package org.cmchat.app.tor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.freehaven.tor.control.TorControlConnection
import org.torproject.jni.TorService as GpTorService

/** Observable Tor connection state. */
sealed interface TorStatus {
    data object Starting : TorStatus
    data class Connecting(val percent: Int) : TorStatus
    data object Online : TorStatus
    data object Offline : TorStatus
    /** Watchdog gave up after the retry cap; needs a manual retry. */
    data class Failed(val reason: String) : TorStatus
}

/**
 * Foreground service that runs Tor (via Guardian Project's tor-android) so it
 * survives backgrounding, and exposes bootstrap progress + state through a
 * StateFlow the UI observes. It binds the library's TorService, listens for
 * its status broadcasts, and polls the control port for bootstrap %.
 *
 * All app networking goes through this Tor instance; the app never opens a
 * direct internet socket.
 */
class TorService : Service() {

    companion object {
        private val _status = MutableStateFlow<TorStatus>(TorStatus.Offline)
        val status: StateFlow<TorStatus> = _status.asStateFlow()

        private const val CHANNEL_ID = "cm_net"
        private const val NOTIF_ID = 7001

        /** Watchdog: if not 100% bootstrapped within this, tear down + restart. */
        private const val BOOTSTRAP_TIMEOUT_MS = 60_000L
        /** Total bootstrap attempts (1 initial + this many restarts) before Failed. */
        private const val MAX_RESTARTS = 1
        /** Restarts used this run; reset to 0 once Online or on a manual retry. */
        @Volatile
        private var restartsUsed = 0

        @Volatile
        private var instance: TorService? = null

        /** The jtorctl control connection while Tor is running, else null. */
        fun controlConnection(): TorControlConnection? =
            instance?.gpService?.torControlConnection

        /** Tor's local SOCKS port (for outgoing connections through Tor). */
        fun socksPort(): Int = instance?.gpService?.socksPort ?: 9050

        fun start(context: Context) {
            // FAST REOPEN: a healthy running Tor is reused, never re-bootstrapped.
            // If we already have a live instance that is Online (or still coming
            // up), startForegroundService only re-delivers onStartCommand to that
            // same instance — it does NOT run onCreate again, so there is never a
            // second bootstrap. The explicit short-circuit avoids even that round
            // trip when Tor is already up.
            if (instance != null && status.value is TorStatus.Online) return
            // Only valid from a foreground context. On API 12+ a background start
            // throws ForegroundServiceStartNotAllowed — catch it (no crash); the
            // next foreground resume will start Tor.
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, TorService::class.java)
                )
            } catch (e: Exception) {
                org.cmchat.app.diag.Diag.e("tor", "deferred FGS start (not foreground)", e)
            }
        }

        /** Manual retry after a Failed state: reset the attempt cap and start. */
        fun retry(context: Context) {
            restartsUsed = 0
            _status.value = TorStatus.Starting
            instance?.let { runCatching { it.restartTor() } } ?: start(context)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TorService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bound = false
    private var gpService: GpTorService? = null
    private var bootstrapJob: Job? = null
    private var watchdogJob: Job? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val s = intent.getStringExtra(GpTorService.EXTRA_STATUS)
            org.cmchat.app.diag.Diag.i("tor", "status=$s")
            when (s) {
                GpTorService.STATUS_STARTING -> _status.value = TorStatus.Starting
                GpTorService.STATUS_ON -> _status.value = TorStatus.Online
                GpTorService.STATUS_STOPPING, GpTorService.STATUS_OFF ->
                    _status.value = TorStatus.Offline
            }
        }
    }

    private val gpConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            gpService = (binder as GpTorService.LocalBinder).service
            startBootstrapPolling()
            startWatchdog()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            gpService = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Single-instance guard: if a previous (possibly half-dead) instance is
        // still around, tear its Tor binding down before we take over so the new
        // start never races a dying old one.
        instance?.takeIf { it !== this }?.let { old -> runCatching { old.teardown() } }
        instance = this
        // startForeground() is the LITERAL FIRST action, before any Tor work, so
        // we never trip ForegroundServiceDidNotStartInTime. The channel is created
        // inside goForeground() before the call.
        goForeground()
        _status.value = TorStatus.Starting
        LocalBroadcastManager.getInstance(this).registerReceiver(
            statusReceiver, IntentFilter(GpTorService.ACTION_STATUS)
        )
        // IMPORTANT: Guardian's org.torproject.jni.TorService never calls
        // startForeground() itself, so starting it with startForegroundService()
        // guarantees the 5s crash. We therefore ONLY BIND it (BIND_AUTO_CREATE) —
        // binding runs its onCreate -> starts the tor thread on its own worker —
        // and WE remain the single foreground service. No chained FGS, no race.
        val intent = Intent(this, GpTorService::class.java)
        bound = bindService(intent, gpConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-assert foreground on every (re)start, incl. START_STICKY restarts.
        goForeground()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * startForeground, immediate and API-branched (channel first; typed on API
     * 29+). This MUST succeed or the OS kills us — so on failure we fall back to
     * an untyped call, and if even that fails we stopSelf rather than linger as a
     * zombie that the watchdog would crash.
     */
    private fun goForeground() {
        val notif = try { buildNotification() } catch (e: Exception) {
            org.cmchat.app.diag.Diag.e("tor", "notification build failed", e); stopSelf(); return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            org.cmchat.app.diag.Diag.e("tor", "typed startForeground failed; retry untyped", e)
            try { startForeground(NOTIF_ID, notif) }
            catch (e2: Exception) {
                org.cmchat.app.diag.Diag.e("tor", "startForeground failed; stopping", e2); stopSelf()
            }
        }
    }

    // The app was swiped from recents. Hand off to the lifecycle policy, which
    // either keeps a minimal buzz-listener alive or goes fully offline.
    override fun onTaskRemoved(rootIntent: Intent?) {
        org.cmchat.app.LifecycleController.onAppClosed(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Fully tear Tor + the onion service down, even when bootstrap is stuck.
     * Idempotent and safe to call from the single-instance guard or onDestroy:
     *   1) stop the onion (DEL_ONION + close the loopback socket),
     *   2) HALT tor via the control port (kills a stuck bootstrap immediately),
     *   3) unbind the Guardian service (its onDestroy stops the tor thread),
     *   4) clear state. Only nulls the static instance if it is still us, so a
     *      freshly started instance is never clobbered by an old one dying.
     */
    private fun teardown() {
        bootstrapJob?.cancel()
        watchdogJob?.cancel()
        runCatching { LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver) }
        runCatching { org.cmchat.app.tor.ServerController.stop() }
        haltTorBounded()
        if (bound) {
            runCatching { unbindService(gpConnection) }
            bound = false
        }
        runCatching { stopService(Intent(this, GpTorService::class.java)) }
        gpService = null
        _status.value = TorStatus.Offline
        if (instance === this) instance = null
    }

    /**
     * RELIABLE STOP: HALT the control port, but never let a stuck control
     * connection (e.g. frozen at 95%) hang the caller. The HALT runs on a daemon
     * thread we join for at most 1.5s; the unbind + stopService that follow in
     * [teardown] kill the tor thread regardless, so stop always completes.
     */
    private fun haltTorBounded() {
        val control = gpService?.torControlConnection ?: return
        val t = Thread { runCatching { control.shutdownTor("HALT") } }.apply { isDaemon = true }
        t.start()
        runCatching { t.join(1500) }
    }

    /**
     * Watchdog: if Tor is not 100% bootstrapped within [BOOTSTRAP_TIMEOUT_MS],
     * tear the Guardian binding down and rebind ONCE. After [MAX_RESTARTS] the
     * state becomes Failed and we stop, so the UI can offer a manual retry.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            val deadline = System.currentTimeMillis() + BOOTSTRAP_TIMEOUT_MS
            while (isActive) {
                when (status.value) {
                    is TorStatus.Online -> { restartsUsed = 0; return@launch }
                    is TorStatus.Failed -> return@launch
                    else -> {}
                }
                if (System.currentTimeMillis() >= deadline) {
                    if (restartsUsed >= MAX_RESTARTS) {
                        org.cmchat.app.diag.Diag.w("watchdog",
                            "Tor not bootstrapped after ${restartsUsed + 1} attempts; failing")
                        _status.value = TorStatus.Failed("Tor failed to connect")
                        teardown()
                    } else {
                        restartsUsed += 1
                        org.cmchat.app.diag.Diag.w("watchdog",
                            "Tor stuck <100% for ${BOOTSTRAP_TIMEOUT_MS}ms; restart #$restartsUsed")
                        restartTor()
                    }
                    return@launch
                }
                delay(1000)
            }
        }
    }

    /**
     * Tear down the Guardian binding (bounded HALT + unbind) and rebind a fresh
     * one. onServiceConnected then restarts bootstrap polling and the watchdog,
     * so this is a full one-shot restart without destroying our own foreground
     * service (never two bootstraps at once — the old binding is gone first).
     */
    private fun restartTor() {
        bootstrapJob?.cancel()
        watchdogJob?.cancel()
        runCatching { org.cmchat.app.tor.ServerController.stop() }
        haltTorBounded()
        if (bound) { runCatching { unbindService(gpConnection) }; bound = false }
        runCatching { stopService(Intent(this, GpTorService::class.java)) }
        gpService = null
        _status.value = TorStatus.Starting
        val intent = Intent(this, GpTorService::class.java)
        bound = bindService(intent, gpConnection, Context.BIND_AUTO_CREATE)
    }

    private fun startBootstrapPolling() {
        bootstrapJob?.cancel()
        bootstrapJob = scope.launch {
            while (isActive && status.value !is TorStatus.Online) {
                val pct = readBootstrapPercent(gpService?.torControlConnection)
                if (pct != null && status.value !is TorStatus.Online) {
                    _status.value = if (pct >= 100) TorStatus.Online else TorStatus.Connecting(pct)
                }
                delay(1000)
            }
        }
    }

    private fun readBootstrapPercent(control: TorControlConnection?): Int? {
        control ?: return null
        return runCatching {
            // e.g. "NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=..."
            val phase = control.getInfo("status/bootstrap-phase") ?: return null
            Regex("PROGRESS=(\\d+)").find(phase)?.groupValues?.get(1)?.toInt()
        }.getOrNull()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Network", NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Active")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }
}

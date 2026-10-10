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

        /** Bumped each time a soft reconnect brought circuits back (the onion
         * stayed up, so there was no Offline → Online edge): retry queued sends. */
        private val _reconnects = MutableStateFlow(0)
        val reconnects: StateFlow<Int> = _reconnects.asStateFlow()

        /** The ONE notification Android requires while the engine runs (a
         * foreground service). New channel id: the old "Active" one is removed. */
        private const val CHANNEL_ID = "cm_engine"
        private const val NOTIF_ID = 7001
        /** Retired channels: the old "Active" (Bluetooth glyph) engine line and the
         * second "Listening" line of the old Buzz listener. Deleted on start. */
        private val OLD_CHANNELS = listOf("cm_net", "cm_listen")

        /** Watchdog: if not 100% bootstrapped within this, tear down + restart. */
        private const val BOOTSTRAP_TIMEOUT_MS = 60_000L
        /** Total bootstrap attempts (1 initial + this many restarts) before Failed. */
        private const val MAX_RESTARTS = 1
        /** Restarts used this run; reset to 0 once Online or on a manual retry. */
        @Volatile
        private var restartsUsed = 0

        @Volatile
        private var instance: TorService? = null

        /** Battery/background diagnostics: when the service last (re)started and
         * how many times — a START_STICKY restart after an OS kill bumps this. */
        @Volatile var serviceStartedAtMs = 0L
            private set
        @Volatile var serviceStarts = 0
            private set

        /** The jtorctl control connection while Tor is running, else null. */
        fun controlConnection(): TorControlConnection? =
            instance?.gpService?.torControlConnection

        /** Tor's local SOCKS port (for outgoing connections through Tor). */
        fun socksPort(): Int = instance?.gpService?.socksPort ?: 9050

        /**
         * Set on the Exit path only. While true, a (re)started engine service
         * refuses to run and reports START_NOT_STICKY, so killing the process on
         * Exit can't be undone by Android's sticky-service restart. Reset by
         * [start] (a fresh unlock) and gone anyway once the process dies.
         */
        @Volatile var exiting = false
            private set

        /** Exit only: don't let START_STICKY bring the engine back after the kill. */
        fun stopForExit(context: Context) {
            exiting = true
            instance?.let { runCatching { it.stopForeground(STOP_FOREGROUND_REMOVE) } }
            stop(context)
        }

        fun start(context: Context) {
            exiting = false
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

        /**
         * A network change happened. ONLINE: a soft reconnect — circuits are
         * rebuilt while the onion stays published ([SoftReconnect]); only if that
         * fails is Tor restarted. Offline/Failed: restart on the new network.
         * Still coming up: leave it alone.
         */
        fun onNetworkChanged() {
            val inst = instance ?: return
            when (status.value) {
                is TorStatus.Starting, is TorStatus.Connecting -> { /* let it finish */ }
                is TorStatus.Online -> inst.softReconnect()
                is TorStatus.Offline, is TorStatus.Failed -> {
                    org.cmchat.app.diag.ConnDiag.sys("network back — restarting Tor")
                    restartsUsed = 0
                    runCatching { inst.restartTor() }
                }
            }
        }

        /**
         * On app resume (e.g. return from Doze): if the engine was running this
         * session (instance != null) but has dropped to Offline/Failed, bring it
         * back. Never starts Tor before first unlock or after an explicit exit
         * (instance is null in both cases) — the unlock flow owns the first start.
         */
        fun ensureHealthy(context: Context) {
            if (instance == null) return
            val s = status.value
            if (s is TorStatus.Offline || s is TorStatus.Failed) {
                org.cmchat.app.diag.ConnDiag.sys("resume — ensuring engine is up")
                retry(context)
            }
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
    @Volatile private var softJob: Job? = null

    /**
     * Network changed while Online: rebuild circuits WITHOUT touching the onion
     * service (see [SoftReconnect]); fall back to [restartTor] only if no circuit
     * comes back. One at a time — a flapping network doesn't stack them.
     */
    private fun softReconnect() {
        if (softJob?.isActive == true) return
        softJob = scope.launch {
            val control = gpService?.torControlConnection
            val ok = control != null && SoftReconnect.run(
                kick = { control.setConf("DisableNetwork", "1"); control.setConf("DisableNetwork", "0") },
                circuitUp = { control.getInfo("status/circuit-established")?.trim() == "1" },
                log = { org.cmchat.app.diag.ConnDiag.sys(it) },
            )
            if (ok) {
                _reconnects.value = _reconnects.value + 1
            } else if (status.value is TorStatus.Online) {
                restartsUsed = 0
                runCatching { restartTor() }
            }
        }
    }

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
        org.cmchat.app.i18n.Tr.attach(this)   // notification text in the chosen language
        // Single-instance guard: if a previous (possibly half-dead) instance is
        // still around, tear its Tor binding down before we take over so the new
        // start never races a dying old one.
        instance?.takeIf { it !== this }?.let { old -> runCatching { old.teardown() } }
        instance = this
        serviceStartedAtMs = System.currentTimeMillis()
        serviceStarts += 1
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
        // bindGuardian() first configures bridges into the torrc (fail-closed).
        bindGuardian()
        // Auto-reconnect on WiFi<->data / signal changes.
        NetworkMonitor.register(this) { onNetworkChanged() }
    }

    /**
     * Configure bridges into the torrc (if enabled) and bind the Guardian Tor
     * service. FAIL CLOSED: if bridges are enabled but the pluggable transport
     * can't start, we set Failed("bridges") and do NOT bind — Tor never makes a
     * direct connection, so the cloak can't be bypassed. Returns whether bound.
     */
    private fun bindGuardian(): Boolean {
        val ready = runCatching { Bridges.prepare(this) }
            .getOrElse { org.cmchat.app.diag.Diag.e("bridges", "prepare failed", it); false }
        if (!ready) {
            _status.value = TorStatus.Failed(if (Bridges.isEnabled()) "bridges" else "config")
            return false
        }
        val intent = Intent(this, GpTorService::class.java)
        bound = bindService(intent, gpConnection, Context.BIND_AUTO_CREATE)
        return bound
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Exit in progress (process about to be killed): do NOT re-foreground, and
        // report NOT_STICKY so the OS won't recreate the engine after the kill.
        if (exiting) { stopSelf(); return START_NOT_STICKY }
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
        softJob?.cancel()
        runCatching { NetworkMonitor.unregister() }
        runCatching { LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver) }
        runCatching { org.cmchat.app.tor.ServerController.stop() }
        runCatching { Bridges.stop() }
        haltTorBounded()
        if (bound) {
            runCatching { unbindService(gpConnection) }
            bound = false
        }
        runCatching { stopService(Intent(this, GpTorService::class.java)) }
        gpService = null
        _status.value = TorStatus.Offline
        // Anti-forensics: wipe tor-android's on-disk cache (consensus/descriptors/
        // state/cookie + our bridge torrc) so a stopped engine leaves no trace that
        // Tor was used. Reconnects use restartTor() (not teardown) so a live
        // session keeps its cache. Async so onDestroy never blocks on disk I/O.
        runCatching { TorFiles.wipeAsync(applicationContext) }
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
                        _status.value = TorStatus.Failed(
                            if (Bridges.isEnabled()) "bridges" else "Tor failed to connect")
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
        // The onion dies with Tor; it's re-published by itself once Tor is back.
        runCatching { org.cmchat.app.tor.ServerController.pauseForTorRestart() }
        haltTorBounded()
        if (bound) { runCatching { unbindService(gpConnection) }; bound = false }
        runCatching { stopService(Intent(this, GpTorService::class.java)) }
        gpService = null
        _status.value = TorStatus.Starting
        // Re-prepares bridges (so a mode change takes effect) then rebinds.
        bindGuardian()
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

    /**
     * The single engine notification (Android shows one for any foreground
     * service): our flower in the brand colour, the lowest importance (no sound,
     * folded away at the bottom of the shade), no text of its own. There is no
     * second "Listening" line any more — the Buzz listener is this same service.
     */
    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            OLD_CHANNELS.forEach { runCatching { nm.deleteNotificationChannel(it) } }
            val channel = NotificationChannel(
                CHANNEL_ID, org.cmchat.app.i18n.Tr.s(org.cmchat.app.R.string.notif_engine_channel), NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(org.cmchat.app.R.drawable.ic_stat_flower)
            .setColor(org.cmchat.app.notify.Notifier.BRAND_COLOR)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }
}

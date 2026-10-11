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


sealed interface TorStatus {
    data object Starting : TorStatus
    data class Connecting(val percent: Int) : TorStatus
    data object Online : TorStatus
    data object Offline : TorStatus

    data class Failed(val reason: String) : TorStatus
}


class TorService : Service() {

    companion object {
        private val _status = MutableStateFlow<TorStatus>(TorStatus.Offline)
        val status: StateFlow<TorStatus> = _status.asStateFlow()


        private val _reconnects = MutableStateFlow(0)
        val reconnects: StateFlow<Int> = _reconnects.asStateFlow()


        private const val CHANNEL_ID = "cm_engine"
        private const val NOTIF_ID = 7001

        private val OLD_CHANNELS = listOf("cm_net", "cm_listen")


        private const val BOOTSTRAP_TIMEOUT_MS = 60_000L

        private const val MAX_RESTARTS = 1

        @Volatile
        private var restartsUsed = 0

        @Volatile
        private var instance: TorService? = null


        @Volatile var serviceStartedAtMs = 0L
            private set
        @Volatile var serviceStarts = 0
            private set


        fun controlConnection(): TorControlConnection? =
            instance?.gpService?.torControlConnection


        fun socksPort(): Int = instance?.gpService?.socksPort ?: 9050


        @Volatile var exiting = false
            private set


        fun stopForExit(context: Context) {
            exiting = true
            instance?.let { runCatching { it.stopForeground(STOP_FOREGROUND_REMOVE) } }
            stop(context)
        }

        fun start(context: Context) {
            exiting = false






            if (instance != null && status.value is TorStatus.Online) return



            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, TorService::class.java)
                )
            } catch (e: Exception) {
                org.cmchat.app.diag.Diag.e("tor", "deferred FGS start (not foreground)", e)
            }
        }


        fun retry(context: Context) {
            restartsUsed = 0
            _status.value = TorStatus.Starting
            instance?.let { runCatching { it.restartTor() } } ?: start(context)
        }


        fun onNetworkChanged() {
            val inst = instance ?: return
            when (status.value) {
                is TorStatus.Starting, is TorStatus.Connecting -> { }
                is TorStatus.Online -> inst.softReconnect()
                is TorStatus.Offline, is TorStatus.Failed -> {
                    org.cmchat.app.diag.ConnDiag.sys("network back — restarting Tor")
                    restartsUsed = 0
                    runCatching { inst.restartTor() }
                }
            }
        }


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
        org.cmchat.app.i18n.Tr.attach(this)



        instance?.takeIf { it !== this }?.let { old -> runCatching { old.teardown() } }
        instance = this
        serviceStartedAtMs = System.currentTimeMillis()
        serviceStarts += 1



        goForeground()
        _status.value = TorStatus.Starting
        LocalBroadcastManager.getInstance(this).registerReceiver(
            statusReceiver, IntentFilter(GpTorService.ACTION_STATUS)
        )






        bindGuardian()

        NetworkMonitor.register(this) { onNetworkChanged() }
    }


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


        if (exiting) { stopSelf(); return START_NOT_STICKY }

        goForeground()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null


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



    override fun onTaskRemoved(rootIntent: Intent?) {
        org.cmchat.app.LifecycleController.onAppClosed(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }


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




        runCatching { TorFiles.wipeAsync(applicationContext) }
        if (instance === this) instance = null
    }


    private fun haltTorBounded() {
        val control = gpService?.torControlConnection ?: return
        val t = Thread { runCatching { control.shutdownTor("HALT") } }.apply { isDaemon = true }
        t.start()
        runCatching { t.join(1500) }
    }


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


    private fun restartTor() {
        bootstrapJob?.cancel()
        watchdogJob?.cancel()

        runCatching { org.cmchat.app.tor.ServerController.pauseForTorRestart() }
        haltTorBounded()
        if (bound) { runCatching { unbindService(gpConnection) }; bound = false }
        runCatching { stopService(Intent(this, GpTorService::class.java)) }
        gpService = null
        _status.value = TorStatus.Starting

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

            val phase = control.getInfo("status/bootstrap-phase") ?: return null
            Regex("PROGRESS=(\\d+)").find(phase)?.groupValues?.get(1)?.toInt()
        }.getOrNull()
    }


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

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

        @Volatile
        private var instance: TorService? = null

        /** The jtorctl control connection while Tor is running, else null. */
        fun controlConnection(): TorControlConnection? =
            instance?.gpService?.torControlConnection

        /** Tor's local SOCKS port (for outgoing connections through Tor). */
        fun socksPort(): Int = instance?.gpService?.socksPort ?: 9050

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, TorService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TorService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bound = false
    private var gpService: GpTorService? = null
    private var bootstrapJob: Job? = null

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
        }

        override fun onServiceDisconnected(name: ComponentName) {
            gpService = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Go foreground IMMEDIATELY, before any Tor work, or Android kills the
        // service (ForegroundServiceDidNotStartInTime). API-branched + typed.
        goForeground()
        _status.value = TorStatus.Starting
        LocalBroadcastManager.getInstance(this).registerReceiver(
            statusReceiver, IntentFilter(GpTorService.ACTION_STATUS)
        )
        val intent = Intent(this, GpTorService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bound = bindService(intent, gpConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-assert foreground on every (re)start, incl. START_STICKY restarts.
        goForeground()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** startForeground, immediate and API-branched; typed on API 29+. */
    private fun goForeground() {
        runCatching {
            val notif = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        }.onFailure { org.cmchat.app.diag.Diag.e("tor", "startForeground failed", it) }
    }

    // The app was swiped from recents. Hand off to the lifecycle policy, which
    // either keeps a minimal buzz-listener alive or goes fully offline.
    override fun onTaskRemoved(rootIntent: Intent?) {
        org.cmchat.app.LifecycleController.onAppClosed(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        bootstrapJob?.cancel()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
        if (bound) {
            runCatching { unbindService(gpConnection) }
            bound = false
        }
        runCatching { stopService(Intent(this, GpTorService::class.java)) }
        _status.value = TorStatus.Offline
        instance = null
        scope.cancel()
        super.onDestroy()
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

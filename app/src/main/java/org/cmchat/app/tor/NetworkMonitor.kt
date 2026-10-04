package org.cmchat.app.tor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import org.cmchat.app.diag.ConnDiag

/**
 * Watches the device's default network (WiFi ⇄ mobile data, signal drop,
 * airplane toggle) and asks [TorService] to re-establish Tor on a change, so
 * the engine survives real-world network churn. Changes are debounced so a
 * flapping connection can't storm reconnects. Uses the existing
 * teardown/watchdog path (no collision storm, no 95% hang).
 */
object NetworkMonitor {

    private const val DEBOUNCE_MS = 3_000L

    @Volatile private var cm: ConnectivityManager? = null
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastTriggerMs = 0L

    fun register(context: Context, onChange: () -> Unit) {
        if (callback != null) return
        val mgr = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = trigger("network available", onChange)
            override fun onLost(network: Network) { ConnDiag.sys("network lost") }
        }
        cm = mgr
        callback = cb
        runCatching { mgr.registerDefaultNetworkCallback(cb) }
            .onFailure { ConnDiag.sys("network monitor unavailable") }
    }

    private fun trigger(reason: String, onChange: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastTriggerMs < DEBOUNCE_MS) return
        lastTriggerMs = now
        ConnDiag.sys("$reason → checking engine")
        onChange()
    }

    fun unregister() {
        val c = callback ?: return
        runCatching { cm?.unregisterNetworkCallback(c) }
        callback = null
        cm = null
    }
}

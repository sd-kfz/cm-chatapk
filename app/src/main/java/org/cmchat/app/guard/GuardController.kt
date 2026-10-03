package org.cmchat.app.guard

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.cmchat.app.chat.ChatStore
import org.cmchat.app.tor.ServerController
import org.cmchat.app.tor.TorService

/**
 * Runtime driver for the guardians. On expiry it performs a silent RAM wipe:
 * clears all chat state, stops the onion server and Tor, and kills the
 * process. The vault (identities + Circle) stays; the next open needs the PIN.
 *
 * Idle tracking (touch resets Cerberus) and the kill deadline are driven from
 * the UI; the actual process kill is Android runtime and only observable on a
 * device.
 */
object GuardController {

    private val _cerberusArmed = MutableStateFlow(true)
    val cerberusArmed: StateFlow<Boolean> = _cerberusArmed.asStateFlow()

    private val _killDeadline = MutableStateFlow<Long?>(null)
    val killDeadline: StateFlow<Long?> = _killDeadline.asStateFlow()

    @Volatile private var cerberusIdleMs: Long = GuardLogic.CERBERUS_90_MIN
    @Volatile private var lastTouch: Long = System.currentTimeMillis()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        touch()
        if (loop == null) loop = scope.launch { watch() }
    }

    /** Any interaction (incl. reopening from recents) resets the idle clock. */
    fun touch() { lastTouch = System.currentTimeMillis() }

    fun setCerberusMinutes(minutes: Int) {
        cerberusIdleMs = if (minutes >= 180) GuardLogic.CERBERUS_180_MIN else GuardLogic.CERBERUS_90_MIN
    }

    fun setCerberusArmed(armed: Boolean) { _cerberusArmed.value = armed }

    fun armKillTimer(durationMs: Long) {
        val d = durationMs.coerceIn(0, GuardLogic.KILL_MAX_MS)
        _killDeadline.value = System.currentTimeMillis() + d
    }

    fun cancelKillTimer() { _killDeadline.value = null }

    private suspend fun watch() {
        while (scope.isActive) {
            val now = System.currentTimeMillis()
            val kill = _killDeadline.value
            val cerberusFires = _cerberusArmed.value &&
                GuardLogic.cerberusExpired(lastTouch, cerberusIdleMs, now)
            val killFires = kill != null && now >= kill
            if (cerberusFires || killFires) {
                wipeAndDie()
                return
            }
            delay(1000)
        }
    }

    /** Silent RAM wipe: drop chats, stop server + Tor, kill the process. */
    fun wipeAndDie() {
        ChatStore.clearAll()
        org.cmchat.app.tools.ToolsState.clear()
        org.cmchat.app.buzz.BuzzPolicy.clear()
        org.cmchat.app.diag.Diag.clear()
        appContext?.let {
            org.cmchat.app.diag.CrashCatcher.delete(it)
            org.cmchat.app.notify.Notifier.clearAll(it)
            org.cmchat.app.tor.BuzzListenerService.stop(it)
            org.cmchat.app.tools.Flashlight.off(it)
        }
        ServerController.stop()
        appContext?.let { TorService.stop(it) }
        // Give the stop calls a moment, then end the process.
        scope.launch {
            delay(300)
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}

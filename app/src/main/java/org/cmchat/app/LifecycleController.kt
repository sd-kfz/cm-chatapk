package org.cmchat.app

import android.content.Context
import org.cmchat.app.buzz.BuzzPolicy
import org.cmchat.app.chat.ChatStore
import org.cmchat.app.diag.Diag
import org.cmchat.app.notify.Notifier
import org.cmchat.app.settings.AppSettings
import org.cmchat.app.tools.ToolsState
import org.cmchat.app.tor.BuzzListenerService
import org.cmchat.app.tor.ServerController
import org.cmchat.app.tor.TorService
import org.cmchat.app.transport.MessageService

/**
 * App open/close lifecycle policy (item 6 of the batch):
 *
 *  - Swiped from recents (onTaskRemoved) = CLOSED: stop messaging, go OFFLINE,
 *    clear ALL RAM state (messages, notes, statuses). The vault stays, so the
 *    next open needs the PIN. EXCEPTION: if "Let a Buzz reach me when closed"
 *    is ON (and not in Invisible mode), a minimal buzz-listener stays alive so a
 *    BUZZ can still post an "Activity" notification; everything else is dropped.
 *  - Minimised (still in recents): stays ONLINE and keeps RAM (handled by not
 *    calling this); Cerberus keeps counting because minimising is NOT "touching".
 *  - Returning to the foreground resumes normal messaging.
 */
object LifecycleController {

    @Volatile
    var listening = false
        private set

    /** Emitted when the app is backgrounded and should re-lock (require PIN). */
    val lockRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * App moved to the background (minimised). Unless "stay reachable" is on,
     * re-lock the UI and wipe the vault-unlock material from RAM (PIN + decrypted
     * vault held in the UI layer) so returning needs the PIN. The service stays
     * running, so minimised = still online and Cerberus keeps counting.
     */
    fun onAppBackground() {
        if (org.cmchat.app.settings.AppSettings.stayReachable.value) return
        // 6h session window: don't re-lock while it's still valid.
        if (org.cmchat.app.settings.AppSettings.sessionStillValid()) return
        lockRequests.tryEmit(Unit)
    }

    /** The user swiped the app away. */
    fun onAppClosed(context: Context) {
        val ctx = context.applicationContext
        // Stay reachable: keep the full server + RAM alive until the user Exits.
        if (org.cmchat.app.settings.AppSettings.stayReachable.value) {
            Diag.i("life", "closed but staying reachable")
            return
        }
        // RAM is dropped either way.
        ChatStore.clearAll()
        ToolsState.clear()
        BuzzPolicy.clear()
        Notifier.clearAll(ctx)

        val keepListening = AppSettings.buzzListenerWhenClosed.value &&
            !AppSettings.invisibleMode.value
        if (keepListening) {
            // Buzz-only: Tor + onion stay up; only a BUZZ does anything now.
            MessageService.buzzOnlyMode = true
            MessageService.activeChatCmId = null
            BuzzListenerService.start(ctx)
            listening = true
            Diag.i("life", "closed -> buzz-listener alive")
        } else {
            fullClose(ctx)
        }
    }

    /** The user came back to the foreground. */
    fun onAppForeground() {
        if (listening) {
            MessageService.buzzOnlyMode = false
            AppSettings.appContext?.let { BuzzListenerService.stop(it) }
            listening = false
            Diag.i("life", "foreground -> normal")
        }
    }

    /** Exit: stop the server, clear RAM, drop the listener, and log out. */
    fun exit(context: Context) {
        val ctx = context.applicationContext
        ChatStore.clearAll()
        ToolsState.clear()
        BuzzPolicy.clear()
        Notifier.clearAll(ctx)
        fullClose(ctx)
        lockRequests.tryEmit(Unit)
    }

    /** Fully go dark: stop the server and Tor, and drop the listener. */
    private fun fullClose(ctx: Context) {
        MessageService.buzzOnlyMode = false
        MessageService.activeChatCmId = null
        listening = false
        org.cmchat.app.transport.CoverTraffic.stop()
        org.cmchat.app.tools.Flashlight.off(ctx)
        ServerController.stop()
        BuzzListenerService.stop(ctx)
        TorService.stop(ctx)
        Diag.i("life", "closed -> fully offline")
    }
}

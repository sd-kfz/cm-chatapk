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
import kotlinx.coroutines.launch
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

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    /**
     * Until this time, a background event is OUR OWN doing: we just opened the
     * QR scanner, the share sheet, or an Android settings screen. Those cover the
     * app (onStop) but the user hasn't left — re-locking then is what made a scan
     * "re-ask the PIN and die" (the scan result came back to a locked app).
     */
    @Volatile private var ownLaunchUntil = 0L
    @Volatile private var deferredLock: kotlinx.coroutines.Job? = null

    /** Call right before launching our own scanner / share sheet / settings page. */
    fun expectOwnLaunch(windowMs: Long = 3 * 60_000L) {
        ownLaunchUntil = System.currentTimeMillis() + windowMs
    }

    /**
     * The user left OUR scanner for Home / another app (not back to CM-Chat):
     * don't wait out the window — lock now, exactly as leaving the app would.
     */
    fun ownScreenLeft() {
        if (ownLaunchUntil == 0L) return
        deferredLock?.cancel(); deferredLock = null
        ownLaunchUntil = 0L
        if (org.cmchat.app.settings.AppSettings.stayReachable.value) return
        if (org.cmchat.app.settings.AppSettings.sessionStillValid()) return
        lockRequests.tryEmit(Unit)
    }

    /** The app's screen is in front of the user (between onResume and onStop). */
    @Volatile
    var inForeground = false
        private set

    /**
     * App moved to the background (minimised). Unless "stay reachable" is on,
     * re-lock the UI and wipe the vault-unlock material from RAM (PIN + decrypted
     * vault held in the UI layer) so returning needs the PIN. The service stays
     * running, so minimised = still online and Cerberus keeps counting.
     */
    fun onAppBackground() {
        inForeground = false
        // Shredder aftermath: leaving the app clears its error, so reopening
        // shows a normal (fresh) lock screen — never a dead one.
        org.cmchat.app.vault.Shredder.reset()
        if (org.cmchat.app.settings.AppSettings.stayReachable.value) return
        // 6h session window: don't re-lock while it's still valid.
        if (org.cmchat.app.settings.AppSettings.sessionStillValid()) return
        val left = ownLaunchUntil - System.currentTimeMillis()
        if (left > 0) {
            // We opened that screen ourselves: stay unlocked so its result can come
            // back — but if the user doesn't return within the window (e.g. went
            // Home from the scanner), lock anyway.
            deferredLock?.cancel()
            deferredLock = scope.launch {
                kotlinx.coroutines.delay(left)
                ownLaunchUntil = 0L
                lockRequests.tryEmit(Unit)
            }
            return
        }
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
        // RAM is dropped either way (incl. anything still queued to send), and
        // the vault key leaves RAM.
        org.cmchat.app.vault.SecurityFactory.lockIfCreated()
        MessageService.clearOutbox()
        ChatStore.clearAll()
        ToolsState.clear()
        BuzzPolicy.clear()
        Notifier.clearAll(ctx)

        val keepListening = AppSettings.buzzListenerWhenClosed.value &&
            !AppSettings.invisibleMode.value
        // Closing ends this start: the next open starts Invisible.
        AppSettings.startupPresence()
        if (keepListening) {
            // Buzz-only: Tor + onion stay up; only a BUZZ does anything now.
            MessageService.buzzOnlyMode = true
            MessageService.activeChatCmId = null
            BuzzListenerService.start(ctx)
            listening = true
            Diag.i("life", "closed -> buzz-listener alive")
            org.cmchat.app.diag.ConnDiag.sys("App closed: Buzz-only — messages are dropped until you reopen the app")
        } else {
            fullClose(ctx)
            org.cmchat.app.diag.ConnDiag.sys("App closed: fully offline — nothing reaches you until you reopen")
        }
    }

    /** The user came back to the foreground. */
    fun onAppForeground() {
        inForeground = true
        // Back from our own scanner/share sheet: cancel the safety lock.
        deferredLock?.cancel(); deferredLock = null
        ownLaunchUntil = 0L
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
        MessageService.clearOutbox()
        // Anti-seizure: the identity keys and the friend table leave RAM too.
        // They come back from the vault at the next unlock.
        MessageService.zeroKeys()
        org.cmchat.app.vault.SecurityFactory.lockIfCreated()   // the vault key too
        AppSettings.startupPresence()
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

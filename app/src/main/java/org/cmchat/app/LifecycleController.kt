package org.cmchat.app

import android.content.Context
import org.cmchat.app.buzz.BuzzPolicy
import org.cmchat.app.chat.ChatStore
import org.cmchat.app.diag.Diag
import org.cmchat.app.notify.Notifier
import org.cmchat.app.settings.AppSettings
import org.cmchat.app.tools.ToolsState
import org.cmchat.app.tor.ServerController
import org.cmchat.app.tor.TorService
import kotlinx.coroutines.launch
import org.cmchat.app.transport.MessageService

/**
 * App open/close lifecycle policy:
 *
 *  - Swiped from recents (onTaskRemoved) = CLOSED: the vault key and the chats
 *    leave RAM (messages, notes, statuses); the next open needs the PIN. The
 *    Buzz listener is ALWAYS on now: Tor and my onion stay up (the one engine
 *    notification), a BUZZ still notifies, and everything else that arrives is
 *    HELD — sealed to my identity key, on flash — and shown as "Missed Message"
 *    after the next unlock. Nothing is dropped and then counted as delivered.
 *  - Minimised (still in recents): stays ONLINE and keeps RAM (handled by not
 *    calling this); Cerberus keeps counting because minimising is NOT "touching".
 *  - Returning to the foreground resumes normal messaging.
 *  - Exit: every key leaves RAM (identity, friends, vault key) and the engine
 *    stops — also when the app had been closed with the listener running.
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
        closeToListener()
        Notifier.clearAll(ctx)
        Diag.i("life", "closed -> listening")
        org.cmchat.app.diag.ConnDiag.sys("App closed: listening — a Buzz notifies; messages are held " +
            "(sealed) and shown as Missed after you unlock")
    }

    /**
     * The RAM side of closing (no Android Context, so a JVM test runs exactly
     * this). The vault key leaves RAM; from now on what arrives is HELD until
     * the next unlock. Queued chat content is dropped from RAM; what the
     * friendship needs (my address, an acceptance, a terminate) keeps going.
     * The Buzz listener is always on: Tor + my onion stay up under the one
     * engine notification. (Identity keys stay in RAM for it — decision B; Exit
     * removes them.)
     */
    internal fun closeToListener() {
        MessageService.closeVault()
        org.cmchat.app.vault.SecurityFactory.lockIfCreated()
        MessageService.dropQueuedContent()
        ChatStore.clearAll()
        ToolsState.clear()
        BuzzPolicy.clear()
        // Closing ends this start: the next open starts Invisible.
        AppSettings.startupPresence()
        MessageService.buzzOnlyMode = true
        MessageService.activeChatCmId = null
        listening = true
    }

    /** The user came back to the foreground. */
    fun onAppForeground() {
        inForeground = true
        // Back from our own scanner/share sheet: cancel the safety lock.
        deferredLock?.cancel(); deferredLock = null
        ownLaunchUntil = 0L
        if (listening) {
            MessageService.buzzOnlyMode = false
            listening = false
            Diag.i("life", "foreground -> normal")
        }
    }

    /**
     * Exit / anti-seizure: every key leaves RAM — the identity keys and friend
     * table (they come back from the vault at the next unlock), the vault key,
     * the chats — also when the app had been closed with the listener running.
     * No Android Context needed, so a JVM test runs exactly this.
     */
    internal fun dropSessionKeys() {
        MessageService.clearOutbox()
        MessageService.zeroKeys()
        org.cmchat.app.vault.SecurityFactory.lockIfCreated()   // the vault key too
        AppSettings.startupPresence()
        ChatStore.clearAll()
        ToolsState.clear()
        BuzzPolicy.clear()
        listening = false
    }

    /** Exit: stop the server, clear RAM, drop the listener, and log out. */
    fun exit(context: Context) {
        val ctx = context.applicationContext
        dropSessionKeys()
        Notifier.clearAll(ctx)
        fullClose(ctx)
        lockRequests.tryEmit(Unit)
    }

    /** Fully go dark: stop the server and Tor. */
    private fun fullClose(ctx: Context) {
        MessageService.buzzOnlyMode = false
        MessageService.activeChatCmId = null
        listening = false
        org.cmchat.app.transport.CoverTraffic.stop()
        org.cmchat.app.tools.Flashlight.off(ctx)
        ServerController.stop()
        TorService.stop(ctx)
        Diag.i("life", "closed -> fully offline")
    }
}

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


object LifecycleController {

    @Volatile
    var listening = false
        private set


    val lockRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)


    @Volatile private var ownLaunchUntil = 0L
    @Volatile private var deferredLock: kotlinx.coroutines.Job? = null


    fun expectOwnLaunch(windowMs: Long = 3 * 60_000L) {
        ownLaunchUntil = System.currentTimeMillis() + windowMs
    }


    fun ownScreenLeft() {
        if (ownLaunchUntil == 0L) return
        deferredLock?.cancel(); deferredLock = null
        ownLaunchUntil = 0L
        if (org.cmchat.app.settings.AppSettings.stayReachable.value) return
        if (org.cmchat.app.settings.AppSettings.sessionStillValid()) return
        lockRequests.tryEmit(Unit)
    }


    @Volatile
    var inForeground = false
        private set


    fun onAppBackground() {
        inForeground = false


        org.cmchat.app.vault.Shredder.reset()
        if (org.cmchat.app.settings.AppSettings.stayReachable.value) return

        if (org.cmchat.app.settings.AppSettings.sessionStillValid()) return
        val left = ownLaunchUntil - System.currentTimeMillis()
        if (left > 0) {



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


    fun onAppClosed(context: Context) {
        val ctx = context.applicationContext

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


    internal fun closeToListener() {
        MessageService.closeVault()
        org.cmchat.app.vault.SecurityFactory.lockIfCreated()
        MessageService.dropQueuedContent()
        ChatStore.clearAll()
        ToolsState.clear()
        BuzzPolicy.clear()

        AppSettings.startupPresence()
        MessageService.buzzOnlyMode = true
        MessageService.activeChatCmId = null
        listening = true
    }


    fun onAppForeground() {
        inForeground = true

        deferredLock?.cancel(); deferredLock = null
        ownLaunchUntil = 0L
        if (listening) {
            MessageService.buzzOnlyMode = false
            listening = false
            Diag.i("life", "foreground -> normal")
        }
    }


    internal fun dropSessionKeys() {
        MessageService.clearOutbox()
        MessageService.zeroKeys()
        org.cmchat.app.vault.SecurityFactory.lockIfCreated()
        AppSettings.startupPresence()
        ChatStore.clearAll()
        ToolsState.clear()
        BuzzPolicy.clear()
        listening = false
    }


    fun exit(context: Context) {
        val ctx = context.applicationContext
        dropSessionKeys()
        Notifier.clearAll(ctx)
        fullClose(ctx)
        lockRequests.tryEmit(Unit)
        hardKill()
    }


    private fun hardKill() {
        val kill = Runnable { runCatching { android.os.Process.killProcess(android.os.Process.myPid()) } }
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(kill, 300)
        }.onFailure { kill.run() }
    }


    private fun fullClose(ctx: Context) {
        MessageService.buzzOnlyMode = false
        MessageService.activeChatCmId = null
        listening = false
        org.cmchat.app.transport.CoverTraffic.stop()
        org.cmchat.app.tools.Flashlight.off(ctx)
        ServerController.stop()
        TorService.stopForExit(ctx)
        Diag.i("life", "closed -> fully offline")
    }
}

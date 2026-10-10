package org.cmchat.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.cmchat.app.guard.GuardController
import org.cmchat.app.ui.AppNav
import org.cmchat.app.ui.theme.CmChatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Application context for background notifications (buzz listener).
        org.cmchat.app.settings.AppSettings.appContext = applicationContext
        // The app's text, in the chosen language (the phone's until the vault says).
        org.cmchat.app.i18n.Tr.attach(this)
        // Where frames that arrive while locked are held (sealed) until unlock.
        org.cmchat.app.transport.MessageService.heldDir = java.io.File(filesDir, "held")
        org.cmchat.app.diag.CrashCatcher.install(this)
        // No screenshots, blank in recents, no screen recording — ALWAYS in
        // release. In debug builds FLAG_SECURE is left OFF so test builds can be
        // screenshotted; a shipped release build is never screenshottable.
        if (!BuildConfig.DEBUG) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        setContent {
            // Every screen gets the chosen language's resources; picking another
            // language swaps them and the whole UI redraws in place (no restart).
            val res = org.cmchat.app.i18n.Tr.resources
            val base = androidx.compose.ui.platform.LocalContext.current
            val ctx = androidx.compose.runtime.remember(res, base) { org.cmchat.app.i18n.Tr.wrap(base, res) }
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides ctx,
            ) {
                // Every text field (messages, nicknames, PINs, notes) tells the
                // keyboard not to learn what is typed: nothing from CM-Chat ends
                // up in the keyboard's own dictionary or suggestion history.
                @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
                androidx.compose.ui.platform.InterceptPlatformTextInput(IncognitoKeyboard) {
                    CmChatTheme {
                        AppNav()
                    }
                }
            }
        }
    }

    // Cerberus is an IDLE wipe: every real touch / key press is activity and
    // resets its clock (it used to reset only on resume, so someone chatting
    // non-stop could be wiped mid-conversation). Setting a timestamp is free.
    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        GuardController.touch()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        GuardController.touch()
        return super.dispatchKeyEvent(event)
    }

    // Reopening from recents / returning to the app counts as touching it,
    // which resets the Cerberus idle clock.
    override fun onResume() {
        super.onResume()
        GuardController.touch()
        // Returning to the foreground resumes normal messaging (ends buzz-only).
        org.cmchat.app.LifecycleController.onAppForeground()
        // Return from sleep/Doze: nudge the engine if the OS dropped it.
        org.cmchat.app.tor.TorService.ensureHealthy(applicationContext)
    }

    // The screen is gone (Exit, swiped away, or recreated): the vault key must not
    // outlive it. A recreated screen starts at the lock screen anyway.
    override fun onDestroy() {
        org.cmchat.app.transport.MessageService.closeVault()   // what arrives now is held
        org.cmchat.app.vault.SecurityFactory.lockIfCreated()
        super.onDestroy()
    }

    // Backgrounded (minimised): re-lock + wipe vault-unlock material from RAM,
    // unless "stay reachable" is on. The service keeps running so we stay online.
    override fun onStop() {
        super.onStop()
        org.cmchat.app.LifecycleController.onAppBackground()
    }
}

/** Asks the keyboard for "incognito" input: no personalised learning. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private object IncognitoKeyboard : androidx.compose.ui.platform.PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(
        request: androidx.compose.ui.platform.PlatformTextInputMethodRequest,
        nextHandler: androidx.compose.ui.platform.PlatformTextInputSession,
    ): Nothing = nextHandler.startInputMethod(object : androidx.compose.ui.platform.PlatformTextInputMethodRequest {
        override fun createInputConnection(outAttributes: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection {
            val ic = request.createInputConnection(outAttributes)
            outAttributes.imeOptions = outAttributes.imeOptions or
                android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            return ic
        }
    })
}

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

        org.cmchat.app.settings.AppSettings.appContext = applicationContext

        org.cmchat.app.i18n.Tr.attach(this)

        org.cmchat.app.transport.MessageService.heldDir = java.io.File(filesDir, "held")
        org.cmchat.app.diag.CrashCatcher.install(this)


        val coverOn = org.cmchat.app.tools.CoverMode.isOn(this)
        org.cmchat.app.tools.CoverDisguise.apply(this, coverOn)
        setTaskDescription(android.app.ActivityManager.TaskDescription(
            getString(if (coverOn) R.string.label_cover else R.string.app_name)))



        if (!BuildConfig.ALLOW_SHOTS) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        setContent {


            val res = org.cmchat.app.i18n.Tr.resources
            val base = androidx.compose.ui.platform.LocalContext.current
            val ctx = androidx.compose.runtime.remember(res, base) { org.cmchat.app.i18n.Tr.wrap(base, res) }
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides ctx,
            ) {



                @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
                androidx.compose.ui.platform.InterceptPlatformTextInput(IncognitoKeyboard) {
                    CmChatTheme {
                        AppNav()
                    }
                }
            }
        }
    }




    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        GuardController.touch()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        GuardController.touch()
        return super.dispatchKeyEvent(event)
    }



    override fun onResume() {
        super.onResume()
        GuardController.touch()

        org.cmchat.app.LifecycleController.onAppForeground()

        org.cmchat.app.tor.TorService.ensureHealthy(applicationContext)
    }



    override fun onDestroy() {
        org.cmchat.app.transport.MessageService.closeVault()
        org.cmchat.app.vault.SecurityFactory.lockIfCreated()
        super.onDestroy()
    }



    override fun onStop() {
        super.onStop()
        org.cmchat.app.LifecycleController.onAppBackground()
    }
}


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

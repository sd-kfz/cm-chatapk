package org.cmchat.app.i18n

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateOf
import java.util.Locale

/**
 * The app's text, in the language picked in Settings — switched LIVE.
 *
 * The strings live in res/values-xx/strings.xml. [s] reads them through a
 * snapshot state: a screen that showed a string is redrawn by itself when the
 * language changes (no restart, no recreate), and the same call works outside
 * the UI (notifications, toasts).
 *
 * The choice is stored ONLY inside the encrypted vault, so before the first
 * unlock of a fresh start the app follows the phone's language (nothing about
 * the user is written unencrypted). "" = follow the phone.
 */
object Tr {
    @Volatile private var app: Context? = null
    private var tag = ""
    private val res = mutableStateOf<Resources?>(null)

    /** Call once with any context (activity, service) before showing text. */
    fun attach(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        res.value = build(tag)
    }

    /** Switch every screen to [newTag] ("" = the phone's language). */
    fun use(newTag: String) {
        tag = newTag
        if (app != null) res.value = build(newTag)
    }

    /** The resources in use (a snapshot read: whoever reads it redraws on a switch). */
    val resources: Resources?
        get() = res.value

    fun s(@StringRes id: Int): String = (res.value ?: app?.resources)?.getString(id) ?: ""

    fun s(@StringRes id: Int, vararg args: Any?): String =
        (res.value ?: app?.resources)?.getString(id, *args) ?: ""

    /** A count in the right plural form ("1 friend" / "2 friends" / Polish, Russian…). */
    fun q(@PluralsRes id: Int, n: Int, vararg args: Any?): String =
        (res.value ?: app?.resources)?.getQuantityString(id, n, *args) ?: ""

    /** [base] (the activity) with this language's resources — for Compose's LocalContext. */
    fun wrap(base: Context, r: Resources?): Context =
        if (r == null) base else object : ContextWrapper(base) {
            override fun getResources(): Resources = r
        }

    private fun build(t: String): Resources? {
        val c = app ?: return null
        val loc = locale(t) ?: return c.resources
        val conf = Configuration(c.resources.configuration)
        conf.setLocale(loc)
        return c.createConfigurationContext(conf).resources
    }

    /** The decoy row's name: the untouched default ("Notes to self") is shown in the
     * chosen language, so the fake chat never stands out; a name the user typed is kept. */
    fun decoyName(stored: String): String =
        if (stored.isBlank() || stored == DEFAULT_DECOY) s(org.cmchat.app.R.string.decoy_default_name) else stored

    const val DEFAULT_DECOY = "Notes to self"

    /** "" = the phone's own language. Norwegian's resources are under "nb". */
    fun locale(t: String): Locale? = when (t) {
        "" -> null
        "no" -> Locale.forLanguageTag("nb")
        else -> Locale.forLanguageTag(t)
    }
}

package org.cmchat.app.i18n

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateOf
import java.util.Locale


object Tr {
    @Volatile private var app: Context? = null
    private var tag = ""
    private val res = mutableStateOf<Resources?>(null)


    fun attach(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        res.value = build(tag)
    }


    fun use(newTag: String) {
        tag = newTag
        if (app != null) res.value = build(newTag)
    }


    val resources: Resources?
        get() = res.value

    fun s(@StringRes id: Int): String = (res.value ?: app?.resources)?.getString(id) ?: ""

    fun s(@StringRes id: Int, vararg args: Any?): String =
        (res.value ?: app?.resources)?.getString(id, *args) ?: ""


    fun q(@PluralsRes id: Int, n: Int, vararg args: Any?): String =
        (res.value ?: app?.resources)?.getQuantityString(id, n, *args) ?: ""


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


    fun decoyName(stored: String): String =
        if (stored.isBlank() || stored == DEFAULT_DECOY) s(org.cmchat.app.R.string.decoy_default_name) else stored

    const val DEFAULT_DECOY = "Notes to self"


    fun locale(t: String): Locale? = when (t) {
        "" -> null
        "no" -> Locale.forLanguageTag("nb")
        else -> Locale.forLanguageTag(t)
    }
}

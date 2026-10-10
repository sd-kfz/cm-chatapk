package org.cmchat.app.settings

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The app's languages and the current choice. Every language has its own
 * res/values-xx/strings.xml; picking one switches the whole app at once
 * ([org.cmchat.app.i18n.Tr]). The choice is kept in the vault.
 */
object Languages {
    /** (tag, native name). Norwegian (Bokmål) is "no" here, "nb" in res/. */
    val list: List<Pair<String, String>> = listOf(
        "en" to "English",
        "ro" to "Română",
        "fr" to "Français",
        "de" to "Deutsch",
        "nl" to "Nederlands",
        "hu" to "Magyar",
        "cs" to "Čeština",
        "it" to "Italiano",
        "es" to "Español",
        "pl" to "Polski",
        "ru" to "Русский",
        "uk" to "Українська",
        "fi" to "Suomi",
        "no" to "Norsk",
    )

    /** The chosen tag; "" = follow the phone's language. */
    val selected = MutableStateFlow("")

    /** Pick a language: every screen switches now. */
    fun select(tag: String) {
        selected.value = tag
        org.cmchat.app.i18n.Tr.use(tag)
    }

    /** The tag actually shown: the choice, or the phone's language when it is one of ours. */
    fun effective(tag: String = selected.value): String {
        if (tag.isNotEmpty()) return tag
        val phone = java.util.Locale.getDefault().language.let { if (it == "nb" || it == "nn") "no" else it }
        return list.firstOrNull { it.first == phone }?.first ?: "en"
    }

    fun displayName(tag: String): String =
        list.firstOrNull { it.first == effective(tag) }?.second ?: "English"
}

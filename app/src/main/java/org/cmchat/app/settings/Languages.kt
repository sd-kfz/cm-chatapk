package org.cmchat.app.settings

import kotlinx.coroutines.flow.MutableStateFlow


object Languages {

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


    val selected = MutableStateFlow("")


    fun select(tag: String) {
        selected.value = tag
        org.cmchat.app.i18n.Tr.use(tag)
    }


    fun effective(tag: String = selected.value): String {
        if (tag.isNotEmpty()) return tag
        val phone = java.util.Locale.getDefault().language.let { if (it == "nb" || it == "nn") "no" else it }
        return list.firstOrNull { it.first == phone }?.first ?: "en"
    }

    fun displayName(tag: String): String =
        list.firstOrNull { it.first == effective(tag) }?.second ?: "English"
}

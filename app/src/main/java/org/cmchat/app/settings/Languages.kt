package org.cmchat.app.settings

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The app's language list and current selection. English ships complete; the
 * other locales are scaffolded for later translation (strings are still English
 * until each locale's resources are filled in). The user's choice is persisted
 * in the vault. Finnish (fi) and Norwegian (no) are included.
 */
object Languages {
    /** (BCP-47 tag, native display name). */
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
        "fi" to "Suomi",        // Finnish (Finland)
        "no" to "Norsk",        // Norwegian (Norway)
    )

    /** Currently selected language tag (RAM; mirrored from the vault). */
    val selected = MutableStateFlow("en")

    fun displayName(tag: String): String =
        list.firstOrNull { it.first == tag }?.second ?: "English"
}

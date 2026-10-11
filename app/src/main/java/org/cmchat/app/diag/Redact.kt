package org.cmchat.app.diag


object Redact {


    private val onion = Regex("[a-z2-7]{56}(\\.onion)?", RegexOption.IGNORE_CASE)

    private val longHex = Regex("[0-9a-fA-F]{32,}")

    fun scrub(s: String): String =
        s.replace(onion) { m -> m.value.take(6) + "…onion-redacted" }
            .replace(longHex) { "<hex-redacted>" }


    fun onionShort(onion: String): String =
        onion.removeSuffix(".onion").take(6) + "…"
}

package org.cmchat.app.diag

/**
 * Last-line-of-defence scrubber for anything headed for a log, the diagnostics
 * buffer, or a crash trace. Even though call sites are written never to log
 * secrets, this guarantees that a FULL v3 onion address or a long hex blob
 * (identity / onion private key, cmId payload) can never leak into a persisted
 * or displayed string. It is deliberately conservative: it only collapses
 * tokens that LOOK like secrets, so ordinary log text is untouched.
 */
object Redact {

    // 56-char base32 = a v3 onion service id (optionally with ".onion").
    private val onion = Regex("[a-z2-7]{56}(\\.onion)?", RegexOption.IGNORE_CASE)
    // 32+ hex chars = a key blob or similar (randomHex(8) = 16 chars, below this).
    private val longHex = Regex("[0-9a-fA-F]{32,}")

    fun scrub(s: String): String =
        s.replace(onion) { m -> m.value.take(6) + "…onion-redacted" }
            .replace(longHex) { "<hex-redacted>" }

    /** A short, safe handle for an onion address: first 6 chars only. */
    fun onionShort(onion: String): String =
        onion.removeSuffix(".onion").take(6) + "…"
}

package org.cmchat.app.transport

import kotlinx.serialization.json.Json

/** JSON payloads carried inside frames. Small, versionable, RAM-only. */
object Messages {
    val json = Json { ignoreUnknownKeys = true }
}

@kotlinx.serialization.Serializable
data class KnockPayload(
    val displayName: String,
    val cmId: String,
    /** true = "I cancelled my request": the recipient just removes the card. */
    val withdraw: Boolean = false,
    /** 16 random bytes (hex) the recipient echoes in the knock's receipt. */
    val nonce: String = "",
    /** In an ACCEPTANCE: the address (CMC-ID) the accepter stored for the knocker
     * — so the knocker knows whether my current address still has to follow. */
    val yours: String = "",
)

@kotlinx.serialization.Serializable
data class TextPayload(
    val id: String,
    val text: String,
    val selfTimer: String = "off",
)

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
)

@kotlinx.serialization.Serializable
data class TextPayload(
    val id: String,
    val text: String,
    val selfTimer: String = "off",
)

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
)

@kotlinx.serialization.Serializable
data class TextPayload(
    val id: String,
    val text: String,
    val selfTimer: String = "off",
)

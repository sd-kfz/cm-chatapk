package org.cmchat.app.transport

import kotlinx.serialization.json.Json


object Messages {
    val json = Json { ignoreUnknownKeys = true }
}

@kotlinx.serialization.Serializable
data class KnockPayload(
    val displayName: String,
    val cmId: String,

    val withdraw: Boolean = false,

    val nonce: String = "",

    val yours: String = "",
)


@kotlinx.serialization.Serializable
data class FileOffer(

    val id: String,

    val name: String,
    val size: Long,
    val mime: String = "application/octet-stream",

    val key: String,
    val chunks: Int,
    val selfTimer: String = "off",
)

@kotlinx.serialization.Serializable
data class TextPayload(
    val id: String,
    val text: String,
    val selfTimer: String = "off",
)

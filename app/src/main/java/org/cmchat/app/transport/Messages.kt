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

/** A file offer (inside the forward-secret FILE_OFFER frame). See [FileTransfer]. */
@kotlinx.serialization.Serializable
data class FileOffer(
    /** 16 random bytes, hex: names this transfer (chunks + final receipt). */
    val id: String,
    /** The sender's file name — the receiver makes it safe before showing it. */
    val name: String,
    val size: Long,
    val mime: String = "application/octet-stream",
    /** This file's own 32-byte key (hex). Travels only inside the forward-secret frame. */
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

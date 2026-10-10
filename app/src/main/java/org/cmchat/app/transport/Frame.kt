package org.cmchat.app.transport

import org.cmchat.app.crypto.CryptoManager

/** Wire frame types. Only KNOCK is wired this phase; the rest are reserved. */
enum class FrameType(val code: Int) {
    KNOCK(1),
    KNOCK_ACCEPT(2),
    MSG(3),
    ACK(4),
    /** Retired (the custom status word was removed in v1.2); code kept reserved. */
    STATUS(5),
    ERASE_CHAT(6),
    PING(7),
    PONG(8),
    FILE_OFFER(9),
    FILE_CHUNK(10),
    FILE_DONE(11),
    /** Scout ping. Fire-and-forget: no ack, no retry, no content, no state. */
    BUZZ(12),

    /** Signed address-update: sender's new CMC-ID after rotating their onion. */
    ADDR_UPDATE(13),

    /** Cover traffic: a decoy frame, padded + sealed like any other, silently
     * discarded by the receiver. Masks WHEN real messages happen. */
    COVER(14),

    /** Forward secrecy, step 1: "send me a one-time prekey" (see SecureChannel). */
    PREKEY_REQ(15),

    /** Forward secrecy, step 2: the one-time prekey, bound to the request. */
    PREKEY_RESP(16),

    /** "Decoy chat tripped": the sender's phone may be compromised. Shown to the
     * friend as a red timestamped line; it deletes NOTHING on their side. */
    DECOY_ALERT(17),

    /** Team Clock changed: payload = canonical offset ("UTC+02:07") or empty. */
    TEAM_CLOCK(18),

    /** "Remove me": the sender deleted me; the receiver removes the sender too. */
    TERMINATE(19);

    companion object {
        fun fromCode(code: Int): FrameType? = entries.firstOrNull { it.code == code }
    }
}

data class Frame(val type: FrameType, val payload: ByteArray)

/**
 * Seals/opens a [Frame] with crypto_box. The sealed bytes are
 * nonce||ciphertext of [type byte][payload]; the caller length-prefixes them
 * on the wire (see Transport). Anything that fails to open is dropped.
 */
class FrameCodec(private val crypto: CryptoManager) {

    fun seal(frame: Frame, peerPubKeyHex: String, mySecretKeyHex: String): ByteArray {
        val inner = ByteArray(1 + frame.payload.size)
        inner[0] = frame.type.code.toByte()
        frame.payload.copyInto(inner, 1)
        return crypto.boxSeal(inner, peerPubKeyHex, mySecretKeyHex)
    }

    fun open(sealed: ByteArray, peerPubKeyHex: String, mySecretKeyHex: String): Frame? {
        val inner = crypto.boxOpen(sealed, peerPubKeyHex, mySecretKeyHex) ?: return null
        if (inner.isEmpty()) return null
        val type = FrameType.fromCode(inner[0].toInt() and 0xff) ?: return null
        return Frame(type, inner.copyOfRange(1, inner.size))
    }
}

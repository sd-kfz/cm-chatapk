package org.cmchat.app.transport

import org.cmchat.app.crypto.CryptoManager


enum class FrameType(val code: Int) {
    KNOCK(1),
    KNOCK_ACCEPT(2),
    MSG(3),
    ACK(4),

    STATUS(5),
    ERASE_CHAT(6),
    PING(7),
    PONG(8),
    FILE_OFFER(9),
    FILE_CHUNK(10),
    FILE_DONE(11),

    BUZZ(12),


    ADDR_UPDATE(13),


    COVER(14),


    PREKEY_REQ(15),


    PREKEY_RESP(16),


    DECOY_ALERT(17),


    TEAM_CLOCK(18),


    TERMINATE(19),


    NICKNAME(20);

    companion object {
        fun fromCode(code: Int): FrameType? = entries.firstOrNull { it.code == code }
    }
}

data class Frame(val type: FrameType, val payload: ByteArray)


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

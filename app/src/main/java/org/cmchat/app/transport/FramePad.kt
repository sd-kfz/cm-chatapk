package org.cmchat.app.transport

import java.security.SecureRandom


object FramePad {






    private val BUCKETS = longArrayOf(536, 2048, 8192, 16384, 32768, 49152)
    private const val HEADER = 4
    const val SEAL_HEADROOM = 128
    val MAX_PLAIN = (Transport.MAX_FRAME_BYTES - SEAL_HEADROOM).toLong()

    private val rng = SecureRandom()


    fun pad(inner: ByteArray): ByteArray {
        val need = HEADER + inner.size
        val bucket = (BUCKETS.firstOrNull { it >= need }
            ?: ((need + 535) / 536 * 536).toLong()).coerceAtMost(MAX_PLAIN).toInt()
            .coerceAtLeast(need)
        val out = ByteArray(bucket)
        val len = inner.size
        out[0] = (len ushr 24).toByte()
        out[1] = (len ushr 16).toByte()
        out[2] = (len ushr 8).toByte()
        out[3] = len.toByte()
        inner.copyInto(out, HEADER)
        if (bucket > need) {
            val pad = ByteArray(bucket - need)
            rng.nextBytes(pad)
            pad.copyInto(out, need)
        }
        return out
    }


    fun unpad(blob: ByteArray): ByteArray? {
        if (blob.size < HEADER) return null
        val len = ((blob[0].toInt() and 0xff) shl 24) or
            ((blob[1].toInt() and 0xff) shl 16) or
            ((blob[2].toInt() and 0xff) shl 8) or
            (blob[3].toInt() and 0xff)
        if (len < 0 || len > blob.size - HEADER) return null
        return blob.copyOfRange(HEADER, HEADER + len)
    }
}

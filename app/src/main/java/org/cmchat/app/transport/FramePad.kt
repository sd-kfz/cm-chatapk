package org.cmchat.app.transport

import java.security.SecureRandom

/**
 * Uniform frame padding for traffic-analysis resistance. Over Tor the content
 * and destination are already hidden; this hides the PATTERN (size) so an
 * observer can't tell a short "ok" from a long paragraph, or a knock from a
 * message.
 *
 * The real inner frame is wrapped as `[4-byte real length][inner][random pad]`
 * and padded up to a fixed SIZE BUCKET *before* it is sealed, so the encrypted
 * frame on the wire always lands in one of a few fixed sizes regardless of
 * content. The random padding is inside the encryption, so it is
 * indistinguishable from payload to anyone watching. The receiver decrypts,
 * reads the real length, and strips the padding. Applied to EVERY frame type
 * (knock, message, status, buzz, erase, address-update, cover).
 */
object FramePad {

    // Plaintext size buckets. 536 is the base (fits a short message); larger
    // payloads round up to the next bucket. Kept below MAX_FRAME_BYTES minus the
    // seal overhead so the sealed frame always fits the wire cap. The largest
    // overhead is a forward-secret frame (Fs.OVERHEAD = 80 bytes: ephemeral key,
    // prekey id, nonce, tag), so leave 128 bytes of headroom.
    private val BUCKETS = longArrayOf(536, 2048, 8192, 16384, 32768, 49152)
    private const val HEADER = 4
    const val SEAL_HEADROOM = 128
    val MAX_PLAIN = (Transport.MAX_FRAME_BYTES - SEAL_HEADROOM).toLong()

    private val rng = SecureRandom()

    /** Wrap + pad an inner frame up to its size bucket. */
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

    /** Strip padding after decryption; null if the blob is malformed. */
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

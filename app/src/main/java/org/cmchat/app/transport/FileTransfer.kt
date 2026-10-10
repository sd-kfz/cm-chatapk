package org.cmchat.app.transport

/**
 * Files travel over the SAME forward-secret channel as messages, on ONE
 * connection per file:
 *
 *  1. the usual handshake, then a FILE_OFFER frame (forward-secret) carrying the
 *     name, the size, the chunk count and a fresh random 32-byte key for this
 *     file only;
 *  2. the receiver checks the size cap (100 MB) and its free memory BEFORE
 *     allocating anything, and answers with a receipt: OK = send it;
 *  3. the sender streams the file as [CHUNK]-byte pieces, each sealed with
 *     XChaCha20-Poly1305 under the file key (nonce = 16 zero bytes + the index;
 *     the file id, index and count are bound as associated data, so a piece
 *     can't be swapped, dropped, reordered or replayed), the last piece padded
 *     so every piece on the wire has the same size;
 *  4. once every piece opened, the file is kept (RAM only, like a message) and a
 *     second receipt — over the file id — says so. Only then is it "delivered".
 */
object FileTransfer {
    /** Refused above this, on BOTH phones, before anything is buffered. */
    const val MAX_BYTES = 100L * 1024 * 1024
    const val CHUNK = 32 * 1024
    const val SEALED_CHUNK = CHUNK + 16
    const val ID_BYTES = 16
    const val KEY_BYTES = 32
    /** RAM that must stay free on top of the file itself. */
    private const val HEADROOM = 32L * 1024 * 1024

    fun chunkCount(size: Long): Int = ((size + CHUNK - 1) / CHUNK).toInt()

    fun nonce(index: Int): ByteArray = ByteArray(24).also { n ->
        for (k in 0 until 8) n[16 + k] = (index.toLong() ushr (56 - 8 * k)).toByte()
    }

    fun aad(fileId: ByteArray, index: Int, total: Int): ByteArray =
        fileId + byteArrayOf(
            (index ushr 24).toByte(), (index ushr 16).toByte(), (index ushr 8).toByte(), index.toByte(),
            (total ushr 24).toByte(), (total ushr 16).toByte(), (total ushr 8).toByte(), total.toByte(),
        )

    /** [bytes] as [CHUNK]-sized pieces (the last one shorter). */
    fun split(bytes: ByteArray): List<ByteArray> =
        (0 until chunkCount(bytes.size.toLong())).map { i ->
            bytes.copyOfRange(i * CHUNK, minOf(bytes.size, (i + 1) * CHUNK))
        }

    /** Can this phone hold [size] more bytes in RAM right now, with headroom? */
    fun fitsInMemory(size: Long, rt: Runtime = Runtime.getRuntime()): Boolean {
        val used = rt.totalMemory() - rt.freeMemory()
        return rt.maxMemory() - used >= size + HEADROOM
    }
}

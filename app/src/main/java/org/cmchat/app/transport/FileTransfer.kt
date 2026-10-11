package org.cmchat.app.transport


object FileTransfer {

    const val MAX_BYTES = 100L * 1024 * 1024
    const val CHUNK = 32 * 1024
    const val SEALED_CHUNK = CHUNK + 16
    const val ID_BYTES = 16
    const val KEY_BYTES = 32

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


    fun split(bytes: ByteArray): List<ByteArray> =
        (0 until chunkCount(bytes.size.toLong())).map { i ->
            bytes.copyOfRange(i * CHUNK, minOf(bytes.size, (i + 1) * CHUNK))
        }


    fun fitsInMemory(size: Long, rt: Runtime = Runtime.getRuntime()): Boolean {
        val used = rt.totalMemory() - rt.freeMemory()
        return rt.maxMemory() - used >= size + HEADROOM
    }
}

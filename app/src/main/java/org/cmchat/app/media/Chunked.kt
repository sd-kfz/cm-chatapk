package org.cmchat.app.media

import java.io.InputStream


class Chunked(val pieces: List<ByteArray>, val size: Long) : MetadataScrubber.ByteAccess {

    override val length: Long get() = size

    override fun at(i: Long): Int = pieces[(i / PIECE).toInt()][(i % PIECE).toInt()].toInt() and 0xFF

    override fun put(i: Long, v: Byte) { pieces[(i / PIECE).toInt()][(i % PIECE).toInt()] = v }


    fun head(n: Int): ByteArray = ByteArray(minOf(n.toLong(), size).toInt()) { at(it.toLong()).toByte() }


    fun join(): ByteArray {
        val out = ByteArray(size.toInt())
        var at = 0
        pieces.forEach { it.copyInto(out, at); at += it.size }
        return out
    }

    fun wipe() = pieces.forEach { it.fill(0) }

    companion object {
        const val PIECE = org.cmchat.app.transport.FileTransfer.CHUNK

        fun of(bytes: ByteArray): Chunked = Chunked(
            (0 until ((bytes.size + PIECE - 1) / PIECE)).map { i ->
                bytes.copyOfRange(i * PIECE, minOf(bytes.size, (i + 1) * PIECE))
            }, bytes.size.toLong())


        fun read(input: InputStream, max: Long): Chunked? {
            val pieces = ArrayList<ByteArray>()
            var total = 0L
            while (true) {
                val buf = ByteArray(PIECE)
                var n = 0
                while (n < PIECE) {
                    val r = input.read(buf, n, PIECE - n)
                    if (r < 0) break
                    n += r
                }
                if (n > 0) {
                    total += n
                    if (total > max) { pieces.forEach { it.fill(0) }; buf.fill(0); return null }
                    pieces += if (n == PIECE) buf else buf.copyOf(n).also { buf.fill(0) }
                }
                if (n < PIECE) return Chunked(pieces, total)
            }
        }
    }
}

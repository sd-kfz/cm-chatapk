package org.cmchat.app.transport

import org.cmchat.app.crypto.CryptoManager
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom


class HeldInbox(private val dir: File, private val crypto: CryptoManager) {


    class Record(
        val type: FrameType,
        val fromPub: String?,
        val body: ByteArray,
        val atMs: Long,

        val closed: Boolean,
    )

    class Entry internal constructor(internal val file: File, val record: Record)

    companion object {
        const val MAX_RECORDS = 1000
        const val MAX_BYTES = 16L * 1024 * 1024
        private const val FORMAT = 1
        private const val KEY_BYTES = 32
        private const val HDR = 12
        private const val SUFFIX = ".held"


        fun shredAll(dir: File) {
            dir.listFiles()?.forEach { f ->
                runCatching {
                    val junk = ByteArray(f.length().toInt().coerceIn(1, 1 shl 20))
                    SecureRandom().nextBytes(junk)
                    f.writeBytes(junk)
                }
                f.delete()
            }
        }
    }

    private val lock = Any()


    fun put(rec: Record, myPubHex: String): Boolean = synchronized(lock) {
        val plain = encode(rec)
        try {
            val sealed = crypto.sealedSeal(plain, myPubHex)
            val files = list()
            if (files.size >= MAX_RECORDS || files.sumOf { it.length() } + sealed.size > MAX_BYTES) return false
            if (!dir.isDirectory && !dir.mkdirs()) return false
            val n = (files.maxOfOrNull { indexOf(it) } ?: 0L) + 1
            val tmp = File(dir, "h%010d.tmp".format(n))
            val dst = File(dir, "h%010d$SUFFIX".format(n))
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(sealed)
                    out.flush()
                    out.fd.sync()
                }
                if (!tmp.renameTo(dst)) { shred(tmp); return false }
            } catch (_: Exception) {
                shred(tmp); return false
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            plain.fill(0)
        }
    }


    fun count(): Int = synchronized(lock) { list().size }


    fun readAll(myPubHex: String, mySecHex: String): List<Entry> = synchronized(lock) {
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { shred(it) }
        list().mapNotNull { f ->
            val rec = runCatching { f.readBytes() }.getOrNull()
                ?.let { crypto.sealedOpen(it, myPubHex, mySecHex) }
                ?.let { plain -> try { decode(plain) } finally { plain.fill(0) } }
            if (rec == null) { shred(f); null } else Entry(f, rec)
        }
    }


    fun remove(e: Entry) = synchronized(lock) { shred(e.file) }


    fun removeFrom(fromPub: String, myPubHex: String, mySecHex: String): Int =
        removeIf(myPubHex, mySecHex) { it.fromPub.equals(fromPub, ignoreCase = true) }


    fun removeIf(myPubHex: String, mySecHex: String, what: (Record) -> Boolean): Int = synchronized(lock) {
        val gone = readAll(myPubHex, mySecHex).filter { what(it.record) }
        gone.forEach { shred(it.file) }
        gone.size
    }


    fun wipe() = synchronized(lock) { shredAll(dir) }

    private fun list(): List<File> =
        (dir.listFiles { f -> f.isFile && f.name.startsWith("h") && f.name.endsWith(SUFFIX) } ?: emptyArray())
            .sortedBy { indexOf(it) }

    private fun indexOf(f: File): Long = f.name.removePrefix("h").substringBefore('.').toLongOrNull() ?: 0L

    private fun encode(r: Record): ByteArray {
        val key = r.fromPub?.let { crypto.hexBytes(it) } ?: ByteArray(0)
        require(key.isEmpty() || key.size == KEY_BYTES) { "bad sender key" }
        val out = ByteArray(HDR + key.size + r.body.size)
        out[0] = FORMAT.toByte()
        out[1] = r.type.code.toByte()
        out[2] = (if (r.closed) 1 else 0).toByte()
        for (i in 0 until 8) out[3 + i] = (r.atMs ushr (56 - 8 * i)).toByte()
        out[11] = key.size.toByte()
        key.copyInto(out, HDR)
        r.body.copyInto(out, HDR + key.size)
        return out
    }

    private fun decode(b: ByteArray): Record? {
        if (b.size < HDR || b[0].toInt() != FORMAT) return null
        val type = FrameType.fromCode(b[1].toInt() and 0xff) ?: return null
        var at = 0L
        for (i in 0 until 8) at = (at shl 8) or (b[3 + i].toLong() and 0xff)
        val keyLen = b[11].toInt() and 0xff
        if ((keyLen != 0 && keyLen != KEY_BYTES) || b.size < HDR + keyLen) return null
        val from = if (keyLen == 0) null
            else b.copyOfRange(HDR, HDR + keyLen).joinToString("") { "%02x".format(it) }
        return Record(type, from, b.copyOfRange(HDR + keyLen, b.size), at, closed = (b[2].toInt() and 1) == 1)
    }


    private fun shred(f: File) {
        if (!f.exists()) return
        runCatching {
            val junk = ByteArray(f.length().toInt().coerceIn(1, 1 shl 20))
            SecureRandom().nextBytes(junk)
            f.writeBytes(junk)
        }
        f.delete()
    }
}

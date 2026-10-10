package org.cmchat.app.transport

import org.cmchat.app.crypto.CryptoManager
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom

/**
 * What arrives while the vault is LOCKED (the app minimised and re-locked, or
 * swiped away with the Buzz listener running) is HELD here until the next
 * unlock — never dropped. The sender is told OK only after the record is on
 * flash (fsync), so "delivered" is never claimed for something a killed app
 * could still lose.
 *
 * Each record is its own file, sealed (crypto_box_seal) to MY identity public
 * key: only the identity secret — kept inside the PIN-protected vault, and in
 * RAM only while the engine runs — opens it. The file names are just a counter;
 * sender, type and time are inside the seal. At unlock every record is replayed
 * into the normal dispatch in arrival order, then shredded. Wipes (Cerberus,
 * Kill, decoy, Shredder) shred them too.
 *
 * Bounded: at most [MAX_RECORDS] records and [MAX_BYTES] in total. Beyond
 * that the sender is told RETRY and keeps the frame.
 */
class HeldInbox(private val dir: File, private val crypto: CryptoManager) {

    /** One held frame. [fromPub] = the sender's identity key (null for a knock). */
    class Record(
        val type: FrameType,
        val fromPub: String?,
        val body: ByteArray,
        val atMs: Long,
        /** It arrived while the app was CLOSED (swiped away; Buzz listener only). */
        val closed: Boolean,
    )

    class Entry internal constructor(internal val file: File, val record: Record)

    companion object {
        const val MAX_RECORDS = 1000
        const val MAX_BYTES = 16L * 1024 * 1024
        private const val FORMAT = 1
        private const val KEY_BYTES = 32
        private const val HDR = 12   // format, type, flags, 8-byte time, key length
        private const val SUFFIX = ".held"

        /** Shred everything held in [dir] — needs no key (wipe paths). */
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

    /** Seal [rec] to [myPubHex] and put it on flash. False = full or not written. */
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
                    out.fd.sync()                     // on flash BEFORE anyone is told OK
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

    /** How many records are waiting (no decryption). */
    fun count(): Int = synchronized(lock) { list().size }

    /**
     * Every readable record, oldest first. A record that doesn't open (another
     * identity's, or damaged) and any half-written leftover are shredded.
     */
    fun readAll(myPubHex: String, mySecHex: String): List<Entry> = synchronized(lock) {
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { shred(it) }
        list().mapNotNull { f ->
            val rec = runCatching { f.readBytes() }.getOrNull()
                ?.let { crypto.sealedOpen(it, myPubHex, mySecHex) }
                ?.let { plain -> try { decode(plain) } finally { plain.fill(0) } }
            if (rec == null) { shred(f); null } else Entry(f, rec)
        }
    }

    /** Shred one record (it was replayed). */
    fun remove(e: Entry) = synchronized(lock) { shred(e.file) }

    /** Shred every record from [fromPub] (they erased the chat / tripped their decoy). */
    fun removeFrom(fromPub: String, myPubHex: String, mySecHex: String): Int =
        removeIf(myPubHex, mySecHex) { it.fromPub.equals(fromPub, ignoreCase = true) }

    /** Shred every record matching [what]; returns how many. */
    fun removeIf(myPubHex: String, mySecHex: String, what: (Record) -> Boolean): Int = synchronized(lock) {
        val gone = readAll(myPubHex, mySecHex).filter { what(it.record) }
        gone.forEach { shred(it.file) }
        gone.size
    }

    /** Shred everything held (wipe paths). */
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

    /** Overwrite, then delete (best-effort on flash, like every other wipe here). */
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

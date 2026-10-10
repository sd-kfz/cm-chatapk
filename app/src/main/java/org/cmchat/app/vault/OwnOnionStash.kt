package org.cmchat.app.vault

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.cmchat.app.crypto.CryptoManager
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom

/**
 * My onion key + address when they were made while the vault could NOT be
 * written (locked: the decoy's rotation, a publish that finished after the app
 * re-locked, a slow Tor outliving the decoy's late save). Without this the next
 * start would publish a DIFFERENT address — and friends who were told the new
 * one could never reach me again.
 *
 * Sealed (crypto_box_seal) to my identity public key: only the identity secret,
 * kept inside the PIN-protected vault, opens it. Applied to the vault at the
 * next unlock, then shredded. One small file, written atomically (fsync).
 */
object OwnOnionStash {

    @Serializable
    data class Onion(val key: String, val onion: String)

    private const val NAME = "own-onion.bin"
    private val json = Json { ignoreUnknownKeys = true }

    /** Keep [key]/[onion] for the next unlock. False = not written. */
    fun put(dir: File, crypto: CryptoManager, myPubHex: String, key: String, onion: String): Boolean {
        val plain = json.encodeToString(Onion.serializer(), Onion(key, onion)).toByteArray(Charsets.UTF_8)
        val tmp = File(dir, "$NAME.tmp")
        return try {
            val sealed = crypto.sealedSeal(plain, myPubHex)
            FileOutputStream(tmp).use { out -> out.write(sealed); out.flush(); out.fd.sync() }
            tmp.renameTo(File(dir, NAME)) || run { shred(tmp); false }
        } catch (_: Exception) {
            shred(tmp); false
        } finally {
            plain.fill(0)
        }
    }

    /** What was kept, if anything (opened with my identity key). */
    fun read(dir: File, crypto: CryptoManager, myPubHex: String, mySecHex: String): Onion? {
        val f = File(dir, NAME)
        if (!f.exists()) return null
        val plain = runCatching { f.readBytes() }.getOrNull()
            ?.let { crypto.sealedOpen(it, myPubHex, mySecHex) } ?: return null
        return try {
            runCatching { json.decodeFromString(Onion.serializer(), String(plain, Charsets.UTF_8)) }.getOrNull()
        } finally {
            plain.fill(0)
        }
    }

    /** The vault has it now: shred the stash. */
    fun clear(dir: File) {
        shred(File(dir, "$NAME.tmp"))
        shred(File(dir, NAME))
    }

    private fun shred(f: File) {
        if (!f.exists()) return
        runCatching {
            val junk = ByteArray(f.length().toInt().coerceIn(1, 1 shl 16))
            SecureRandom().nextBytes(junk)
            f.writeBytes(junk)
        }
        f.delete()
    }
}

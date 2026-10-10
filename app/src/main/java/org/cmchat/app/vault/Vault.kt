package org.cmchat.app.vault

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom

/**
 * The vault on disk: ONE file in app-internal storage,
 *
 *     vault2.dat = salt (16) || nonce (24) || secretbox(JSON)
 *
 * The key is Argon2id(PIN, salt); [VaultManager] derives it once per unlock and
 * keeps it in RAM for the session. Salt and ciphertext live in the same file,
 * so they can never get out of step, and every write replaces the file
 * ATOMICALLY (temp file → fsync → rename), so a crash or a dead battery
 * mid-save can never leave a half-written vault.
 *
 * Older builds kept the salt and the ciphertext in two files (salt.dat +
 * vault.dat); those are read as-is and shredded after the first new write.
 *
 * This class only moves bytes — it never sees the PIN or a key.
 */
class Vault(private val dir: File, private val wrapper: VaultWrapper = VaultWrapper.NONE) {

    private val file = File(dir, "vault2.dat")
    private val tmp = File(dir, "vault2.tmp")
    /** A PIN change in progress: the re-encrypted vault, checked before it goes live. */
    private val next = File(dir, "vault2.new")
    /** The vault as it was before a PIN change — kept until the change is confirmed. */
    private val prev = File(dir, "vault2.old")
    private val legacyVault = File(dir, "vault.dat")
    private val legacySalt = File(dir, "salt.dat")

    /** The stored salt + sealed bytes ([sealed] is already unwrapped). [wasWrapped]
     * is false for a legacy (build82 / two-file) vault that had no hardware layer. */
    class Blob(val salt: ByteArray, val sealed: ByteArray, val wasWrapped: Boolean = false)

    /** True when a real hardware wrapper is in use (drives one-time migration). */
    fun wrapActive(): Boolean = wrapper.active

    /** Test hook: simulate the process dying right after this PIN-change step. */
    internal var crashAfterStep: Int? = null

    fun exists(): Boolean =
        file.exists() || prev.exists() || (legacyVault.exists() && legacySalt.exists())

    /** The current vault, or null when there is none. */
    fun read(): Blob? = readFile(file) ?: readLegacy()

    /** The copy kept by an interrupted PIN change (normally null). */
    fun readPrevious(): Blob? = readFile(prev)

    /** Atomically replace the vault with [sealed] under [salt]. */
    fun write(salt: ByteArray, sealed: ByteArray) {
        require(salt.size == SALT_BYTES) { "bad salt length" }
        dir.mkdirs()
        writeSynced(tmp, salt, sealed)
        // rename(2) swaps the file in one step: a reader sees the old or the new, never half.
        if (!tmp.renameTo(file)) throw IOException("vault replace failed")
        shredLegacy()
    }

    /**
     * Swap in a vault re-encrypted under a NEW PIN, crash-safe:
     *  1. write it to vault2.new and fsync,
     *  2. read it back — [verify] must confirm the new PIN's key opens it,
     *  3. keep a copy of the current vault as vault2.old (fsync),
     *  4. rename vault2.new over vault2.dat (one atomic step),
     *  5. [verify] vault2.dat once more, then shred vault2.old.
     * Dying at any point leaves the old vault, or the new one plus the kept old
     * copy (which [VaultManager.unlock] resolves) — never a vault no PIN opens.
     * Returns false (old vault untouched and current) if a check fails.
     */
    fun replaceForPinChange(salt: ByteArray, sealed: ByteArray, verify: (Blob) -> Boolean): Boolean {
        require(salt.size == SALT_BYTES) { "bad salt length" }
        val current = read() ?: return false
        dir.mkdirs()
        writeSynced(next, salt, sealed)
        step(1)
        val written = readFile(next)
        if (written == null || !verify(written)) { shred(next); return false }
        step(2)
        writeSynced(prev, current.salt, current.sealed)
        step(3)
        if (!next.renameTo(file)) { shred(next); shred(prev); return false }
        step(4)
        val live = readFile(file)
        if (live == null || !verify(live)) {
            // The new copy didn't survive: put the old vault back (atomic) and report failure.
            if (!prev.renameTo(file)) throw IOException("vault restore failed")
            return false
        }
        shred(prev)
        shredLegacy()
        return true
    }

    /** An interrupted PIN change is void (the current vault opened): drop its leftovers. */
    fun discardLeftovers() {
        shred(prev)
        shred(next)
        shred(tmp)
        // An old-format pair is stale once the new file is the live vault.
        if (file.exists()) shredLegacy()
    }

    /** An interrupted PIN change never finished for the user: the kept copy goes back live. */
    fun restorePrevious() {
        if (!prev.exists()) return
        if (!prev.renameTo(file)) throw IOException("vault restore failed")
        shred(next)
        shredLegacy()
    }

    /** Best-effort wipe: overwrite then delete. Flash wear-levelling means this is
     * not a forensic guarantee, only that ciphertext and salt leave the normal
     * filesystem view. */
    fun wipe() {
        listOf(file, tmp, next, prev, legacyVault, legacySalt).forEach { shred(it) }
    }

    private fun step(n: Int) {
        if (crashAfterStep == n) throw IllegalStateException("simulated crash after step $n")
    }

    private fun readFile(f: File): Blob? {
        if (!f.exists()) return null
        val all = runCatching { f.readBytes() }.getOrNull() ?: return null
        if (all.size <= SALT_BYTES) return null
        val salt = all.copyOfRange(0, SALT_BYTES)
        val stored = all.copyOfRange(SALT_BYTES, all.size)
        // A hardware-wrapped vault is unwrapped here (needs this phone's secure
        // element); null means the key is unavailable or the file was tampered —
        // treat it as unreadable, never as plaintext. A legacy vault has no marker
        // and is returned as-is (and re-wrapped on the next unlock).
        if (wrapper.isWrapped(stored)) {
            val sealed = wrapper.unwrap(stored) ?: return null
            return Blob(salt, sealed, wasWrapped = true)
        }
        return Blob(salt, stored, wasWrapped = false)
    }

    private fun readLegacy(): Blob? {
        if (!legacyVault.exists() || !legacySalt.exists()) return null
        val salt = runCatching { legacySalt.readBytes() }.getOrNull() ?: return null
        if (salt.size != SALT_BYTES) return null
        return Blob(salt, runCatching { legacyVault.readBytes() }.getOrNull() ?: return null, wasWrapped = false)
    }

    private fun writeSynced(f: File, salt: ByteArray, sealed: ByteArray) {
        // The device-bound wrap is applied here so EVERY write (the live vault and
        // the crash-safe PIN-change staging/backup files) is hardware-bound alike.
        val onDisk = wrapper.wrap(sealed)
        FileOutputStream(f).use { out ->
            out.write(salt)
            out.write(onDisk)
            out.flush()
            runCatching { out.fd.sync() }       // on flash before anything points at it
        }
    }

    private fun shredLegacy() {
        if (legacyVault.exists() || legacySalt.exists()) {
            shred(legacyVault)
            shred(legacySalt)
        }
    }

    private fun shred(f: File) {
        if (!f.exists()) return
        runCatching {
            val len = f.length().toInt().coerceIn(1, 1 shl 20)
            val junk = ByteArray(len)
            SecureRandom().nextBytes(junk)
            f.writeBytes(junk)
        }
        f.delete()
    }

    companion object { const val SALT_BYTES = 16 }
}

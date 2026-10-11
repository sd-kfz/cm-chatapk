package org.cmchat.app.vault

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom


class Vault(private val dir: File, private val wrapper: VaultWrapper = VaultWrapper.NONE) {

    private val file = File(dir, "vault2.dat")
    private val tmp = File(dir, "vault2.tmp")

    private val next = File(dir, "vault2.new")

    private val prev = File(dir, "vault2.old")
    private val legacyVault = File(dir, "vault.dat")
    private val legacySalt = File(dir, "salt.dat")


    class Blob(val salt: ByteArray, val sealed: ByteArray, val wasWrapped: Boolean = false)


    fun wrapActive(): Boolean = wrapper.active


    internal var crashAfterStep: Int? = null

    fun exists(): Boolean =
        file.exists() || prev.exists() || (legacyVault.exists() && legacySalt.exists())


    fun read(): Blob? = readFile(file) ?: readLegacy()


    fun readPrevious(): Blob? = readFile(prev)


    fun write(salt: ByteArray, sealed: ByteArray) {
        require(salt.size == SALT_BYTES) { "bad salt length" }
        dir.mkdirs()
        writeSynced(tmp, salt, sealed)

        if (!tmp.renameTo(file)) throw IOException("vault replace failed")
        shredLegacy()
    }


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

            if (!prev.renameTo(file)) throw IOException("vault restore failed")
            return false
        }
        shred(prev)
        shredLegacy()
        return true
    }


    fun discardLeftovers() {
        shred(prev)
        shred(next)
        shred(tmp)

        if (file.exists()) shredLegacy()
    }


    fun restorePrevious() {
        if (!prev.exists()) return
        if (!prev.renameTo(file)) throw IOException("vault restore failed")
        shred(next)
        shredLegacy()
    }


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


        val onDisk = wrapper.wrap(sealed)
        FileOutputStream(f).use { out ->
            out.write(salt)
            out.write(onDisk)
            out.flush()
            runCatching { out.fd.sync() }
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

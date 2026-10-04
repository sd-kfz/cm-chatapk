package org.cmchat.app.vault

import kotlinx.serialization.json.Json
import org.cmchat.app.crypto.CryptoManager
import java.io.File
import java.security.SecureRandom

/**
 * One encrypted file in app-internal storage holding the [VaultData] JSON,
 * plus a sibling file with the Argon2id salt. Only these two files touch
 * disk; messages/files/statuses live in RAM only.
 */
class Vault(private val crypto: CryptoManager, private val dir: File) {

    private val vaultFile = File(dir, "vault.dat")
    private val saltFile = File(dir, "salt.dat")
    private val json = Json { ignoreUnknownKeys = true }

    fun exists(): Boolean = vaultFile.exists() && saltFile.exists()

    fun create(pin: String, data: VaultData) {
        dir.mkdirs()
        val salt = crypto.randomSalt()
        saltFile.writeBytes(salt)
        writeEncrypted(pin, salt, data)
    }

    fun save(pin: String, data: VaultData) {
        val salt = saltFile.readBytes()
        writeEncrypted(pin, salt, data)
    }

    fun load(pin: String): VaultData? {
        if (!exists()) return null
        val salt = saltFile.readBytes()
        val key = crypto.deriveKey(pin, salt)
        val plain = crypto.open(vaultFile.readBytes(), key)
        key.fill(0)                               // zero the Argon2 key ASAP
        if (plain == null) return null
        return try {
            json.decodeFromString(VaultData.serializer(), String(plain, Charsets.UTF_8))
        } finally {
            plain.fill(0)                          // zero the decrypted plaintext
        }
    }

    /**
     * Re-encrypt the vault under a NEW passcode. Loads the data with [oldPin],
     * writes a FRESH salt, and re-seals the SAME data under [newPin]. Returns
     * false (and changes nothing) if [oldPin] is wrong. On success the old
     * passcode can no longer open the vault — a new salt + new Argon2id key.
     */
    fun changePin(oldPin: String, newPin: String): Boolean {
        val data = load(oldPin) ?: return false
        val salt = crypto.randomSalt()
        saltFile.writeBytes(salt)
        writeEncrypted(newPin, salt, data)
        return true
    }

    /** Best-effort wipe: overwrite then delete. Flash wear-levelling means
     * this is not a forensic guarantee, only that the plaintext key material
     * and ciphertext are cleared from the normal filesystem view. */
    fun wipe() {
        overwriteAndDelete(vaultFile)
        overwriteAndDelete(saltFile)
    }

    private fun writeEncrypted(pin: String, salt: ByteArray, data: VaultData) {
        val key = crypto.deriveKey(pin, salt)
        val plain = json.encodeToString(VaultData.serializer(), data).toByteArray(Charsets.UTF_8)
        try {
            vaultFile.writeBytes(crypto.seal(plain, key))
        } finally {
            // Zero key material + the serialized plaintext as soon as we're done.
            key.fill(0)
            plain.fill(0)
        }
    }

    private fun overwriteAndDelete(f: File) {
        if (!f.exists()) return
        runCatching {
            val len = f.length().toInt().coerceAtLeast(1)
            val junk = ByteArray(len)
            SecureRandom().nextBytes(junk)
            f.writeBytes(junk)
        }
        f.delete()
    }
}

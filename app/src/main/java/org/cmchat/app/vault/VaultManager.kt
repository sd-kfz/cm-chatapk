package org.cmchat.app.vault

import org.cmchat.app.crypto.CryptoManager
import java.io.File

sealed interface UnlockResult {
    data class Success(val data: VaultData) : UnlockResult
    object WrongPin : UnlockResult
    /** The real PIN was entered in exact reverse (duress). Vault already wiped. */
    object Duress : UnlockResult
}

/**
 * Owns the vault lifecycle: first-run creation, unlock (incl. duress
 * detection), and wipe. The duress check needs no stored PIN: on a failed
 * unlock we try the reversed input; if THAT opens the vault, the user typed
 * their real PIN backwards, so we wipe and report Duress. Palindrome PINs are
 * rejected at creation precisely so reverse != forward.
 */
class VaultManager(val crypto: CryptoManager, dir: File) {

    private val vault = Vault(crypto, dir)

    fun firstRunNeeded(): Boolean = !vault.exists()

    fun createVault(pin: String, faceName: String): VaultData {
        val (pk, sk) = crypto.newIdentityKeypair()
        val face = Face(
            id = crypto.randomHex(8),
            name = faceName.ifBlank { "Wanderer" },
            publicKey = pk,
            secretKey = sk,
        )
        val data = VaultData(faces = listOf(face))
        vault.create(pin, data)
        return data
    }

    fun unlock(pin: String): UnlockResult {
        if (!vault.exists()) return UnlockResult.WrongPin
        vault.load(pin)?.let { return UnlockResult.Success(it) }
        val reversed = pin.reversed()
        if (reversed != pin && vault.load(reversed) != null) {
            vault.wipe()
            return UnlockResult.Duress
        }
        return UnlockResult.WrongPin
    }

    /** Verify the passcode with NO side effects (no duress wipe). For PIN gates. */
    fun verify(pin: String): Boolean = vault.exists() && vault.load(pin) != null

    fun save(pin: String, data: VaultData) = vault.save(pin, data)

    /**
     * Change the vault passcode: verify [oldPin] by decrypting, then re-encrypt
     * the data under [newPin] with a fresh salt. Returns false (no change) if the
     * new passcode is invalid ([isValidNewPin]) or the old one is wrong. After a
     * success only [newPin] opens the vault; no data is lost. Runs Argon2id twice
     * (load + re-seal), so callers MUST invoke this off the main thread.
     */
    fun changePin(oldPin: String, newPin: String): Boolean {
        if (!isValidNewPin(newPin)) return false
        return vault.changePin(oldPin, newPin)
    }

    fun wipe() = vault.wipe()

    companion object {
        /**
         * Accepts any passcode of 6..128 characters (digits, letters and/or the
         * keyboard symbols) that is not a palindrome. Argon2id (cryptoPwHash)
         * hashes the raw bytes, so any length/charset derives a valid key; the
         * passcode is treated as OPAQUE BYTES only and never interpreted. The
         * palindrome rejection keeps the reversed-input duress check unambiguous.
         */
        fun isValidNewPin(pin: String): Boolean =
            pin.length in 6..128 && pin != pin.reversed()
    }
}

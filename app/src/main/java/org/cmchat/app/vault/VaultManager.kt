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

    fun wipe() = vault.wipe()

    companion object {
        /**
         * Accepts either a 6-digit numeric PIN or an alphanumeric passcode of 6+
         * chars that includes at least one letter. Argon2id (cryptoPwHash) hashes
         * the raw bytes, so any length/charset derives a valid key. A palindrome
         * is always rejected so the reversed-input duress check stays unambiguous.
         */
        fun isValidNewPin(pin: String): Boolean {
            if (pin == pin.reversed()) return false
            val numeric6 = pin.length == 6 && pin.all { it.isDigit() }
            val alphanumeric = pin.length >= 6 &&
                pin.all { it.isLetterOrDigit() } && pin.any { it.isLetter() }
            return numeric6 || alphanumeric
        }
    }
}

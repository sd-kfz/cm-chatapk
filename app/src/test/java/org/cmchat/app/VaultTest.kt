package org.cmchat.app

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.cmchat.app.crypto.CryptoManager
import org.cmchat.app.vault.UnlockResult
import org.cmchat.app.vault.VaultManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Runs the real crypto on the host JVM via lazysodium-java (same
 * CryptoManager/Vault code that runs on-device via lazysodium-android).
 */
class VaultTest {

    private fun newManager(): Pair<VaultManager, File> {
        val ls = LazySodiumJava(SodiumJava())
        val crypto = CryptoManager(ls)
        val dir = Files.createTempDirectory("cmvault").toFile()
        return VaultManager(crypto, dir) to dir
    }

    @Test
    fun vault_round_trip_and_wrong_pin() {
        val (m, _) = newManager()
        m.createVault(pin = "135790", faceName = "Wanderer")

        val ok = m.unlock("135790")
        assertTrue(ok is UnlockResult.Success)
        assertEquals("Wanderer", (ok as UnlockResult.Success).data.faces.single().name)

        // A random wrong PIN (not the reverse) must fail to decrypt.
        assertEquals(UnlockResult.WrongPin, m.unlock("246801"))
    }

    @Test
    fun reverse_pin_is_detected_as_duress_and_wipes() {
        val (m, _) = newManager()
        m.createVault(pin = "135790", faceName = "Wanderer")

        assertEquals(UnlockResult.Duress, m.unlock("097531"))
        // After a duress wipe the vault is gone -> first-run again.
        assertTrue(m.firstRunNeeded())
    }

    @Test
    fun palindrome_pin_rejected_others_accepted() {
        assertFalse(VaultManager.isValidNewPin("123321")) // palindrome
        assertFalse(VaultManager.isValidNewPin("12345"))  // too short (numeric)
        assertFalse(VaultManager.isValidNewPin("ababa"))  // too short (alnum)
        assertFalse(VaultManager.isValidNewPin("pa ss1")) // space not alphanumeric
        assertTrue(VaultManager.isValidNewPin("135790"))   // 6-digit PIN
        assertTrue(VaultManager.isValidNewPin("12a456"))   // alphanumeric passcode
        assertTrue(VaultManager.isValidNewPin("Secret1"))  // longer alphanumeric
        assertFalse(VaultManager.isValidNewPin("aa1aa"))   // 5 chars, too short
    }

    @Test
    fun crypto_secretbox_open_fails_on_tampered_blob() {
        val ls = LazySodiumJava(SodiumJava())
        val crypto = CryptoManager(ls)
        val salt = crypto.randomSalt()
        val key = crypto.deriveKey("135790", salt)
        val blob = crypto.seal("secret".toByteArray(), key)
        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(crypto.open(tampered, key))
        assertEquals("secret", String(crypto.open(blob, key)!!))
    }
}

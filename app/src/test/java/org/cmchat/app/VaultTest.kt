package org.cmchat.app

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.cmchat.app.crypto.CryptoManager
import org.cmchat.app.vault.UnlockResult
import org.cmchat.app.vault.VaultManager
import org.cmchat.app.vault.VaultManager.Companion.Strength
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
        // v1.2 policy: 4..56 chars, any mix, never a palindrome (Shredder = reversed).
        assertFalse(VaultManager.isValidNewPin("123"))      // too short (min 4)
        assertTrue(VaultManager.isValidNewPin("1234"))      // 4 is allowed (hint nudges to 8+)
        assertTrue(VaultManager.isValidNewPin("12345"))
        assertFalse(VaultManager.isValidNewPin("1221"))     // palindrome
        assertFalse(VaultManager.isValidNewPin("123321"))   // palindrome
        assertFalse(VaultManager.isValidNewPin("ababa"))    // palindrome
        assertTrue(VaultManager.isValidNewPin("135790"))
        assertTrue(VaultManager.isValidNewPin("12a456"))    // alphanumeric
        assertTrue(VaultManager.isValidNewPin("p@ss#1"))    // symbols
        assertTrue(VaultManager.isValidNewPin("a".repeat(55) + "b"))   // 56 = max
        assertFalse(VaultManager.isValidNewPin("a".repeat(56) + "b"))  // 57 = too long
    }

    @Test
    fun strength_hint_encourages_eight_plus_but_never_blocks() {
        assertEquals(Strength.WEAK, VaultManager.strength("1234"))
        assertEquals(Strength.WEAK, VaultManager.strength("135790"))
        assertEquals(Strength.FAIR, VaultManager.strength("Ab1!xy"))        // short but varied
        assertEquals(Strength.FAIR, VaultManager.strength("13579024"))      // 8 digits
        assertEquals(Strength.GOOD, VaultManager.strength("Abc12!xyz"))     // 9, 4 kinds
        assertEquals(Strength.STRONG, VaultManager.strength("correct horse 9"))
        // Weak is only a hint: a weak-but-valid passcode is still accepted.
        assertTrue(VaultManager.isValidNewPin("1234"))
    }

    @Test
    fun change_pin_reencrypts_and_old_pin_stops_working() {
        val (m, _) = newManager()
        m.createVault(pin = "135790", faceName = "Wanderer")

        // Wrong current passcode -> no change; the old one still opens the vault.
        assertFalse(m.changePin("000000", "246802"))
        assertTrue(m.unlock("135790") is UnlockResult.Success)

        // Correct change: old passcode stops working, new one opens the SAME data.
        assertTrue(m.changePin("135790", "246802"))
        assertEquals(UnlockResult.WrongPin, m.unlock("135790"))
        val ok = m.unlock("246802")
        assertTrue(ok is UnlockResult.Success)
        assertEquals("Wanderer", (ok as UnlockResult.Success).data.faces.single().name)

        // An invalid new passcode (a palindrome) is rejected and changes nothing.
        assertFalse(m.changePin("246802", "12321"))
        assertTrue(m.unlock("246802") is UnlockResult.Success)
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

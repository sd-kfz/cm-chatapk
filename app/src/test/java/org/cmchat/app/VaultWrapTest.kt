package org.cmchat.app

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.cmchat.app.crypto.CryptoManager
import org.cmchat.app.vault.UnlockResult
import org.cmchat.app.vault.VaultManager
import org.cmchat.app.vault.VaultWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * H3 — the vault is wrapped with a device-bound key, so a COPIED vault is
 * useless off the phone even with a weak PIN. On the JVM there is no
 * AndroidKeyStore, so a software AES-GCM key stands in for the secure element
 * (same on-disk format as [org.cmchat.app.vault.KeystoreWrap]); these tests
 * prove the Vault/VaultManager wiring, the "wrong device = can't open" property
 * and the one-time migration of a legacy (build82) vault.
 */
class VaultWrapTest {

    private val crypto = CryptoManager(LazySodiumJava(SodiumJava()))

    /** Stand-in for the phone's non-exportable Keystore key. */
    private class DeviceKey(private val bytes: ByteArray) : VaultWrapper {
        private val key = SecretKeySpec(bytes, "AES")
        override val active = true
        override fun wrap(plain: ByteArray): ByteArray {
            val iv = ByteArray(VaultWrapper.IV_LEN).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            return VaultWrapper.MAGIC + iv + c.doFinal(plain)
        }
        override fun unwrap(blob: ByteArray): ByteArray? = runCatching {
            if (!isWrapped(blob)) return null
            val off = VaultWrapper.MAGIC.size
            val iv = blob.copyOfRange(off, off + VaultWrapper.IV_LEN)
            val ct = blob.copyOfRange(off + VaultWrapper.IV_LEN, blob.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            c.doFinal(ct)
        }.getOrNull()
    }

    private fun key(b: Byte) = ByteArray(32) { b }

    private fun newDir() = Files.createTempDirectory("vaultwrap").toFile()
    private fun vaultFile(dir: File) = File(dir, "vault/vault2.dat")
    private fun storedAfterSalt(dir: File): ByteArray {
        val all = vaultFile(dir).readBytes()
        return all.copyOfRange(16, all.size)   // salt is 16 bytes
    }

    @Test
    fun a_wrapped_vault_opens_only_with_the_same_device_key() {
        val dir = newDir()
        try {
            VaultManager(crypto, File(dir, "vault"), DeviceKey(key(1))).createVault("1234", "me")
            // On disk the sealed region is hardware-wrapped (carries the marker).
            assertTrue("vault is wrapped on disk", VaultWrapper.NONE.let {
                val s = storedAfterSalt(dir)
                s.size >= 8 && VaultWrapper.MAGIC.indices.all { i -> s[i] == VaultWrapper.MAGIC[i] }
            })
            // Same device key (a re-launch on the SAME phone) opens it.
            assertTrue(VaultManager(crypto, File(dir, "vault"), DeviceKey(key(1))).unlock("1234")
                is UnlockResult.Success)
            // A different device key (the file copied to another phone / a PC) cannot —
            // and brute-forcing the short PIN there is futile without the key.
            val other = VaultManager(crypto, File(dir, "vault"), DeviceKey(key(2)))
            assertEquals(UnlockResult.WrongPin, other.unlock("1234"))
            for (guess in listOf("0000", "1234", "1111", "9999", "4321")) {
                assertEquals(UnlockResult.WrongPin, other.unlock(guess))
            }
            // No device key at all (plain copy) is just as dead.
            assertEquals(UnlockResult.WrongPin,
                VaultManager(crypto, File(dir, "vault")).unlock("1234"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun a_legacy_build82_vault_is_wrapped_on_first_unlock() {
        val dir = newDir()
        try {
            // A build82 vault: created with no wrapper, so plaintext-sealed on disk.
            VaultManager(crypto, File(dir, "vault")).createVault("7788", "old")
            val before = storedAfterSalt(dir)
            assertFalse("legacy vault has no wrap marker",
                before.size >= 8 && VaultWrapper.MAGIC.indices.all { before[it] == VaultWrapper.MAGIC[it] })

            // First unlock after the update, on the device (its key present): opens,
            // then silently re-saves WITH the wrap.
            val dev = DeviceKey(key(5))
            assertTrue(VaultManager(crypto, File(dir, "vault"), dev).unlock("7788") is UnlockResult.Success)
            val after = storedAfterSalt(dir)
            assertTrue("migrated to wrapped",
                VaultWrapper.MAGIC.indices.all { after[it] == VaultWrapper.MAGIC[it] })

            // Now it needs that device key, like any wrapped vault.
            assertTrue(VaultManager(crypto, File(dir, "vault"), DeviceKey(key(5))).unlock("7788")
                is UnlockResult.Success)
            assertEquals(UnlockResult.WrongPin,
                VaultManager(crypto, File(dir, "vault"), DeviceKey(key(6))).unlock("7788"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun a_tampered_wrap_is_refused_never_opened_as_plaintext() {
        val dir = newDir()
        try {
            VaultManager(crypto, File(dir, "vault"), DeviceKey(key(9))).createVault("2468", "me")
            // Flip a byte inside the wrapped ciphertext.
            val f = vaultFile(dir)
            val b = f.readBytes()
            b[b.size - 1] = (b[b.size - 1].toInt() xor 0x01).toByte()
            f.writeBytes(b)
            // GCM rejects it → the vault reads as unopenable, not as plaintext.
            assertEquals(UnlockResult.WrongPin,
                VaultManager(crypto, File(dir, "vault"), DeviceKey(key(9))).unlock("2468"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pin_change_keeps_the_wrap() {
        val dir = newDir()
        try {
            val a = VaultManager(crypto, File(dir, "vault"), DeviceKey(key(3)))
            a.createVault("1122", "me")
            assertTrue(a.changePin("1122", "3344"))
            val s = storedAfterSalt(dir)
            assertTrue("still wrapped after PIN change",
                VaultWrapper.MAGIC.indices.all { s[it] == VaultWrapper.MAGIC[it] })
            // Old PIN is dead; new PIN + same device key opens; wrong device key never does.
            assertEquals(UnlockResult.WrongPin,
                VaultManager(crypto, File(dir, "vault"), DeviceKey(key(3))).unlock("1122"))
            assertTrue(VaultManager(crypto, File(dir, "vault"), DeviceKey(key(3))).unlock("3344")
                is UnlockResult.Success)
            assertEquals(UnlockResult.WrongPin,
                VaultManager(crypto, File(dir, "vault"), DeviceKey(key(4))).unlock("3344"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun the_marker_does_not_collide_with_a_legacy_nonce() {
        // Detection is by an 8-byte marker; a legacy vault must never be mistaken
        // for wrapped (its sealed region is a 24-byte random nonce + box).
        assertFalse(VaultWrapper.NONE.isWrapped(ByteArray(200)))
        val wrapped = DeviceKey(key(7)).wrap("hello".toByteArray())
        assertTrue(DeviceKey(key(7)).isWrapped(wrapped))
    }
}

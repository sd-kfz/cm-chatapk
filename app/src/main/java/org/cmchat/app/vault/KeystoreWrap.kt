package org.cmchat.app.vault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware binding for the vault. Wraps the Argon2 ciphertext with an
 * AES-256-GCM key kept in the AndroidKeyStore — inside the phone's secure
 * element (StrongBox when present, otherwise the TEE). The key is marked
 * non-exportable, so it NEVER leaves the device: a vault file copied to a PC
 * can't be brute-forced there, however short the PIN, because the attacker
 * doesn't have this key and can't extract it. On API 28+ the key also requires
 * the device to be unlocked, so the vault can't even be unwrapped on-device
 * while the screen is locked.
 *
 * Deliberate trade-off for an anti-seizure tool: if the app is uninstalled, its
 * data cleared, or the phone factory-reset, this key is destroyed and the vault
 * can never be opened again. A copied or orphaned vault is dead weight.
 */
class KeystoreWrap : VaultWrapper {
    override val active = true

    override fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv                                   // 12 bytes, generated in hardware
        require(iv.size == VaultWrapper.IV_LEN) { "unexpected GCM IV length" }
        return VaultWrapper.MAGIC + iv + cipher.doFinal(plain)
    }

    override fun unwrap(blob: ByteArray): ByteArray? = runCatching {
        if (!isWrapped(blob)) return null
        val off = VaultWrapper.MAGIC.size
        val iv = blob.copyOfRange(off, off + VaultWrapper.IV_LEN)
        val ct = blob.copyOfRange(off + VaultWrapper.IV_LEN, blob.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        cipher.doFinal(ct)
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        return try {
            kg.init(spec(strongBox = Build.VERSION.SDK_INT >= 28)); kg.generateKey()
        } catch (_: Exception) {
            // No StrongBox on this phone (or the spec was rejected): fall back to the TEE.
            kg.init(spec(strongBox = false)); kg.generateKey()
        }
    }

    private fun spec(strongBox: Boolean): KeyGenParameterSpec {
        val b = KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (Build.VERSION.SDK_INT >= 28) {
            b.setUnlockedDeviceRequired(true)      // usable only while the phone is unlocked
            if (strongBox) b.setIsStrongBoxBacked(true)
        }
        return b.build()
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "cmc_vault_wrap"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}

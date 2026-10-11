package org.cmchat.app.vault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec


class KeystoreWrap : VaultWrapper {
    override val active = true

    override fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
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
            b.setUnlockedDeviceRequired(true)
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

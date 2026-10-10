package org.cmchat.app.vault

/**
 * A second, device-bound layer around the vault's ciphertext. The real
 * implementation ([KeystoreWrap]) encrypts with an AES-256-GCM key that lives
 * in this phone's secure hardware and never leaves it, so a COPIED vault file
 * is useless off-device even when the PIN is short. On disk the sealed bytes
 * become
 *
 *     MAGIC(8) || iv(12) || AES-GCM(the Argon2 secretbox)
 *
 * This sits OUTSIDE the Argon2 layer: opening the vault needs BOTH the PIN (for
 * Argon2) AND this phone's secure element (to unwrap). [NONE] is the identity —
 * used by unit tests, and by which any legacy (build82) vault reads back as-is
 * and is re-wrapped on first unlock.
 */
interface VaultWrapper {
    /** Wrap plaintext sealed bytes for disk. */
    fun wrap(plain: ByteArray): ByteArray

    /** Unwrap, or null if these bytes can't be opened here (wrong device / tampered / key gone). */
    fun unwrap(blob: ByteArray): ByteArray?

    /** Does [blob] carry our marker (a hardware-wrapped vault, vs a legacy one)? */
    fun isWrapped(blob: ByteArray): Boolean =
        blob.size >= MAGIC.size + IV_LEN + GCM_TAG &&
            MAGIC.indices.all { blob[it] == MAGIC[it] }

    /** True only for a real hardware wrapper — drives the one-time migration of old vaults. */
    val active: Boolean

    companion object {
        /** "CMKWRAP1". Marks a hardware-wrapped vault; never change it. */
        val MAGIC = byteArrayOf(0x43, 0x4D, 0x4B, 0x57, 0x52, 0x41, 0x50, 0x31)
        const val IV_LEN = 12
        const val GCM_TAG = 16

        /** Identity wrapper: no hardware binding (JVM tests, and reading legacy vaults). */
        val NONE: VaultWrapper = object : VaultWrapper {
            override fun wrap(plain: ByteArray) = plain
            override fun unwrap(blob: ByteArray) = blob
            override fun isWrapped(blob: ByteArray) = false
            override val active = false
        }
    }
}

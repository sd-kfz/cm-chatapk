package org.cmchat.app.crypto

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.PwHash
import com.goterl.lazysodium.interfaces.SecretBox
import com.sun.jna.NativeLong

/**
 * Thin wrapper over libsodium (via lazysodium). All primitives are the
 * library's own; nothing here is hand-rolled.
 *
 *  - PIN -> 32-byte key: Argon2id (crypto_pwhash) with a per-vault random
 *    16-byte salt. Interactive ops/mem limits (2 / 64 MiB) so unlock stays
 *    usable on low-RAM phones.
 *  - Vault sealing: crypto_secretbox_easy (XSalsa20-Poly1305), the libsodium
 *    "secretbox" primitive, output framed as nonce || ciphertext.
 *  - Per-Face identity keys: crypto_box keypair (X25519).
 *
 * The same class runs on-device (LazySodiumAndroid) and in host unit tests
 * (LazySodiumJava), so the crypto is exercised in CI.
 */
class CryptoManager(private val ls: LazySodium) {

    companion object {
        const val SALT_BYTES = 16
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 24
        const val MAC_BYTES = 16
        const val OPS_LIMIT = 2L
        const val MEM_LIMIT = 67108864 // 64 MiB
    }

    private val pwHash get() = ls as PwHash.Native
    private val secretBox get() = ls as SecretBox.Native
    private val box get() = ls as Box.Lazy

    fun randomSalt(): ByteArray = ls.randomBytesBuf(SALT_BYTES)

    fun randomHex(bytes: Int): String = toHex(ls.randomBytesBuf(bytes))

    fun deriveKey(pin: String, salt: ByteArray): ByteArray {
        require(salt.size == SALT_BYTES) { "bad salt length" }
        val key = ByteArray(KEY_BYTES)
        val pw = pin.toByteArray(Charsets.UTF_8)
        val ok = pwHash.cryptoPwHash(
            key, KEY_BYTES, pw, pw.size, salt,
            OPS_LIMIT, NativeLong(MEM_LIMIT.toLong()),
            PwHash.Alg.PWHASH_ALG_ARGON2ID13,
        )
        check(ok) { "Argon2id derivation failed" }
        return key
    }

    /** Returns nonce || ciphertext. */
    fun seal(plain: ByteArray, key: ByteArray): ByteArray {
        val nonce = ls.randomBytesBuf(NONCE_BYTES)
        val cipher = ByteArray(plain.size + MAC_BYTES)
        val ok = secretBox.cryptoSecretBoxEasy(cipher, plain, plain.size.toLong(), nonce, key)
        check(ok) { "seal failed" }
        return nonce + cipher
    }

    /** Input is nonce || ciphertext; returns null if authentication fails. */
    fun open(blob: ByteArray, key: ByteArray): ByteArray? {
        if (blob.size < NONCE_BYTES + MAC_BYTES) return null
        val nonce = blob.copyOfRange(0, NONCE_BYTES)
        val cipher = blob.copyOfRange(NONCE_BYTES, blob.size)
        val plain = ByteArray(cipher.size - MAC_BYTES)
        val ok = secretBox.cryptoSecretBoxOpenEasy(plain, cipher, cipher.size.toLong(), nonce, key)
        return if (ok) plain else null
    }

    /** X25519 identity keypair for a Face, as (publicHex, secretHex). */
    fun newIdentityKeypair(): Pair<String, String> {
        val kp = box.cryptoBoxKeypair()
        return kp.publicKey.asHexString to kp.secretKey.asHexString
    }

    private val boxNative get() = ls as Box.Native

    /** crypto_box seal: my secret key + peer public key. Returns nonce||ciphertext. */
    fun boxSeal(plain: ByteArray, peerPubKeyHex: String, mySecretKeyHex: String): ByteArray {
        val nonce = ls.randomBytesBuf(Box.NONCEBYTES)
        val cipher = ByteArray(plain.size + Box.MACBYTES)
        val ok = boxNative.cryptoBoxEasy(
            cipher, plain, plain.size.toLong(), nonce,
            hexToBytes(peerPubKeyHex), hexToBytes(mySecretKeyHex),
        )
        check(ok) { "box seal failed" }
        return nonce + cipher
    }

    /** crypto_box open; returns null if authentication fails. */
    fun boxOpen(blob: ByteArray, peerPubKeyHex: String, mySecretKeyHex: String): ByteArray? {
        if (blob.size < Box.NONCEBYTES + Box.MACBYTES) return null
        val nonce = blob.copyOfRange(0, Box.NONCEBYTES)
        val cipher = blob.copyOfRange(Box.NONCEBYTES, blob.size)
        val plain = ByteArray(cipher.size - Box.MACBYTES)
        val ok = boxNative.cryptoBoxOpenEasy(
            plain, cipher, cipher.size.toLong(), nonce,
            hexToBytes(peerPubKeyHex), hexToBytes(mySecretKeyHex),
        )
        return if (ok) plain else null
    }

    /**
     * Anonymous sealed box (crypto_box_seal) to a recipient public key. Used
     * for KNOCK: the sender isn't in the recipient's Circle yet, so there's no
     * shared knowledge of the sender's key — the sender stays anonymous until
     * the recipient opens the knock and learns their CMC-ID from inside.
     */
    fun sealedSeal(plain: ByteArray, recipientPubKeyHex: String): ByteArray {
        val cipher = ByteArray(plain.size + Box.SEALBYTES)
        val ok = boxNative.cryptoBoxSeal(cipher, plain, plain.size.toLong(), hexToBytes(recipientPubKeyHex))
        check(ok) { "sealed seal failed" }
        return cipher
    }

    fun sealedOpen(cipher: ByteArray, myPubKeyHex: String, mySecretKeyHex: String): ByteArray? {
        if (cipher.size < Box.SEALBYTES) return null
        val plain = ByteArray(cipher.size - Box.SEALBYTES)
        val ok = boxNative.cryptoBoxSealOpen(
            plain, cipher, cipher.size.toLong(),
            hexToBytes(myPubKeyHex), hexToBytes(mySecretKeyHex),
        )
        return if (ok) plain else null
    }

    // ---- forward-secrecy primitives (X25519 DH, BLAKE2b KDF, XChaCha AEAD) ----
    private val dhNative get() = ls as com.goterl.lazysodium.interfaces.DiffieHellman.Native
    private val genericHash get() = ls as com.goterl.lazysodium.interfaces.GenericHash.Native
    private val aeadNative get() = ls as com.goterl.lazysodium.interfaces.AEAD.Native

    val AEAD_NONCE_BYTES = 24   // XChaCha20-Poly1305 IETF npub
    private val AEAD_ABYTES = 16

    fun randomBytes(n: Int): ByteArray = ls.randomBytesBuf(n)

    /** A fresh X25519 keypair (hex pub, hex sec), e.g. for an ephemeral or prekey. */
    fun newX25519Keypair(): Pair<String, String> = newIdentityKeypair()

    /** Raw X25519 Diffie-Hellman: scalarmult(mySecret, theirPublic) -> 32 bytes. */
    fun dh(theirPubHex: String, mySecHex: String): ByteArray {
        val out = ByteArray(32)
        val ok = dhNative.cryptoScalarMult(out, hexToBytes(mySecHex), hexToBytes(theirPubHex))
        check(ok) { "scalarmult failed" }
        return out
    }

    /** BLAKE2b KDF -> 32-byte key from arbitrary input material. */
    fun kdf32(input: ByteArray): ByteArray {
        val out = ByteArray(KEY_BYTES)
        val ok = genericHash.cryptoGenericHash(out, out.size, input, input.size.toLong())
        check(ok) { "kdf failed" }
        return out
    }

    /** XChaCha20-Poly1305 AEAD seal with associated data. Returns ciphertext||tag. */
    fun aeadSeal(plain: ByteArray, key32: ByteArray, nonce24: ByteArray, aad: ByteArray): ByteArray {
        val c = ByteArray(plain.size + AEAD_ABYTES)
        val ok = aeadNative.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            c, null, plain, plain.size.toLong(), aad, aad.size.toLong(), null, nonce24, key32,
        )
        check(ok) { "aead seal failed" }
        return c
    }

    /** XChaCha20-Poly1305 AEAD open; null if auth fails (tampered / wrong key). */
    fun aeadOpen(cipher: ByteArray, key32: ByteArray, nonce24: ByteArray, aad: ByteArray): ByteArray? {
        if (cipher.size < AEAD_ABYTES) return null
        val m = ByteArray(cipher.size - AEAD_ABYTES)
        val ok = aeadNative.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            m, null, null, cipher, cipher.size.toLong(), aad, aad.size.toLong(), nonce24, key32,
        )
        return if (ok) m else null
    }

    fun toHexPublic(b: ByteArray): String = toHex(b)

    private fun toHex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

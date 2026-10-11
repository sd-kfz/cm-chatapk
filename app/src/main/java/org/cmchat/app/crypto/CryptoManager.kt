package org.cmchat.app.crypto

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.PwHash
import com.goterl.lazysodium.interfaces.SecretBox
import com.sun.jna.NativeLong


class CryptoManager(private val ls: LazySodium) {

    companion object {
        const val SALT_BYTES = 16
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 24
        const val MAC_BYTES = 16
        const val OPS_LIMIT = 2L
        const val MEM_LIMIT = 67108864
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


    fun seal(plain: ByteArray, key: ByteArray): ByteArray {
        val nonce = ls.randomBytesBuf(NONCE_BYTES)
        val cipher = ByteArray(plain.size + MAC_BYTES)
        val ok = secretBox.cryptoSecretBoxEasy(cipher, plain, plain.size.toLong(), nonce, key)
        check(ok) { "seal failed" }
        return nonce + cipher
    }


    fun open(blob: ByteArray, key: ByteArray): ByteArray? {
        if (blob.size < NONCE_BYTES + MAC_BYTES) return null
        val nonce = blob.copyOfRange(0, NONCE_BYTES)
        val cipher = blob.copyOfRange(NONCE_BYTES, blob.size)
        val plain = ByteArray(cipher.size - MAC_BYTES)
        val ok = secretBox.cryptoSecretBoxOpenEasy(plain, cipher, cipher.size.toLong(), nonce, key)
        return if (ok) plain else null
    }


    fun newIdentityKeypair(): Pair<String, String> {
        val kp = box.cryptoBoxKeypair()
        return kp.publicKey.asHexString to kp.secretKey.asHexString
    }

    private val boxNative get() = ls as Box.Native


    fun boxSeal(plain: ByteArray, peerPubKeyHex: String, mySecretKeyHex: String): ByteArray {
        val nonce = ls.randomBytesBuf(Box.NONCEBYTES)
        val cipher = ByteArray(plain.size + Box.MACBYTES)
        val sk = hexToBytes(mySecretKeyHex)
        val ok = try {
            boxNative.cryptoBoxEasy(cipher, plain, plain.size.toLong(), nonce, hexToBytes(peerPubKeyHex), sk)
        } finally {
            sk.fill(0)
        }
        check(ok) { "box seal failed" }
        return nonce + cipher
    }


    fun boxOpen(blob: ByteArray, peerPubKeyHex: String, mySecretKeyHex: String): ByteArray? {
        if (blob.size < Box.NONCEBYTES + Box.MACBYTES) return null
        val nonce = blob.copyOfRange(0, Box.NONCEBYTES)
        val cipher = blob.copyOfRange(Box.NONCEBYTES, blob.size)
        val plain = ByteArray(cipher.size - Box.MACBYTES)
        val sk = hexToBytes(mySecretKeyHex)
        val ok = try {
            boxNative.cryptoBoxOpenEasy(plain, cipher, cipher.size.toLong(), nonce, hexToBytes(peerPubKeyHex), sk)
        } finally {
            sk.fill(0)
        }
        return if (ok) plain else null
    }


    fun sealedSeal(plain: ByteArray, recipientPubKeyHex: String): ByteArray {
        val cipher = ByteArray(plain.size + Box.SEALBYTES)
        val ok = boxNative.cryptoBoxSeal(cipher, plain, plain.size.toLong(), hexToBytes(recipientPubKeyHex))
        check(ok) { "sealed seal failed" }
        return cipher
    }

    fun sealedOpen(cipher: ByteArray, myPubKeyHex: String, mySecretKeyHex: String): ByteArray? {
        if (cipher.size < Box.SEALBYTES) return null
        val plain = ByteArray(cipher.size - Box.SEALBYTES)
        val sk = hexToBytes(mySecretKeyHex)
        val ok = try {
            boxNative.cryptoBoxSealOpen(plain, cipher, cipher.size.toLong(), hexToBytes(myPubKeyHex), sk)
        } finally {
            sk.fill(0)
        }
        return if (ok) plain else null
    }




    private val dhNative get() = ls as com.goterl.lazysodium.interfaces.DiffieHellman.Native
    private val genericHash get() = ls as com.goterl.lazysodium.interfaces.GenericHash.Native
    private val aeadNative get() = ls as com.goterl.lazysodium.interfaces.AEAD.Native

    private val AEAD_ABYTES = 16

    fun randomBytes(n: Int): ByteArray = ls.randomBytesBuf(n)


    fun x25519Keypair(): Pair<ByteArray, ByteArray> {
        val pk = ByteArray(Box.PUBLICKEYBYTES)
        val sk = ByteArray(Box.SECRETKEYBYTES)
        check(boxNative.cryptoBoxKeypair(pk, sk)) { "keypair generation failed" }
        return pk to sk
    }


    fun x25519(mySec: ByteArray, theirPub: ByteArray): ByteArray? {
        if (mySec.size != 32 || theirPub.size != 32) return null
        val out = ByteArray(32)
        if (dhNative.cryptoScalarMult(out, mySec, theirPub)) return out
        out.fill(0)
        return null
    }


    fun hexBytes(hex: String): ByteArray = hexToBytes(hex)


    fun kdf32(input: ByteArray): ByteArray {
        val out = ByteArray(KEY_BYTES)
        val ok = genericHash.cryptoGenericHash(out, out.size, input, input.size.toLong())
        check(ok) { "kdf failed" }
        return out
    }


    fun aeadSeal(plain: ByteArray, key32: ByteArray, nonce24: ByteArray, aad: ByteArray): ByteArray {
        val c = ByteArray(plain.size + AEAD_ABYTES)
        val ok = aeadNative.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            c, null, plain, plain.size.toLong(), aad, aad.size.toLong(), null, nonce24, key32,
        )
        check(ok) { "aead seal failed" }
        return c
    }


    fun aeadOpen(cipher: ByteArray, key32: ByteArray, nonce24: ByteArray, aad: ByteArray): ByteArray? {
        if (cipher.size < AEAD_ABYTES) return null
        val m = ByteArray(cipher.size - AEAD_ABYTES)
        val ok = aeadNative.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            m, null, null, cipher, cipher.size.toLong(), aad, aad.size.toLong(), nonce24, key32,
        )
        return if (ok) m else null
    }

    private fun toHex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

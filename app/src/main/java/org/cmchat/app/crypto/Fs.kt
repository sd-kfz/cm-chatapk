package org.cmchat.app.crypto

/**
 * Forward-secrecy frame crypto — an X3DH-style agreement that needs NO
 * interactive handshake (fits the connectionless, one-shot-connection model).
 *
 * To send, the sender generates a FRESH ephemeral X25519 key and combines three
 * Diffie-Hellmans against the recipient's short-lived PREKEY and both parties'
 * long-term IDENTITY keys:
 *   DH1 = eph       × recipientPrekey    (the forward-secret term)
 *   DH2 = myIdentity × recipientPrekey   (authenticates the sender)
 *   DH3 = eph       × recipientIdentity  (binds the recipient's identity)
 *   key = BLAKE2b(INFO || DH1 || DH2 || DH3)
 * The message is then AEAD-sealed (XChaCha20-Poly1305) under that key, with the
 * ephemeral pubkey + prekey id as associated data.
 *
 * Forward secrecy: DH1 and DH3 both need the ephemeral private (deleted right
 * after send) and DH1/DH2 need the prekey private (deleted after the rotation
 * window). So a later compromise of EITHER identity key cannot re-derive the
 * key — the ephemeral and rotated prekey are gone. Identity keys only
 * authenticate; they never encrypt content directly.
 *
 * Each message uses a fresh ephemeral, so message keys are independent: a later
 * key reveals nothing about an earlier one. The derived key and all DH outputs
 * are zeroed immediately after use (ephemeral key material is generated locally
 * and dropped; JVM String keys can't be wiped in place — see report).
 */
object Fs {

    private val INFO = "cmchat-x3dh-v3".toByteArray()
    const val EPH = 32
    const val PKID = 8
    const val NONCE = 24
    const val HEADER = EPH + PKID + NONCE   // 64 bytes before the ciphertext

    data class Parsed(val ephPubHex: String, val prekeyIdHex: String, val nonce: ByteArray, val cipher: ByteArray)

    /** Build an FS frame: [ephPub(32)][prekeyId(8)][nonce(24)][AEAD ciphertext]. */
    fun seal(
        crypto: CryptoManager, plain: ByteArray,
        myIdSecHex: String, recipientIdPubHex: String,
        recipientPrekeyPubHex: String, recipientPrekeyIdHex: String,
    ): ByteArray {
        val (ePubHex, eSecHex) = crypto.newX25519Keypair()
        val key = deriveSend(crypto, myIdSecHex, recipientIdPubHex, recipientPrekeyPubHex, eSecHex)
        try {
            val nonce = crypto.randomBytes(NONCE)
            val ephRaw = hexBytes(ePubHex)
            val pkidRaw = hexBytes(recipientPrekeyIdHex)
            val cipher = crypto.aeadSeal(plain, key, nonce, ephRaw + pkidRaw)
            return ephRaw + pkidRaw + nonce + cipher
        } finally {
            key.fill(0)
        }
    }

    fun parse(frame: ByteArray): Parsed? {
        if (frame.size < HEADER) return null
        val eph = frame.copyOfRange(0, EPH)
        val pkid = frame.copyOfRange(EPH, EPH + PKID)
        val nonce = frame.copyOfRange(EPH + PKID, HEADER)
        val cipher = frame.copyOfRange(HEADER, frame.size)
        return Parsed(hex(eph), hex(pkid), nonce, cipher)
    }

    /** Try to open [p] as sent by the contact whose identity is [senderIdPubHex],
     * using our own identity secret + the prekey secret for p.prekeyIdHex. Returns
     * the plaintext or null (wrong contact / tampered / wrong prekey). */
    fun open(
        crypto: CryptoManager, p: Parsed,
        myIdSecHex: String, myPrekeySecHex: String, senderIdPubHex: String,
    ): ByteArray? {
        val key = deriveRecv(crypto, myIdSecHex, myPrekeySecHex, senderIdPubHex, p.ephPubHex)
        try {
            return crypto.aeadOpen(p.cipher, key, p.nonce, hexBytes(p.ephPubHex) + hexBytes(p.prekeyIdHex))
        } finally {
            key.fill(0)
        }
    }

    private fun deriveSend(c: CryptoManager, myIdSec: String, rIdPub: String, rPrekeyPub: String, eSec: String): ByteArray {
        val dh1 = c.dh(rPrekeyPub, eSec)
        val dh2 = c.dh(rPrekeyPub, myIdSec)
        val dh3 = c.dh(rIdPub, eSec)
        try { return c.kdf32(INFO + dh1 + dh2 + dh3) }
        finally { dh1.fill(0); dh2.fill(0); dh3.fill(0) }
    }

    private fun deriveRecv(c: CryptoManager, myIdSec: String, myPrekeySec: String, sIdPub: String, sEphPub: String): ByteArray {
        val dh1 = c.dh(sEphPub, myPrekeySec)
        val dh2 = c.dh(sIdPub, myPrekeySec)
        val dh3 = c.dh(sEphPub, myIdSec)
        try { return c.kdf32(INFO + dh1 + dh2 + dh3) }
        finally { dh1.fill(0); dh2.fill(0); dh3.fill(0) }
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    private fun hexBytes(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

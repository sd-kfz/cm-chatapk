package org.cmchat.app.crypto

import java.security.MessageDigest

/**
 * Forward-secret message crypto — an X3DH-style key agreement, done fresh for
 * EVERY message. Raw bytes throughout so each secret can be wiped.
 *
 * Inputs per message:
 *  - the sender's FRESH ephemeral X25519 key (made here, wiped right after use);
 *  - the recipient's ONE-TIME prekey, fetched for this very message over the
 *    same connection and authenticated by the recipient's identity key (see
 *    transport/SecureChannel);
 *  - both long-term identity keys, which only AUTHENTICATE — no message is ever
 *    encrypted under an identity key.
 *
 *   DH1 = X25519(eph,      prekey)       forward secrecy (both halves ephemeral)
 *   DH2 = X25519(senderId, prekey)       authenticates the sender
 *   DH3 = X25519(eph,      recipientId)  binds the recipient
 *   key = BLAKE2b-256(INFO || DH1 || DH2 || DH3 || senderIdPub || recipientIdPub
 *                     || ephPub || prekeyPub || prekeyId)
 *
 * The plaintext is sealed with XChaCha20-Poly1305 under that key, with the frame
 * header (ephPub || prekeyId) as associated data, so the header can't be swapped.
 * The KDF input binds both identities in sender→recipient order plus every
 * public value of this exchange, so a frame can't be reflected or re-pointed.
 *
 * Why this is forward-secret: DH1 needs the ephemeral secret or the prekey
 * secret. The ephemeral is wiped as soon as the key is derived; the prekey is
 * one-time and wiped when its connection ends. After that, NOTHING that is kept
 * — not even both identity secrets — can re-derive the key.
 *
 * Why each key is independent ("a later key can't derive an earlier one"):
 * every message uses its own fresh ephemeral AND its own fresh prekey, so there
 * is no chain between message keys at all — each one is a full DH ratchet step.
 * A stolen message key opens that one message and nothing else, earlier or later.
 *
 * Wiping is best-effort on the JVM (the GC may have copied an array before it is
 * zeroed, and JNA briefly marshals arrays into native memory); identity secrets
 * also live in RAM as hex Strings from the vault, which can't be wiped in place.
 */
object Fs {

    private val INFO = "CM-Chat FS v3 | X3DH | XChaCha20-Poly1305".toByteArray(Charsets.US_ASCII)

    const val KEY = 32
    const val PKID = 8
    const val NONCE = 24
    const val TAG = 16
    /** [ephPub(32)][prekeyId(8)][nonce(24)] precede the AEAD ciphertext. */
    const val HEADER = KEY + PKID + NONCE
    /** Total bytes a forward-secret frame adds on top of its plaintext. */
    const val OVERHEAD = HEADER + TAG

    class Parsed(val ephPub: ByteArray, val prekeyId: ByteArray, val nonce: ByteArray, val cipher: ByteArray)

    /**
     * Seal [plain] for one recipient. Returns `ephPub || prekeyId || nonce ||
     * ciphertext`, or null if the key agreement is refused (malformed prekey).
     * The ephemeral secret is wiped before this returns, success or not.
     * [ephemeral] is injectable only so tests can verify that wipe.
     */
    fun seal(
        c: CryptoManager, plain: ByteArray,
        senderIdSec: ByteArray, senderIdPub: ByteArray, recipientIdPub: ByteArray,
        prekeyPub: ByteArray, prekeyId: ByteArray,
        ephemeral: Pair<ByteArray, ByteArray> = c.x25519Keypair(),
    ): ByteArray? {
        val (ephPub, ephSec) = ephemeral
        val key: ByteArray?
        try {
            key = senderKey(c, senderIdSec, senderIdPub, recipientIdPub, ephSec, ephPub, prekeyPub, prekeyId)
        } finally {
            ephSec.fill(0)                      // the ephemeral private key is gone
        }
        if (key == null) return null
        try {
            val nonce = c.randomBytes(NONCE)
            val header = ephPub + prekeyId
            return header + nonce + c.aeadSeal(plain, key, nonce, header)
        } finally {
            key.fill(0)                         // and so is the message key
        }
    }

    fun parse(frame: ByteArray): Parsed? {
        if (frame.size < HEADER + TAG) return null
        return Parsed(
            ephPub = frame.copyOfRange(0, KEY),
            prekeyId = frame.copyOfRange(KEY, KEY + PKID),
            nonce = frame.copyOfRange(KEY + PKID, HEADER),
            cipher = frame.copyOfRange(HEADER, frame.size),
        )
    }

    /**
     * Open [p] as the recipient, using the one-time prekey it was sealed to.
     * Returns the plaintext, or null on ANY failure: a frame for a different
     * prekey, a malformed ephemeral, the wrong sender, or a tampered byte.
     */
    fun open(
        c: CryptoManager, p: Parsed,
        recipientIdSec: ByteArray, recipientIdPub: ByteArray, senderIdPub: ByteArray,
        prekeySec: ByteArray, prekeyPub: ByteArray, prekeyId: ByteArray,
    ): ByteArray? {
        if (!MessageDigest.isEqual(p.prekeyId, prekeyId)) return null
        val key = recipientKey(c, recipientIdSec, recipientIdPub, senderIdPub, prekeySec, prekeyPub, prekeyId, p.ephPub)
            ?: return null
        try {
            return c.aeadOpen(p.cipher, key, p.nonce, p.ephPub + p.prekeyId)
        } finally {
            key.fill(0)
        }
    }

    // ---- key derivation (internal so the loopback tests can inspect keys) ----

    internal fun senderKey(
        c: CryptoManager, senderIdSec: ByteArray, senderIdPub: ByteArray, recipientIdPub: ByteArray,
        ephSec: ByteArray, ephPub: ByteArray, prekeyPub: ByteArray, prekeyId: ByteArray,
    ): ByteArray? {
        val dh1 = c.x25519(ephSec, prekeyPub)
        val dh2 = c.x25519(senderIdSec, prekeyPub)
        val dh3 = c.x25519(ephSec, recipientIdPub)
        try {
            if (dh1 == null || dh2 == null || dh3 == null) return null
            return derive(c, dh1, dh2, dh3, senderIdPub, recipientIdPub, ephPub, prekeyPub, prekeyId)
        } finally {
            dh1?.fill(0); dh2?.fill(0); dh3?.fill(0)
        }
    }

    internal fun recipientKey(
        c: CryptoManager, recipientIdSec: ByteArray, recipientIdPub: ByteArray, senderIdPub: ByteArray,
        prekeySec: ByteArray, prekeyPub: ByteArray, prekeyId: ByteArray, ephPub: ByteArray,
    ): ByteArray? {
        val dh1 = c.x25519(prekeySec, ephPub)
        val dh2 = c.x25519(prekeySec, senderIdPub)
        val dh3 = c.x25519(recipientIdSec, ephPub)
        try {
            if (dh1 == null || dh2 == null || dh3 == null) return null
            return derive(c, dh1, dh2, dh3, senderIdPub, recipientIdPub, ephPub, prekeyPub, prekeyId)
        } finally {
            dh1?.fill(0); dh2?.fill(0); dh3?.fill(0)
        }
    }

    internal fun derive(
        c: CryptoManager, dh1: ByteArray, dh2: ByteArray, dh3: ByteArray,
        senderIdPub: ByteArray, recipientIdPub: ByteArray,
        ephPub: ByteArray, prekeyPub: ByteArray, prekeyId: ByteArray,
    ): ByteArray? {
        val parts = arrayOf(INFO, dh1, dh2, dh3, senderIdPub, recipientIdPub, ephPub, prekeyPub, prekeyId)
        if (senderIdPub.size != KEY || recipientIdPub.size != KEY || ephPub.size != KEY ||
            prekeyPub.size != KEY || prekeyId.size != PKID) return null
        val input = ByteArray(parts.sumOf { it.size })
        var o = 0
        for (part in parts) { part.copyInto(input, o); o += part.size }
        try {
            return c.kdf32(input)
        } finally {
            input.fill(0)                       // the buffer holds the DH outputs
        }
    }
}

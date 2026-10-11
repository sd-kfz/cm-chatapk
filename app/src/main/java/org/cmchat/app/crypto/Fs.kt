package org.cmchat.app.crypto

import java.security.MessageDigest


object Fs {

    private val INFO = "CM-Chat FS v3 | X3DH | XChaCha20-Poly1305".toByteArray(Charsets.US_ASCII)

    const val KEY = 32
    const val PKID = 8
    const val NONCE = 24
    const val TAG = 16

    const val HEADER = KEY + PKID + NONCE

    const val OVERHEAD = HEADER + TAG

    class Parsed(val ephPub: ByteArray, val prekeyId: ByteArray, val nonce: ByteArray, val cipher: ByteArray)


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
            ephSec.fill(0)
        }
        if (key == null) return null
        try {
            val nonce = c.randomBytes(NONCE)
            val header = ephPub + prekeyId
            return header + nonce + c.aeadSeal(plain, key, nonce, header)
        } finally {
            key.fill(0)
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
            input.fill(0)
        }
    }
}

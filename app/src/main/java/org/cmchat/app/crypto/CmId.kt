package org.cmchat.app.crypto

/** Decoded CMC-ID: the peer's onion address and identity (crypto_box) public key. */
data class CmIdData(val onion: String, val identityPubKeyHex: String)

/**
 * CMC-ID = "cmc1:" + base32( [onionLen][onion ASCII bytes][32-byte identity pubkey] ).
 *
 * The onion address is the endpoint (from the Face's onion service); the
 * identity public key is the separate messaging key used for crypto_box. Both
 * are needed to reach AND to encrypt to a peer.
 */
object CmId {
    private const val PREFIX = "cmc1:"
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val PUBKEY_LEN = 32

    fun encode(onion: String, identityPubKeyHex: String): String {
        val pub = hexToBytes(identityPubKeyHex)
        require(pub.size == PUBKEY_LEN) { "identity public key must be 32 bytes" }
        val onionBytes = onion.toByteArray(Charsets.US_ASCII)
        require(onionBytes.size <= 255) { "onion too long" }
        val buf = ByteArray(1 + onionBytes.size + PUBKEY_LEN)
        buf[0] = onionBytes.size.toByte()
        onionBytes.copyInto(buf, 1)
        pub.copyInto(buf, 1 + onionBytes.size)
        return PREFIX + base32Encode(buf)
    }

    fun decode(cmId: String): CmIdData? {
        if (!cmId.startsWith(PREFIX)) return null
        val raw = base32Decode(cmId.removePrefix(PREFIX)) ?: return null
        if (raw.isEmpty()) return null
        val onionLen = raw[0].toInt() and 0xff
        if (onionLen == 0 || raw.size != 1 + onionLen + PUBKEY_LEN) return null
        val onion = String(raw, 1, onionLen, Charsets.US_ASCII)
        val pub = raw.copyOfRange(1 + onionLen, raw.size)
        return CmIdData(onion, bytesToHex(pub))
    }

    private fun base32Encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALPHABET[(buffer shr bits) and 0x1f])
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 0x1f])
        return sb.toString()
    }

    private fun base32Decode(s: String): ByteArray? {
        if (s.isEmpty()) return null
        val out = ArrayList<Byte>(s.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (c in s) {
            val v = ALPHABET.indexOf(c.uppercaseChar())
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xff).toByte())
            }
        }
        return out.toByteArray()
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) {
            hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    private fun bytesToHex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it) }
}

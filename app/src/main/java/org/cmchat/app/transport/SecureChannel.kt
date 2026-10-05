package org.cmchat.app.transport

import org.cmchat.app.crypto.CryptoManager
import org.cmchat.app.crypto.Fs
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Inner frame: `[ver(1)][type(1)][sessionId(8)][seq(8)][payload]`. It always
 * travels INSIDE the padding and the encryption — never visible on the wire.
 * The version lets mismatched builds be detected instead of failing silently;
 * sessionId + seq drive replay protection ([ReplayGuard]).
 *
 * Sequence numbers are counted PER PEER: a contact's replay window then only
 * sees the frames sent to that contact, with no gaps from sends to others —
 * gaps that could otherwise push a slow-but-legitimate frame out of the window.
 */
class InnerCodec(sessionId: ByteArray? = null) {

    private val sid: ByteArray = sessionId?.copyOf() ?: ByteArray(8).also { SecureRandom().nextBytes(it) }
    private val seqs = ConcurrentHashMap<String, AtomicLong>()

    class Inner(val version: Int, val type: FrameType?, val sidHex: String, val seq: Long, val body: ByteArray)

    fun wrap(type: FrameType, payload: ByteArray, peerKey: String, version: Int = SecureChannel.WIRE_VERSION): ByteArray {
        val seq = seqs.computeIfAbsent(peerKey.lowercase()) { AtomicLong(0) }.getAndIncrement()
        val out = ByteArray(HDR + payload.size)
        out[0] = version.toByte()
        out[1] = type.code.toByte()
        sid.copyInto(out, 2)
        for (i in 0 until 8) out[10 + i] = (seq ushr (56 - i * 8)).toByte()
        payload.copyInto(out, HDR)
        return out
    }

    fun unwrap(inner: ByteArray): Inner? {
        if (inner.size < HDR) return null
        val version = inner[0].toInt() and 0xff
        val type = FrameType.fromCode(inner[1].toInt() and 0xff)
        val sidHex = inner.copyOfRange(2, 10).joinToString("") { "%02x".format(it) }
        var seq = 0L
        for (i in 0 until 8) seq = (seq shl 8) or (inner[10 + i].toLong() and 0xff)
        return Inner(version, type, sidHex, seq, inner.copyOfRange(HDR, inner.size))
    }

    companion object { const val HDR = 18 }
}

/**
 * The v3 wire protocol between two contacts: forward-secret, and socket-free so
 * the exact production logic runs in the loopback unit tests.
 *
 * One connection carries exactly ONE message, in three frames:
 *
 *  1. A → B  PREKEY_REQ   crypto_box(A_id → B_id) of a fresh 16-byte challenge.
 *                         Only a known contact can make one; others are ignored.
 *  2. B → A  PREKEY_RESP  crypto_box(B_id → A_id) of `prekeyId | prekeyPub |
 *                         challenge` — a ONE-TIME X25519 prekey made just for
 *                         this connection. A accepts it only if it opens under
 *                         B's identity key (nobody without B's identity secret
 *                         can forge or swap it — even with B's onion key) AND it
 *                         echoes A's challenge (an old reply can't be replayed).
 *  3. A → B  Fs frame     X3DH(fresh ephemeral, that prekey, both identities) →
 *                         XChaCha20-Poly1305 of the padded inner frame. B opens it
 *                         with the prekey and then wipes the prekey.
 *
 * Why fetch the prekey per message instead of publishing one ahead of time:
 * there is no server, so the recipient is ALWAYS online when a message is sent —
 * the fetch is one round trip on a connection we're opening anyway. A prekey
 * cached ahead of time can go stale (the recipient restarts and its RAM-only
 * prekeys are gone) and a message sealed to a stale prekey would be silently
 * undecryptable. Fetching per message can never go stale, and making each
 * prekey one-time (rotated on every request, wiped when its connection ends)
 * gives a forward-secrecy window of one connection instead of a rotation period.
 *
 * "Signed": the prekey is authenticated by the identity key with crypto_box's
 * MAC (X25519 + Poly1305), not an Ed25519 signature — identities are X25519-only,
 * and adding a signing key would change every CMC-ID. For a prekey fetched over
 * this pairwise channel the guarantee is the same: only the holder of B's
 * identity secret can produce a prekey A will accept.
 *
 * Identity keys only AUTHENTICATE: they seal the two tiny handshake frames
 * (which carry no user content — a random challenge and a public key) and feed
 * DH2/DH3. ALL user content (messages, status, buzz, erase, address updates,
 * cover traffic) travels only in step 3. The anonymous KNOCK to a not-yet-contact
 * stays a sealed box (see [sealKnock]).
 */
class SecureChannel(
    private val crypto: CryptoManager,
    private val myIdPubHex: String,
    private val myIdSecHex: String,
    private val codec: InnerCodec,
    private val replay: ReplayGuard,
) {

    companion object {
        /** v3 = forward-secret handshake. (v2 = static crypto_box + replay counter.) */
        const val WIRE_VERSION = 3
        const val CHALLENGE = 16
        private const val RESP_BODY = Fs.PKID + Fs.KEY + CHALLENGE

        /** Frame types allowed to carry content in step 3 — never handshake types. */
        val CONTENT_TYPES: Set<FrameType> = setOf(
            FrameType.MSG, FrameType.STATUS, FrameType.ERASE_CHAT, FrameType.BUZZ,
            FrameType.ADDR_UPDATE, FrameType.COVER, FrameType.KNOCK_ACCEPT,
        )
    }

    // ---- anonymous KNOCK --------------------------------------------------

    /**
     * KNOCK to someone who isn't a contact yet: an anonymous sealed box to their
     * identity key (unchanged from v2 apart from the version byte). NOT forward
     * secret — it holds only the knocker's display name and their own CMC-ID.
     */
    fun sealKnock(payload: ByteArray, recipientIdPubHex: String): ByteArray {
        val inner = codec.wrap(FrameType.KNOCK, payload, recipientIdPubHex)
        val plain = FramePad.pad(inner)
        try {
            return crypto.sealedSeal(plain, recipientIdPubHex)
        } finally {
            inner.fill(0); plain.fill(0)
        }
    }

    // ---- sender side ------------------------------------------------------

    /** A contact's one-time prekey, verified for this connection. Public values only. */
    class Prekey internal constructor(val id: ByteArray, val pub: ByteArray)

    sealed interface Verdict {
        class Ok(val prekey: Prekey) : Verdict
        object VersionMismatch : Verdict
        class Rejected(val reason: String) : Verdict
    }

    /** One outgoing message's handshake. Use once, on one connection. */
    inner class Client(private val peerIdPubHex: String) {

        private val challenge = crypto.randomBytes(CHALLENGE)

        /** Step 1: the prekey request, authenticated to the peer by my identity key. */
        val request: ByteArray = run {
            val inner = codec.wrap(FrameType.PREKEY_REQ, challenge, peerIdPubHex)
            val plain = FramePad.pad(inner)
            try { crypto.boxSeal(plain, peerIdPubHex, myIdSecHex) } finally { inner.fill(0); plain.fill(0) }
        }

        /** Step 2: accept the prekey only if the peer's identity key vouches for it
         * AND it answers THIS request. Anything else is rejected. */
        fun verify(response: ByteArray): Verdict {
            val opened = crypto.boxOpen(response, peerIdPubHex, myIdSecHex)
                ?: return Verdict.Rejected("prekey not authenticated by the contact's identity key")
            val inner = FramePad.unpad(opened) ?: return Verdict.Rejected("malformed prekey reply")
            val f = codec.unwrap(inner) ?: return Verdict.Rejected("malformed prekey reply")
            if (f.version != WIRE_VERSION) return Verdict.VersionMismatch
            if (f.type != FrameType.PREKEY_RESP || f.body.size != RESP_BODY) {
                return Verdict.Rejected("not a prekey reply")
            }
            val echo = f.body.copyOfRange(Fs.PKID + Fs.KEY, RESP_BODY)
            if (!MessageDigest.isEqual(echo, challenge)) {
                return Verdict.Rejected("prekey reply is not for this request")
            }
            return Verdict.Ok(Prekey(
                id = f.body.copyOfRange(0, Fs.PKID),
                pub = f.body.copyOfRange(Fs.PKID, Fs.PKID + Fs.KEY),
            ))
        }

        /** Step 3: the forward-secret frame (fresh ephemeral; wiped inside [Fs.seal]). */
        fun seal(prekey: Prekey, type: FrameType, payload: ByteArray): ByteArray {
            require(type in CONTENT_TYPES) { "not a content frame type" }
            val inner = codec.wrap(type, payload, peerIdPubHex)
            val plain = FramePad.pad(inner)
            val mySec = crypto.hexBytes(myIdSecHex)
            try {
                return Fs.seal(
                    crypto, plain, mySec, crypto.hexBytes(myIdPubHex), crypto.hexBytes(peerIdPubHex),
                    prekey.pub, prekey.id,
                ) ?: throw IOException("key agreement refused (malformed prekey)")
            } finally {
                mySec.fill(0); inner.fill(0); plain.fill(0)
            }
        }
    }

    // ---- recipient side ---------------------------------------------------

    sealed interface First {
        class Knock(val body: ByteArray) : First
        class Handshake(val server: Server) : First
        object VersionMismatch : First
        class Drop(val reason: String) : First
    }

    /**
     * Classify the FIRST frame of an incoming connection: an anonymous knock, a
     * prekey request from a known contact (→ a [Server] for this connection), a
     * different wire version, or something to drop. [contacts] maps cmId →
     * identity public key (hex); [allow] is the per-contact rate limit.
     */
    fun onFirstFrame(frame: ByteArray, contacts: Map<String, String>, allow: (String) -> Boolean = { true }): First {
        crypto.sealedOpen(frame, myIdPubHex, myIdSecHex)?.let { opened ->
            val inner = FramePad.unpad(opened) ?: return First.Drop("knock padding malformed")
            val f = codec.unwrap(inner) ?: return First.Drop("knock header malformed")
            if (f.version != WIRE_VERSION) return First.VersionMismatch
            if (f.type != FrameType.KNOCK) return First.Drop("anonymous frame is not a knock")
            return First.Knock(f.body)
        }
        for ((cmId, pubHex) in contacts) {
            val opened = crypto.boxOpen(frame, pubHex, myIdSecHex) ?: continue
            if (!allow(cmId)) return First.Drop("per-contact rate limit")
            val inner = FramePad.unpad(opened) ?: return First.Drop("padding malformed")
            val f = codec.unwrap(inner) ?: return First.Drop("header malformed")
            if (f.version != WIRE_VERSION) return First.VersionMismatch
            if (f.type != FrameType.PREKEY_REQ || f.body.size != CHALLENGE) {
                return First.Drop("expected a prekey request")
            }
            if (!replay.check("$cmId:${f.sidHex}", f.seq)) return First.Drop("replayed prekey request")
            return First.Handshake(Server(cmId, pubHex, f.body))
        }
        return First.Drop("not from a known contact")
    }

    sealed interface Opened {
        class Delivered(val type: FrameType, val body: ByteArray) : Opened
        object VersionMismatch : Opened
        class Drop(val reason: String) : Opened
    }

    /**
     * The recipient half of ONE connection. Holds this connection's one-time
     * prekey; [close] (always called — see SecureWire) wipes its secret. A newer
     * prekey issued to another connection never affects this one, so a message
     * sealed to a "just-rotated" prekey still opens here.
     */
    inner class Server internal constructor(
        val cmId: String,
        private val peerIdPubHex: String,
        challenge: ByteArray,
    ) : Closeable {

        private val prekeyId = crypto.randomBytes(Fs.PKID)
        private val prekeyPub: ByteArray
        private val prekeySec: ByteArray
        private var used = false

        init {
            val (pub, sec) = crypto.x25519Keypair()
            prekeyPub = pub
            prekeySec = sec
        }

        /** Step 2: this connection's one-time prekey, authenticated by my identity key. */
        val response: ByteArray = run {
            val inner = codec.wrap(FrameType.PREKEY_RESP, prekeyId + prekeyPub + challenge, peerIdPubHex)
            val plain = FramePad.pad(inner)
            try { crypto.boxSeal(plain, peerIdPubHex, myIdSecHex) } finally { inner.fill(0); plain.fill(0) }
        }

        /** Step 3: open the forward-secret frame. ONE attempt: the prekey is wiped
         * afterwards whatever happens, so it can't be replayed or probed. */
        fun open(frame: ByteArray): Opened {
            if (used) return Opened.Drop("one-time prekey already used")
            used = true
            try {
                val p = Fs.parse(frame) ?: return Opened.Drop("malformed forward-secret frame")
                if (!MessageDigest.isEqual(p.prekeyId, prekeyId)) {
                    return Opened.Drop("frame is not for this connection's prekey")
                }
                val mySec = crypto.hexBytes(myIdSecHex)
                val plain: ByteArray?
                try {
                    plain = Fs.open(
                        crypto, p, mySec, crypto.hexBytes(myIdPubHex), crypto.hexBytes(peerIdPubHex),
                        prekeySec, prekeyPub, prekeyId,
                    )
                } finally {
                    mySec.fill(0)
                }
                if (plain == null) return Opened.Drop("failed authentication (tampered, or wrong key)")
                val inner = FramePad.unpad(plain)
                plain.fill(0)
                if (inner == null) return Opened.Drop("padding malformed")
                val f = codec.unwrap(inner)
                inner.fill(0)
                if (f == null) return Opened.Drop("header malformed")
                if (f.version != WIRE_VERSION) return Opened.VersionMismatch
                val type = f.type
                if (type == null || type !in CONTENT_TYPES) return Opened.Drop("unexpected frame type")
                if (!replay.check("$cmId:${f.sidHex}", f.seq)) return Opened.Drop("replayed frame")
                return Opened.Delivered(type, f.body)
            } finally {
                close()
            }
        }

        /** Wipe this connection's prekey secret. Idempotent. */
        override fun close() { prekeySec.fill(0) }

        internal val prekeyWiped: Boolean get() = prekeySec.all { it == 0.toByte() }
    }
}

/**
 * Runs [SecureChannel] over a pair of streams. MessageService uses it on real
 * Tor sockets; the loopback tests use it on local sockets — same code path.
 */
object SecureWire {

    /** The peer never completed the handshake. The caller shows the send as failed. */
    class HandshakeFailed(message: String) : IOException(message)

    /**
     * Sender: fetch + verify a one-time prekey, then send ONE forward-secret
     * frame. Throws on any failure (the caller marks the message OFFLINE so it
     * never fails silently). [onVersionMismatch] fires if the peer answers with a
     * different wire version.
     */
    fun send(
        ch: SecureChannel, input: InputStream, output: OutputStream,
        peerIdPubHex: String, type: FrameType, payload: ByteArray,
        onStage: (String) -> Unit = {},
        onVersionMismatch: () -> Unit = {},
    ) {
        val client = ch.Client(peerIdPubHex)
        Transport.writeFrame(output, client.request)
        onStage("prekey requested")
        val reply = Transport.readFrame(input)
            ?: throw HandshakeFailed(
                "no prekey reply — contact is busy, not accepting us, or on an older app version")
        val prekey = when (val v = client.verify(reply)) {
            is SecureChannel.Verdict.Ok -> v.prekey
            SecureChannel.Verdict.VersionMismatch -> {
                onVersionMismatch()
                throw HandshakeFailed("contact runs a different app version — update both apps")
            }
            is SecureChannel.Verdict.Rejected -> throw HandshakeFailed("prekey rejected: ${v.reason}")
        }
        onStage("one-time prekey verified against the contact's identity key")
        val frame = client.seal(prekey, type, payload)
        // Never send a frame the receiver's length cap would silently discard.
        if (frame.size > Transport.MAX_FRAME_BYTES) throw HandshakeFailed("message too large")
        Transport.writeFrame(output, frame)
        onStage("forward-secret frame sent (${frame.size}b)")
    }

    sealed interface Received {
        class Knock(val body: ByteArray) : Received
        class Message(val cmId: String, val type: FrameType, val body: ByteArray) : Received
        object VersionMismatch : Received
        class Dropped(val reason: String) : Received
    }

    /**
     * Recipient: handle ONE incoming connection. Never throws for bad input; the
     * one-time prekey is wiped on every path (including a sender that vanishes).
     */
    fun receive(
        ch: SecureChannel, input: InputStream, output: OutputStream,
        contacts: Map<String, String>,
        allow: (String) -> Boolean = { true },
        onStage: (String) -> Unit = {},
    ): Received {
        val first = Transport.readFrame(input) ?: return Received.Dropped("no readable frame")
        return when (val f = ch.onFirstFrame(first, contacts, allow)) {
            is SecureChannel.First.Knock -> Received.Knock(f.body)
            SecureChannel.First.VersionMismatch -> Received.VersionMismatch
            is SecureChannel.First.Drop -> Received.Dropped(f.reason)
            is SecureChannel.First.Handshake -> f.server.use { server ->
                try {
                    Transport.writeFrame(output, server.response)
                } catch (_: IOException) {
                    return Received.Dropped("connection lost before the prekey was sent")
                }
                onStage("one-time prekey served")
                val second = Transport.readFrame(input)
                    ?: return Received.Dropped("no frame after the prekey (sender gave up)")
                when (val o = server.open(second)) {
                    is SecureChannel.Opened.Delivered -> Received.Message(server.cmId, o.type, o.body)
                    SecureChannel.Opened.VersionMismatch -> Received.VersionMismatch
                    is SecureChannel.Opened.Drop -> Received.Dropped(o.reason)
                }
            }
        }
    }
}

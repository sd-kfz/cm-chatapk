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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong


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


class SecureChannel(
    private val crypto: CryptoManager,
    private val myIdPubHex: String,
    private val myIdSecHex: String,
    private val codec: InnerCodec,
    private val replay: ReplayGuard,
) {

    companion object {

        const val WIRE_VERSION = 6
        const val CHALLENGE = 16
        private const val RESP_BODY = Fs.PKID + Fs.KEY + CHALLENGE

        private const val RECEIPT_BODY = CHALLENGE + 1


        val CONTENT_TYPES: Set<FrameType> = setOf(
            FrameType.MSG, FrameType.ERASE_CHAT, FrameType.BUZZ, FrameType.ADDR_UPDATE,
            FrameType.COVER, FrameType.KNOCK_ACCEPT, FrameType.DECOY_ALERT, FrameType.TEAM_CLOCK,
            FrameType.TERMINATE, FrameType.NICKNAME, FrameType.FILE_OFFER,
        )
    }




    fun sealKnock(payload: ByteArray, recipientIdPubHex: String): ByteArray {
        val inner = codec.wrap(FrameType.KNOCK, payload, recipientIdPubHex)
        val plain = FramePad.pad(inner)
        try {
            return crypto.sealedSeal(plain, recipientIdPubHex)
        } finally {
            inner.fill(0); plain.fill(0)
        }
    }


    fun knockReceipt(nonce: ByteArray, status: Ack, knockerIdPubHex: String): ByteArray {
        require(nonce.size == CHALLENGE) { "bad knock nonce" }
        return sealReceipt(nonce, status, knockerIdPubHex)
    }


    fun openKnockReceipt(frame: ByteArray, nonce: ByteArray, recipientIdPubHex: String): Ack? =
        openReceipt(frame, nonce, recipientIdPubHex)




    fun sealFileChunk(key: ByteArray, fileId: ByteArray, index: Int, total: Int, data: ByteArray): ByteArray {
        require(data.size <= FileTransfer.CHUNK) { "chunk too large" }
        val plain = if (data.size == FileTransfer.CHUNK) data
            else data + crypto.randomBytes(FileTransfer.CHUNK - data.size)
        try {
            return crypto.aeadSeal(plain, key, FileTransfer.nonce(index), FileTransfer.aad(fileId, index, total))
        } finally {
            if (plain !== data) plain.fill(0)
        }
    }


    fun openFileChunk(key: ByteArray, fileId: ByteArray, index: Int, total: Int, frame: ByteArray): ByteArray? =
        if (frame.size != FileTransfer.SEALED_CHUNK) null
        else crypto.aeadOpen(frame, key, FileTransfer.nonce(index), FileTransfer.aad(fileId, index, total))


    fun fileReceipt(fileId: ByteArray, status: Ack, peerIdPubHex: String): ByteArray {
        require(fileId.size == CHALLENGE) { "bad file id" }
        return sealReceipt(fileId, status, peerIdPubHex)
    }

    fun openFileReceipt(frame: ByteArray, fileId: ByteArray, peerIdPubHex: String): Ack? =
        openReceipt(frame, fileId, peerIdPubHex)




    private fun sealReceipt(echo: ByteArray, status: Ack, peerIdPubHex: String): ByteArray {
        val inner = codec.wrap(FrameType.ACK, echo + byteArrayOf(status.code.toByte()), "$peerIdPubHex#receipt")
        val plain = FramePad.pad(inner)
        try { return crypto.boxSeal(plain, peerIdPubHex, myIdSecHex) } finally { inner.fill(0); plain.fill(0) }
    }


    private fun openReceipt(frame: ByteArray, echo: ByteArray, peerIdPubHex: String): Ack? {
        val opened = crypto.boxOpen(frame, peerIdPubHex, myIdSecHex) ?: return null
        val inner = FramePad.unpad(opened) ?: return null
        val f = codec.unwrap(inner) ?: return null
        if (f.version != WIRE_VERSION || f.type != FrameType.ACK || f.body.size != RECEIPT_BODY) return null
        if (!MessageDigest.isEqual(f.body.copyOfRange(0, CHALLENGE), echo)) return null
        return Ack.of(f.body[CHALLENGE].toInt() and 0xff)
    }




    class Prekey internal constructor(val id: ByteArray, val pub: ByteArray)

    sealed interface Verdict {
        class Ok(val prekey: Prekey) : Verdict
        object VersionMismatch : Verdict
        class Rejected(val reason: String) : Verdict
    }


    inner class Client(private val peerIdPubHex: String) {

        private val challenge = crypto.randomBytes(CHALLENGE)


        val request: ByteArray = run {
            val inner = codec.wrap(FrameType.PREKEY_REQ, challenge, peerIdPubHex)
            val plain = FramePad.pad(inner)
            try { crypto.boxSeal(plain, peerIdPubHex, myIdSecHex) } finally { inner.fill(0); plain.fill(0) }
        }


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


        fun verifyReceipt(frame: ByteArray): Ack? = openReceipt(frame, challenge, peerIdPubHex)


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



    sealed interface First {
        class Knock(val body: ByteArray) : First
        class Handshake(val server: Server) : First
        object VersionMismatch : First
        class Drop(val reason: String) : First
    }


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


    inner class Server internal constructor(
        val cmId: String,
        private val peerIdPubHex: String,
        private val challenge: ByteArray,
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


        val response: ByteArray = run {
            val inner = codec.wrap(FrameType.PREKEY_RESP, prekeyId + prekeyPub + challenge, peerIdPubHex)
            val plain = FramePad.pad(inner)
            try { crypto.boxSeal(plain, peerIdPubHex, myIdSecHex) } finally { inner.fill(0); plain.fill(0) }
        }


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


        fun receipt(status: Ack): ByteArray = sealReceipt(challenge, status, peerIdPubHex)


        override fun close() { prekeySec.fill(0) }

        internal val prekeyWiped: Boolean get() = prekeySec.all { it == 0.toByte() }
    }
}


object SecureWire {


    class HandshakeFailed(message: String) : IOException(message)


    fun send(
        ch: SecureChannel, input: InputStream, output: OutputStream,
        peerIdPubHex: String, type: FrameType, payload: ByteArray,
        onStage: (String) -> Unit = {},
        onVersionMismatch: () -> Unit = {},
    ): Ack {
        val (client, prekey) = handshake(ch, input, output, peerIdPubHex, onStage, onVersionMismatch)
        val frame = client.seal(prekey, type, payload)

        if (frame.size > Transport.MAX_FRAME_BYTES) throw HandshakeFailed("message too large")
        Transport.writeFrame(output, frame)
        onStage("forward-secret frame sent (${frame.size}b)")


        val r = Transport.readFrame(input)
            ?: throw HandshakeFailed("no receipt — not confirmed stored, will retry")
        val ack = client.verifyReceipt(r) ?: throw HandshakeFailed("receipt not authenticated")
        onStage("receipt: ${ack.name}")
        return ack
    }


    fun sendFile(
        ch: SecureChannel, input: InputStream, output: OutputStream,
        peerIdPubHex: String, offer: ByteArray, fileId: ByteArray, key: ByteArray, chunks: List<ByteArray>,
        onStage: (String) -> Unit = {},
        onVersionMismatch: () -> Unit = {},
        stillWanted: () -> Boolean = { true },
    ): Ack {
        val (client, prekey) = handshake(ch, input, output, peerIdPubHex, onStage, onVersionMismatch)
        Transport.writeFrame(output, client.seal(prekey, FrameType.FILE_OFFER, offer))
        onStage("file offer sent (${chunks.size} piece(s))")
        val r = Transport.readFrame(input) ?: throw HandshakeFailed("no answer to the file offer — will retry")
        val go = client.verifyReceipt(r) ?: throw HandshakeFailed("receipt not authenticated")
        if (go == Ack.HAVE) { onStage("file already on their phone"); return Ack.OK }
        if (go != Ack.OK) { onStage("file offer answered: ${go.name}"); return go }
        chunks.forEachIndexed { i, c ->
            if (!stillWanted()) throw HandshakeFailed("file cancelled (erased) — stopped")
            Transport.writeFrame(output, ch.sealFileChunk(key, fileId, i, chunks.size, c))
        }
        onStage("all ${chunks.size} piece(s) sent")
        val fin = Transport.readFrame(input)
            ?: throw HandshakeFailed("no receipt after the file — not confirmed stored, will retry")
        val ack = ch.openFileReceipt(fin, fileId, peerIdPubHex) ?: throw HandshakeFailed("file receipt not authenticated")
        onStage("file receipt: ${ack.name}")
        return ack
    }


    fun readFileChunks(
        ch: SecureChannel, input: InputStream, key: ByteArray, fileId: ByteArray, size: Long, total: Int,
        deadlineMs: Long,
    ): List<ByteArray>? {
        val out = ArrayList<ByteArray>(total)
        fun fail(): List<ByteArray>? { out.forEach { it.fill(0) }; return null }
        for (i in 0 until total) {
            if (System.currentTimeMillis() > deadlineMs) return fail()
            val f = Transport.readFrame(input) ?: return fail()
            val p = ch.openFileChunk(key, fileId, i, total, f) ?: return fail()
            val keep = if (i == total - 1) (size - FileTransfer.CHUNK.toLong() * (total - 1)).toInt() else FileTransfer.CHUNK
            out += if (keep == FileTransfer.CHUNK) p else p.copyOf(keep).also { p.fill(0) }
        }
        return out
    }


    private fun handshake(
        ch: SecureChannel, input: InputStream, output: OutputStream, peerIdPubHex: String,
        onStage: (String) -> Unit, onVersionMismatch: () -> Unit,
    ): Pair<SecureChannel.Client, SecureChannel.Prekey> {
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
        return client to prekey
    }


    fun sendKnock(
        ch: SecureChannel, input: InputStream, output: OutputStream,
        sealed: ByteArray, nonce: ByteArray, recipientIdPubHex: String,
        onStage: (String) -> Unit = {},
    ): Ack {
        Transport.writeFrame(output, sealed)
        onStage("knock sent (anonymous sealed box, ${sealed.size}b)")
        val r = Transport.readFrame(input)
            ?: throw HandshakeFailed("no receipt for the knock — not confirmed stored, will retry")
        val ack = ch.openKnockReceipt(r, nonce, recipientIdPubHex)
            ?: throw HandshakeFailed("knock receipt not authenticated")
        onStage("knock receipt: ${ack.name}")
        return ack
    }

    sealed interface Received {

        class Knock(val body: ByteArray, private val ch: SecureChannel, private val output: OutputStream) : Received {
            private val replied = AtomicBoolean(false)
            fun reply(nonce: ByteArray, knockerIdPubHex: String, ack: Ack): Boolean {
                if (!replied.compareAndSet(false, true)) return false
                return runCatching { Transport.writeFrame(output, ch.knockReceipt(nonce, ack, knockerIdPubHex)) }.isSuccess
            }
        }


        class Message(
            val cmId: String, val type: FrameType, val body: ByteArray,
            private val receipt: (Ack) -> ByteArray, private val output: OutputStream,
        ) : Received {
            private val replied = AtomicBoolean(false)
            fun reply(ack: Ack): Boolean {
                if (!replied.compareAndSet(false, true)) return false
                return runCatching { Transport.writeFrame(output, receipt(ack)) }.isSuccess
            }
        }
        object VersionMismatch : Received
        class Dropped(val reason: String) : Received
    }


    fun receive(
        ch: SecureChannel, input: InputStream, output: OutputStream,
        contacts: Map<String, String>,
        allow: (String) -> Boolean = { true },
        onStage: (String) -> Unit = {},
    ): Received {
        val first = Transport.readFrame(input) ?: return Received.Dropped("no readable frame")
        return when (val f = ch.onFirstFrame(first, contacts, allow)) {
            is SecureChannel.First.Knock -> Received.Knock(f.body, ch, output)
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
                    is SecureChannel.Opened.Delivered ->
                        Received.Message(server.cmId, o.type, o.body, server::receipt, output)
                    SecureChannel.Opened.VersionMismatch -> Received.VersionMismatch
                    is SecureChannel.Opened.Drop -> Received.Dropped(o.reason)
                }
            }
        }
    }
}

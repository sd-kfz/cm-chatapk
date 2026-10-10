package org.cmchat.app

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.cmchat.app.crypto.CryptoManager
import org.cmchat.app.transport.Ack
import org.cmchat.app.crypto.Fs
import org.cmchat.app.transport.FramePad
import org.cmchat.app.transport.FrameType
import org.cmchat.app.transport.InnerCodec
import org.cmchat.app.transport.Messages
import org.cmchat.app.transport.ReplayGuard
import org.cmchat.app.transport.SecureChannel
import org.cmchat.app.transport.SecureWire
import org.cmchat.app.transport.TextPayload
import org.cmchat.app.transport.Transport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The v3 handshake over REAL TCP sockets on 127.0.0.1, running the exact
 * SecureWire.send / SecureWire.receive that MessageService runs over Tor. Only
 * Tor itself (unchanged from v2) is left out.
 */
class SecureWireTest {

    private val crypto = CryptoManager(LazySodiumJava(SodiumJava()))

    private inner class Device(val cmId: String) {
        val idPub: String
        val idSec: String
        init { val (p, s) = crypto.newIdentityKeypair(); idPub = p; idSec = s }
        val ch = SecureChannel(crypto, idPub, idSec, InnerCodec(), ReplayGuard())
    }

    private val alice = Device("alice")
    private val bob = Device("bob")

    /** Bob serves ONE connection with [serve]; Alice runs [client]. Returns Bob's result. */
    private fun <T> loopback(serve: (Socket) -> T, client: (Socket) -> Unit): T? {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            val out = AtomicReference<T?>()
            val t = thread { server.accept().use { s -> s.soTimeout = 5_000; out.set(serve(s)) } }
            try {
                Socket().use { c ->
                    c.connect(InetSocketAddress("127.0.0.1", server.localPort), 2_000)
                    c.soTimeout = 2_000
                    client(c)
                }
            } finally {
                t.join(8_000)
            }
            return out.get()
        }
    }

    /** Bob receives one frame and answers with [receipt] (null = stores nothing, no receipt). */
    private fun bobReceives(s: Socket, receipt: Ack? = Ack.OK) =
        SecureWire.receive(bob.ch, s.getInputStream(), s.getOutputStream(), mapOf(alice.cmId to alice.idPub))
            .also { r -> if (receipt != null) (r as? SecureWire.Received.Message)?.reply(receipt) }

    private fun bobReceives(s: Socket) = bobReceives(s, Ack.OK)

    private fun aliceSends(c: Socket, payload: ByteArray, onMismatch: () -> Unit = {}) =
        SecureWire.send(alice.ch, c.getInputStream(), c.getOutputStream(), bob.idPub, FrameType.MSG, payload,
            onVersionMismatch = onMismatch)

    @Test
    fun delivers_a_message_over_real_loopback_sockets() {
        val payload = "hello over the wire".toByteArray()
        var ack: Ack? = null
        val r = loopback(::bobReceives) { ack = aliceSends(it, payload) } as SecureWire.Received.Message
        assertEquals("alice", r.cmId)
        assertEquals(FrameType.MSG, r.type)
        assertArrayEquals(payload, r.body)
        assertEquals("the sender learns it was STORED", Ack.OK, ack)
    }

    // ---- receipts (v6): "delivered" only when the receiver says stored ----------

    @Test
    fun a_receiver_that_stores_nothing_never_counts_as_delivered() {
        // It opened the frame but sent no receipt (crashed, dropped it, closed).
        val e = assertThrows(SecureWire.HandshakeFailed::class.java) {
            loopback({ bobReceives(it, receipt = null) }) { aliceSends(it, "x".toByteArray()) }
        }
        assertTrue(e.message!!.contains("no receipt"))
    }

    @Test
    fun retry_and_refusal_come_back_to_the_sender() {
        var ack: Ack? = null
        loopback({ bobReceives(it, Ack.RETRY) }) { ack = aliceSends(it, "x".toByteArray()) }
        assertEquals(Ack.RETRY, ack)
        loopback({ bobReceives(it, Ack.REJECTED) }) { ack = aliceSends(it, "x".toByteArray()) }
        assertEquals(Ack.REJECTED, ack)
    }

    @Test
    fun a_receipt_from_anyone_else_is_rejected() {
        // Mallory (or Bob's onion key without Bob's identity) answers with a
        // well-formed receipt made with another identity key.
        val mallory = Device("mallory")
        val e = assertThrows(SecureWire.HandshakeFailed::class.java) {
            loopback({ s ->
                val r = SecureWire.receive(bob.ch, s.getInputStream(), s.getOutputStream(), mapOf(alice.cmId to alice.idPub))
                    as SecureWire.Received.Message
                // A receipt sealed by Mallory for Alice, echoing nothing Alice asked.
                val fake = mallory.ch.knockReceipt(ByteArray(16), Ack.OK, alice.idPub)
                Transport.writeFrame(s.getOutputStream(), fake)
                r
            }) { aliceSends(it, "x".toByteArray()) }
        }
        assertTrue(e.message!!.contains("receipt not authenticated"))
    }

    @Test
    fun a_receipt_with_the_right_challenge_but_another_key_is_rejected() {
        val client = alice.ch.Client(bob.idPub)
        // Whoever sees the challenge (it's inside Bob's box, but say they did)…
        val challenge = InnerCodec().unwrap(FramePad.unpad(crypto.boxOpen(client.request, alice.idPub, bob.idSec)!!)!!)!!.body
        val mallory = Device("mallory")
        assertEquals(null, client.verifyReceipt(mallory.ch.knockReceipt(challenge, Ack.OK, alice.idPub)))
        // …only Bob's identity key makes a receipt Alice accepts.
        val srv = (bob.ch.onFirstFrame(client.request, mapOf(alice.cmId to alice.idPub)) as SecureChannel.First.Handshake).server
        assertEquals(Ack.OK, client.verifyReceipt(srv.receipt(Ack.OK)))
    }

    @Test
    fun an_old_receipt_cannot_confirm_a_new_frame() {
        // Capture Bob's genuine receipt for connection #1 …
        val client1 = alice.ch.Client(bob.idPub)
        val srv1 = (bob.ch.onFirstFrame(client1.request, mapOf(alice.cmId to alice.idPub))
            as SecureChannel.First.Handshake).server
        val receipt1 = srv1.receipt(Ack.OK)
        assertEquals(Ack.OK, client1.verifyReceipt(receipt1))
        // … it is worthless for connection #2 (a different challenge).
        val client2 = alice.ch.Client(bob.idPub)
        assertEquals(null, client2.verifyReceipt(receipt1))
    }

    @Test
    fun a_knock_gets_a_receipt_only_the_real_recipient_can_make() {
        val nonce = crypto.randomBytes(16)
        val good = bob.ch.knockReceipt(nonce, Ack.OK, alice.idPub)
        assertEquals(Ack.OK, alice.ch.openKnockReceipt(good, nonce, bob.idPub))
        // Wrong nonce (an old knock), or made by someone else: rejected.
        assertEquals(null, alice.ch.openKnockReceipt(good, crypto.randomBytes(16), bob.idPub))
        val mallory = Device("mallory")
        val forged = mallory.ch.knockReceipt(nonce, Ack.OK, alice.idPub)
        assertEquals(null, alice.ch.openKnockReceipt(forged, nonce, bob.idPub))
    }

    @Test
    fun the_largest_possible_text_message_still_fits_and_delivers() {
        // 10,000 control chars → each JSON-escaped to 6 bytes: the worst case for
        // the 10,000-char body cap. Must stay under the 64 KiB wire cap after the
        // forward-secret overhead, or the receiver would silently drop it.
        val text = "\u0001".repeat(10_000)
        val payload = Messages.json.encodeToString(TextPayload.serializer(), TextPayload("id", text, "off"))
            .toByteArray()
        val r = loopback(::bobReceives) { aliceSends(it, payload) } as SecureWire.Received.Message
        assertArrayEquals(payload, r.body)
    }

    @Test
    fun a_peer_that_closes_without_replying_fails_the_send_cleanly() {
        // What an OLD (v2) build does: read our request, refuse it, close.
        assertThrows(SecureWire.HandshakeFailed::class.java) {
            loopback({ s -> Transport.readFrame(s.getInputStream()) }) { aliceSends(it, "x".toByteArray()) }
        }
    }

    @Test
    fun a_silent_peer_times_out_instead_of_hanging() {
        val t0 = System.currentTimeMillis()
        assertThrows(SecureWire.HandshakeFailed::class.java) {
            loopback({ Thread.sleep(3_000) }) { c -> c.soTimeout = 500; aliceSends(c, "x".toByteArray()) }
        }
        assertTrue("timed out promptly", System.currentTimeMillis() - t0 < 4_500)
    }

    @Test
    fun a_newer_version_reply_raises_update_both_apps() {
        var flagged = false
        assertThrows(SecureWire.HandshakeFailed::class.java) {
            loopback({ s ->
                val req = Transport.readFrame(s.getInputStream())!!
                val opened = crypto.boxOpen(req, alice.idPub, bob.idSec)!!
                val challenge = InnerCodec().unwrap(FramePad.unpad(opened)!!)!!.body
                val body = crypto.randomBytes(Fs.PKID) + crypto.x25519Keypair().first + challenge
                val v4 = crypto.boxSeal(FramePad.pad(InnerCodec().wrap(FrameType.PREKEY_RESP, body, alice.idPub, SecureChannel.WIRE_VERSION + 1)),
                    alice.idPub, bob.idSec)
                Transport.writeFrame(s.getOutputStream(), v4)
            }) { aliceSends(it, "x".toByteArray()) { flagged = true } }
        }
        assertTrue("the version-mismatch banner callback fired", flagged)
    }

    @Test
    fun the_receiver_survives_a_sender_that_vanishes_after_the_prekey() {
        val r = loopback(::bobReceives) { c ->
            val client = alice.ch.Client(bob.idPub)
            Transport.writeFrame(c.getOutputStream(), client.request)
            Transport.readFrame(c.getInputStream())         // take the prekey, then disappear
        }
        assertTrue("dropped cleanly, no hang, no throw", r is SecureWire.Received.Dropped)
    }
}

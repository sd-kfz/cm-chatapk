package org.cmchat.app

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.cmchat.app.crypto.CryptoManager
import org.cmchat.app.crypto.Fs
import org.cmchat.app.transport.FramePad
import org.cmchat.app.transport.FrameType
import org.cmchat.app.transport.InnerCodec
import org.cmchat.app.transport.ReplayGuard
import org.cmchat.app.transport.SecureChannel
import org.cmchat.app.transport.SecureChannel.First
import org.cmchat.app.transport.SecureChannel.Opened
import org.cmchat.app.transport.SecureChannel.Verdict
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loopback proofs of the v3 forward-secret protocol, run on the REAL libsodium
 * (lazysodium-java) against the exact production code in SecureChannel / Fs —
 * only the sockets are left out (SecureWireTest covers those).
 */
class ForwardSecrecyTest {

    private val crypto = CryptoManager(LazySodiumJava(SodiumJava()))

    /** A device: its long-term identity keypair + its own channel state. */
    private inner class Device(val cmId: String) {
        val idPub: String
        val idSec: String
        init { val (p, s) = crypto.newIdentityKeypair(); idPub = p; idSec = s }
        val ch = SecureChannel(crypto, idPub, idSec, InnerCodec(), ReplayGuard())
        fun pubBytes() = crypto.hexBytes(idPub)
        fun secBytes() = crypto.hexBytes(idSec)
    }

    private val alice = Device("alice")
    private val bob = Device("bob")
    private val carol = Device("carol")
    /** Bob's Circle: Alice and Carol are contacts. */
    private val bobContacts = mapOf(alice.cmId to alice.idPub, carol.cmId to carol.idPub)

    /** The three frames that crossed the wire, plus Bob's side of the connection. */
    private class Exchange(val req: ByteArray, val resp: ByteArray, val msg: ByteArray,
                           val result: Opened, val server: SecureChannel.Server)

    private fun handshake(from: Device, to: Device, contacts: Map<String, String>):
        Triple<SecureChannel.Client, SecureChannel.Server, SecureChannel.Prekey> {
        val client = from.ch.Client(to.idPub)
        val first = to.ch.onFirstFrame(client.request, contacts)
        val server = (first as? First.Handshake)?.server ?: throw AssertionError("expected a handshake, got $first")
        val verdict = client.verify(server.response)
        val prekey = (verdict as? Verdict.Ok)?.prekey ?: throw AssertionError("prekey not accepted: $verdict")
        return Triple(client, server, prekey)
    }

    private fun exchange(from: Device, to: Device, type: FrameType, payload: ByteArray): Exchange {
        val (client, server, prekey) = handshake(from, to, bobContacts)
        val msg = client.seal(prekey, type, payload)
        return Exchange(client.request, server.response, msg, server.open(msg), server)
    }

    private fun delivered(o: Opened): Opened.Delivered =
        o as? Opened.Delivered ?: throw AssertionError("expected delivery, got ${(o as? Opened.Drop)?.reason ?: o}")

    /** Build a prekey reply by hand (to forge, replay or version-shift one). */
    private fun craftReply(sealerSecHex: String, toPubHex: String, body: ByteArray, version: Int = 3) =
        crypto.boxSeal(FramePad.pad(InnerCodec().wrap(FrameType.PREKEY_RESP, body, toPubHex, version)),
            toPubHex, sealerSecHex)

    /** What Bob reads inside a request (lets a test act as a forger who somehow knows it). */
    private fun challengeOf(request: ByteArray, from: Device, to: Device): ByteArray {
        val opened = crypto.boxOpen(request, from.idPub, to.idSec)!!
        return InnerCodec().unwrap(FramePad.unpad(opened)!!)!!.body
    }

    // ---- delivery -----------------------------------------------------------

    @Test
    fun full_handshake_delivers_every_content_type() {
        for (type in SecureChannel.CONTENT_TYPES) {
            val body = "payload for $type".toByteArray()
            val ex = exchange(alice, bob, type, body)
            val d = delivered(ex.result)
            assertEquals(type, d.type)
            assertArrayEquals(body, d.body)
            assertEquals("Bob identifies the sender from the request", "alice", ex.server.cmId)
        }
    }

    @Test
    fun knock_still_round_trips_as_an_anonymous_sealed_box() {
        val sealed = alice.ch.sealKnock("hello".toByteArray(), bob.idPub)
        val r = bob.ch.onFirstFrame(sealed, emptyMap())
        assertArrayEquals("hello".toByteArray(), (r as First.Knock).body)
    }

    // ---- 1) stolen identity keys do not decrypt captured traffic -------------

    @Test
    fun stealing_both_identity_keys_does_not_decrypt_previously_captured_messages() {
        val secret = "meet at the usual place".toByteArray()
        val ex = exchange(alice, bob, FrameType.MSG, secret)
        assertArrayEquals(secret, delivered(ex.result).body)
        // Once the message is opened, Bob's one-time prekey is gone (and Alice's
        // ephemeral was wiped inside Fs.seal — see the wipe test below).
        assertTrue(ex.server.prekeyWiped)

        // LATER: an attacker who recorded all three frames steals BOTH identity secrets.
        // They can open the two handshake frames — which hold only a random
        // challenge and a PUBLIC prekey…
        val reply = crypto.boxOpen(ex.resp, bob.idPub, alice.idSec)!!
        val body = InnerCodec().unwrap(FramePad.unpad(reply)!!)!!.body
        val pkId = body.copyOfRange(0, Fs.PKID)
        val pkPub = body.copyOfRange(Fs.PKID, Fs.PKID + Fs.KEY)
        // …but the content frame is not sealed under the identity keys at all…
        assertNull(crypto.boxOpen(ex.msg, alice.idPub, bob.idSec))
        // …and no secret they hold can stand in for the wiped prekey secret.
        val p = Fs.parse(ex.msg)!!
        // Strongest attacker model: with both identity secrets they CAN compute
        // DH2 = X25519(A_id, prekey) and DH3 = X25519(B_id, eph) exactly. Only DH1
        // (eph × prekey) protects the message — try every DH1 they could compute.
        val dh2 = crypto.x25519(alice.secBytes(), pkPub)!!
        val dh3 = crypto.x25519(bob.secBytes(), p.ephPub)!!
        val dh1Guesses = listOfNotNull(
            crypto.x25519(alice.secBytes(), p.ephPub), crypto.x25519(bob.secBytes(), pkPub),
            crypto.x25519(alice.secBytes(), pkPub), crypto.x25519(bob.secBytes(), p.ephPub),
            dh2, dh3, ByteArray(32),
        )
        for (dh1 in dh1Guesses) {
            val k = Fs.derive(crypto, dh1, dh2, dh3, alice.pubBytes(), bob.pubBytes(), p.ephPub, pkPub, pkId)!!
            assertNull(crypto.aeadOpen(p.cipher, k, p.nonce, p.ephPub + p.prekeyId))
        }
        for (guess in listOf(alice.secBytes(), bob.secBytes(), ByteArray(32), crypto.randomBytes(32))) {
            assertNull(Fs.open(crypto, p, bob.secBytes(), bob.pubBytes(), alice.pubBytes(), guess, pkPub, pkId))
        }
    }

    @Test
    fun the_wiped_prekey_is_exactly_what_protects_old_messages() {
        // Counterfactual at the crypto layer: WITH the prekey secret the frame opens,
        // with only identity secrets it doesn't — so wiping the prekey (and the
        // ephemeral) is what makes captured messages permanently unreadable.
        val (pkPub, pkSec) = crypto.x25519Keypair()
        val pkId = crypto.randomBytes(Fs.PKID)
        val frame = Fs.seal(crypto, "x".toByteArray(), alice.secBytes(), alice.pubBytes(), bob.pubBytes(), pkPub, pkId)!!
        val p = Fs.parse(frame)!!
        assertNull(Fs.open(crypto, p, bob.secBytes(), bob.pubBytes(), alice.pubBytes(), bob.secBytes(), pkPub, pkId))
        assertArrayEquals("x".toByteArray(),
            Fs.open(crypto, p, bob.secBytes(), bob.pubBytes(), alice.pubBytes(), pkSec, pkPub, pkId))
    }

    // ---- 2) a later message key cannot derive an earlier one ----------------

    @Test
    fun a_later_message_key_cannot_open_or_derive_an_earlier_message() {
        val a = alice; val b = bob
        // Two consecutive messages, each over its own handshake: its own one-time
        // prekey AND its own fresh ephemeral.
        val (pk1Pub, pk1Sec) = crypto.x25519Keypair(); val id1 = crypto.randomBytes(Fs.PKID)
        val (pk2Pub, pk2Sec) = crypto.x25519Keypair(); val id2 = crypto.randomBytes(Fs.PKID)
        val m1 = Fs.parse(Fs.seal(crypto, "first".toByteArray(), a.secBytes(), a.pubBytes(), b.pubBytes(), pk1Pub, id1)!!)!!
        val m2 = Fs.parse(Fs.seal(crypto, "second".toByteArray(), a.secBytes(), a.pubBytes(), b.pubBytes(), pk2Pub, id2)!!)!!
        assertFalse("each message has a fresh ephemeral", m1.ephPub.contentEquals(m2.ephPub))

        val k1 = Fs.recipientKey(crypto, b.secBytes(), b.pubBytes(), a.pubBytes(), pk1Sec, pk1Pub, id1, m1.ephPub)!!
        // The attacker steals the LATER message key.
        val k2 = Fs.recipientKey(crypto, b.secBytes(), b.pubBytes(), a.pubBytes(), pk2Sec, pk2Pub, id2, m2.ephPub)!!
        assertFalse(k1.contentEquals(k2))
        val aad1 = m1.ephPub + m1.prekeyId
        val aad2 = m2.ephPub + m2.prekeyId

        // It opens its own message…
        assertArrayEquals("second".toByteArray(), crypto.aeadOpen(m2.cipher, k2, m2.nonce, aad2))
        // …but neither it nor anything hashed from it (ratchet-style steps
        // included) opens the earlier message: no chain links the two keys.
        for (cand in listOf(k2, crypto.kdf32(k2), crypto.kdf32(k2 + byteArrayOf(1)), crypto.kdf32(k2 + byteArrayOf(2)))) {
            assertNull(crypto.aeadOpen(m1.cipher, cand, m1.nonce, aad1))
        }
        // Sanity: the right key does open it, so the failure above is the key.
        assertArrayEquals("first".toByteArray(), crypto.aeadOpen(m1.cipher, k1, m1.nonce, aad1))
    }

    // ---- 3) forged / mismatched prekeys are rejected -------------------------

    @Test
    fun a_prekey_forged_without_the_contacts_identity_key_is_rejected() {
        // Mallory has NO access to Bob's identity secret (she may hold a stolen
        // onion key and answer Bob's address). Even if she somehow knew the
        // challenge, she can't make a reply that opens under Bob's identity key.
        val mallory = Device("mallory")
        val client = alice.ch.Client(bob.idPub)
        val challenge = challengeOf(client.request, alice, bob)
        val (fakePub, _) = crypto.x25519Keypair()
        val forged = craftReply(mallory.idSec, alice.idPub, crypto.randomBytes(Fs.PKID) + fakePub + challenge)
        val v = client.verify(forged)
        assertTrue("forged prekey must be rejected, got $v", v is Verdict.Rejected)
    }

    @Test
    fun a_replayed_or_tampered_prekey_reply_is_rejected() {
        // A genuine reply from Bob, but to an EARLIER request (replayed): rejected.
        val (_, oldServer, _) = handshake(alice, bob, bobContacts)
        val laterClient = alice.ch.Client(bob.idPub)
        assertTrue(laterClient.verify(oldServer.response) is Verdict.Rejected)

        // A genuine reply with one byte flipped in transit: rejected.
        val client = alice.ch.Client(bob.idPub)
        val server = (bob.ch.onFirstFrame(client.request, bobContacts) as First.Handshake).server
        val tampered = server.response.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertTrue(client.verify(tampered) is Verdict.Rejected)
        // The untampered reply still verifies (so the rejection was the tamper).
        assertTrue(client.verify(server.response) is Verdict.Ok)
    }

    @Test
    fun a_wrong_shaped_reply_or_a_malformed_prekey_is_refused() {
        val client = alice.ch.Client(bob.idPub)
        val challenge = challengeOf(client.request, alice, bob)
        // Authenticated by Bob, but the wrong length: rejected.
        assertTrue(client.verify(craftReply(bob.idSec, alice.idPub, challenge)) is Verdict.Rejected)
        // Authenticated by Bob, well-formed, but carrying a low-order (all-zero)
        // prekey: the key agreement refuses it rather than produce a weak key.
        val v = client.verify(craftReply(bob.idSec, alice.idPub, crypto.randomBytes(Fs.PKID) + ByteArray(32) + challenge))
        val prekey = (v as Verdict.Ok).prekey
        assertThrows(java.io.IOException::class.java) { client.seal(prekey, FrameType.MSG, "x".toByteArray()) }
    }

    @Test
    fun a_request_from_someone_outside_the_circle_gets_no_prekey() {
        val mallory = Device("mallory")
        assertTrue(bob.ch.onFirstFrame(mallory.ch.Client(bob.idPub).request, bobContacts) is First.Drop)
    }

    // ---- 4) a message against the just-rotated prekey still decrypts ---------

    @Test
    fun a_message_sealed_to_a_just_rotated_prekey_still_decrypts() {
        // Alice fetches Bob's prekey P1…
        val (aClient, s1, p1) = handshake(alice, bob, bobContacts)
        // …then, before her message arrives, Bob rotates: Carol's request gets P2.
        val (cClient, s2, p2) = handshake(carol, bob, bobContacts)
        assertFalse("rotation issued a new prekey", p1.pub.contentEquals(p2.pub))
        val aMsg = aClient.seal(p1, FrameType.MSG, "sealed to the older prekey".toByteArray())
        val cMsg = cClient.seal(p2, FrameType.MSG, "sealed to the newer prekey".toByteArray())
        // Arrival order doesn't matter; each still opens with its own prekey.
        assertArrayEquals("sealed to the newer prekey".toByteArray(), delivered(s2.open(cMsg)).body)
        assertArrayEquals("sealed to the older prekey".toByteArray(), delivered(s1.open(aMsg)).body)
    }

    @Test
    fun a_frame_is_bound_to_its_own_connections_prekey() {
        val (aClient, _, p1) = handshake(alice, bob, bobContacts)
        val (_, sOther, _) = handshake(carol, bob, bobContacts)
        val aMsg = aClient.seal(p1, FrameType.MSG, "x".toByteArray())
        assertTrue(sOther.open(aMsg) is Opened.Drop)     // presented on the wrong connection
    }

    // ---- 5) tampering and replay ---------------------------------------------

    @Test
    fun a_tampered_frame_fails_authentication() {
        // Flip one bit in each region: ephemeral key, prekey id, nonce, ciphertext, tag.
        for (pos in listOf(0, Fs.KEY, Fs.KEY + Fs.PKID, Fs.HEADER + 2, -1)) {
            val (client, server, prekey) = handshake(alice, bob, bobContacts)
            val msg = client.seal(prekey, FrameType.MSG, "integrity".toByteArray())
            val i = if (pos < 0) msg.size - 1 else pos
            msg[i] = (msg[i].toInt() xor 0x01).toByte()
            assertTrue("bit flip at $i must be rejected", server.open(msg) is Opened.Drop)
            assertTrue("prekey wiped even after a failed open", server.prekeyWiped)
        }
    }

    @Test
    fun replayed_frames_are_rejected() {
        val ex = exchange(alice, bob, FrameType.MSG, "only once".toByteArray())
        delivered(ex.result)
        // (a) The content frame again on the SAME connection: its prekey is spent.
        assertTrue(ex.server.open(ex.msg) is Opened.Drop)
        // (b) The whole captured conversation on a NEW connection: the request is a
        //     replay (its sequence number was already seen inside the authenticated header)…
        assertTrue(bob.ch.onFirstFrame(ex.req, bobContacts) is First.Drop)
        // (c) …and even after a fresh, valid request, the old content frame is
        //     bound to the old (wiped) prekey.
        val (_, fresh, _) = handshake(alice, bob, bobContacts)
        assertTrue(fresh.open(ex.msg) is Opened.Drop)
    }

    // ---- 6) wire version ---------------------------------------------------

    @Test
    fun a_different_wire_version_is_reported_never_silently_dropped() {
        // An old v2 peer's frame (crypto_box from a contact, version byte 2).
        val v2 = crypto.boxSeal(
            FramePad.pad(InnerCodec().wrap(FrameType.MSG, "old build".toByteArray(), bob.idPub, version = 2)),
            bob.idPub, alice.idSec)
        assertSame(First.VersionMismatch, bob.ch.onFirstFrame(v2, bobContacts))
        // An old v2 knock.
        val v2Knock = crypto.sealedSeal(
            FramePad.pad(InnerCodec().wrap(FrameType.KNOCK, "{}".toByteArray(), bob.idPub, version = 2)), bob.idPub)
        assertSame(First.VersionMismatch, bob.ch.onFirstFrame(v2Knock, bobContacts))
        // A newer peer answering our request with version 4.
        val client = alice.ch.Client(bob.idPub)
        val challenge = challengeOf(client.request, alice, bob)
        val v4 = craftReply(bob.idSec, alice.idPub,
            crypto.randomBytes(Fs.PKID) + crypto.x25519Keypair().first + challenge, version = 4)
        assertSame(Verdict.VersionMismatch, client.verify(v4))
    }

    // ---- 7) secrets are wiped ------------------------------------------------

    @Test
    fun ephemeral_and_prekey_secrets_are_wiped_after_use() {
        val (pkPub, _) = crypto.x25519Keypair()
        // The sender's ephemeral private key is zeroed before Fs.seal returns…
        val eph = crypto.x25519Keypair()
        assertTrue(Fs.seal(crypto, "x".toByteArray(), alice.secBytes(), alice.pubBytes(), bob.pubBytes(),
            pkPub, crypto.randomBytes(Fs.PKID), ephemeral = eph) != null)
        assertTrue(eph.second.all { it == 0.toByte() })
        // …even when the key agreement is refused.
        val eph2 = crypto.x25519Keypair()
        assertNull(Fs.seal(crypto, "x".toByteArray(), alice.secBytes(), alice.pubBytes(), bob.pubBytes(),
            ByteArray(32), crypto.randomBytes(Fs.PKID), ephemeral = eph2))
        assertTrue(eph2.second.all { it == 0.toByte() })

        // The recipient's one-time prekey is wiped once the frame is opened…
        assertTrue(exchange(alice, bob, FrameType.MSG, "x".toByteArray()).server.prekeyWiped)
        // …and when the sender vanishes before sending (SecureWire always closes).
        val (_, idle, _) = handshake(alice, bob, bobContacts)
        assertFalse(idle.prekeyWiped)
        idle.close()
        assertTrue(idle.prekeyWiped)
    }

    @Test
    fun low_order_public_keys_are_refused_by_the_key_agreement() {
        val (_, sec) = crypto.x25519Keypair()
        assertNull(crypto.x25519(sec, ByteArray(32)))
    }
}

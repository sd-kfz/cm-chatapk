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


class ForwardSecrecyTest {

    private val crypto = CryptoManager(LazySodiumJava(SodiumJava()))


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

    private val bobContacts = mapOf(alice.cmId to alice.idPub, carol.cmId to carol.idPub)


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


    private fun craftReply(sealerSecHex: String, toPubHex: String, body: ByteArray, version: Int = SecureChannel.WIRE_VERSION) =
        crypto.boxSeal(FramePad.pad(InnerCodec().wrap(FrameType.PREKEY_RESP, body, toPubHex, version)),
            toPubHex, sealerSecHex)


    private fun challengeOf(request: ByteArray, from: Device, to: Device): ByteArray {
        val opened = crypto.boxOpen(request, from.idPub, to.idSec)!!
        return InnerCodec().unwrap(FramePad.unpad(opened)!!)!!.body
    }



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



    @Test
    fun stealing_both_identity_keys_does_not_decrypt_previously_captured_messages() {
        val secret = "meet at the usual place".toByteArray()
        val ex = exchange(alice, bob, FrameType.MSG, secret)
        assertArrayEquals(secret, delivered(ex.result).body)


        assertTrue(ex.server.prekeyWiped)




        val reply = crypto.boxOpen(ex.resp, bob.idPub, alice.idSec)!!
        val body = InnerCodec().unwrap(FramePad.unpad(reply)!!)!!.body
        val pkId = body.copyOfRange(0, Fs.PKID)
        val pkPub = body.copyOfRange(Fs.PKID, Fs.PKID + Fs.KEY)

        assertNull(crypto.boxOpen(ex.msg, alice.idPub, bob.idSec))

        val p = Fs.parse(ex.msg)!!



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



        val (pkPub, pkSec) = crypto.x25519Keypair()
        val pkId = crypto.randomBytes(Fs.PKID)
        val frame = Fs.seal(crypto, "x".toByteArray(), alice.secBytes(), alice.pubBytes(), bob.pubBytes(), pkPub, pkId)!!
        val p = Fs.parse(frame)!!
        assertNull(Fs.open(crypto, p, bob.secBytes(), bob.pubBytes(), alice.pubBytes(), bob.secBytes(), pkPub, pkId))
        assertArrayEquals("x".toByteArray(),
            Fs.open(crypto, p, bob.secBytes(), bob.pubBytes(), alice.pubBytes(), pkSec, pkPub, pkId))
    }



    @Test
    fun a_later_message_key_cannot_open_or_derive_an_earlier_message() {
        val a = alice; val b = bob


        val (pk1Pub, pk1Sec) = crypto.x25519Keypair(); val id1 = crypto.randomBytes(Fs.PKID)
        val (pk2Pub, pk2Sec) = crypto.x25519Keypair(); val id2 = crypto.randomBytes(Fs.PKID)
        val m1 = Fs.parse(Fs.seal(crypto, "first".toByteArray(), a.secBytes(), a.pubBytes(), b.pubBytes(), pk1Pub, id1)!!)!!
        val m2 = Fs.parse(Fs.seal(crypto, "second".toByteArray(), a.secBytes(), a.pubBytes(), b.pubBytes(), pk2Pub, id2)!!)!!
        assertFalse("each message has a fresh ephemeral", m1.ephPub.contentEquals(m2.ephPub))

        val k1 = Fs.recipientKey(crypto, b.secBytes(), b.pubBytes(), a.pubBytes(), pk1Sec, pk1Pub, id1, m1.ephPub)!!

        val k2 = Fs.recipientKey(crypto, b.secBytes(), b.pubBytes(), a.pubBytes(), pk2Sec, pk2Pub, id2, m2.ephPub)!!
        assertFalse(k1.contentEquals(k2))
        val aad1 = m1.ephPub + m1.prekeyId
        val aad2 = m2.ephPub + m2.prekeyId


        assertArrayEquals("second".toByteArray(), crypto.aeadOpen(m2.cipher, k2, m2.nonce, aad2))


        for (cand in listOf(k2, crypto.kdf32(k2), crypto.kdf32(k2 + byteArrayOf(1)), crypto.kdf32(k2 + byteArrayOf(2)))) {
            assertNull(crypto.aeadOpen(m1.cipher, cand, m1.nonce, aad1))
        }

        assertArrayEquals("first".toByteArray(), crypto.aeadOpen(m1.cipher, k1, m1.nonce, aad1))
    }



    @Test
    fun a_prekey_forged_without_the_contacts_identity_key_is_rejected() {



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

        val (_, oldServer, _) = handshake(alice, bob, bobContacts)
        val laterClient = alice.ch.Client(bob.idPub)
        assertTrue(laterClient.verify(oldServer.response) is Verdict.Rejected)


        val client = alice.ch.Client(bob.idPub)
        val server = (bob.ch.onFirstFrame(client.request, bobContacts) as First.Handshake).server
        val tampered = server.response.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertTrue(client.verify(tampered) is Verdict.Rejected)

        assertTrue(client.verify(server.response) is Verdict.Ok)
    }

    @Test
    fun a_wrong_shaped_reply_or_a_malformed_prekey_is_refused() {
        val client = alice.ch.Client(bob.idPub)
        val challenge = challengeOf(client.request, alice, bob)

        assertTrue(client.verify(craftReply(bob.idSec, alice.idPub, challenge)) is Verdict.Rejected)


        val v = client.verify(craftReply(bob.idSec, alice.idPub, crypto.randomBytes(Fs.PKID) + ByteArray(32) + challenge))
        val prekey = (v as Verdict.Ok).prekey
        assertThrows(java.io.IOException::class.java) { client.seal(prekey, FrameType.MSG, "x".toByteArray()) }
    }

    @Test
    fun a_request_from_someone_who_is_not_a_friend_gets_no_prekey() {
        val mallory = Device("mallory")
        assertTrue(bob.ch.onFirstFrame(mallory.ch.Client(bob.idPub).request, bobContacts) is First.Drop)
    }



    @Test
    fun a_message_sealed_to_a_just_rotated_prekey_still_decrypts() {

        val (aClient, s1, p1) = handshake(alice, bob, bobContacts)

        val (cClient, s2, p2) = handshake(carol, bob, bobContacts)
        assertFalse("rotation issued a new prekey", p1.pub.contentEquals(p2.pub))
        val aMsg = aClient.seal(p1, FrameType.MSG, "sealed to the older prekey".toByteArray())
        val cMsg = cClient.seal(p2, FrameType.MSG, "sealed to the newer prekey".toByteArray())

        assertArrayEquals("sealed to the newer prekey".toByteArray(), delivered(s2.open(cMsg)).body)
        assertArrayEquals("sealed to the older prekey".toByteArray(), delivered(s1.open(aMsg)).body)
    }

    @Test
    fun a_frame_is_bound_to_its_own_connections_prekey() {
        val (aClient, _, p1) = handshake(alice, bob, bobContacts)
        val (_, sOther, _) = handshake(carol, bob, bobContacts)
        val aMsg = aClient.seal(p1, FrameType.MSG, "x".toByteArray())
        assertTrue(sOther.open(aMsg) is Opened.Drop)
    }



    @Test
    fun a_tampered_frame_fails_authentication() {

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

        assertTrue(ex.server.open(ex.msg) is Opened.Drop)


        assertTrue(bob.ch.onFirstFrame(ex.req, bobContacts) is First.Drop)


        val (_, fresh, _) = handshake(alice, bob, bobContacts)
        assertTrue(fresh.open(ex.msg) is Opened.Drop)
    }



    @Test
    fun a_different_wire_version_is_reported_never_silently_dropped() {

        val v2 = crypto.boxSeal(
            FramePad.pad(InnerCodec().wrap(FrameType.MSG, "old build".toByteArray(), bob.idPub, version = 2)),
            bob.idPub, alice.idSec)
        assertSame(First.VersionMismatch, bob.ch.onFirstFrame(v2, bobContacts))

        val v2Knock = crypto.sealedSeal(
            FramePad.pad(InnerCodec().wrap(FrameType.KNOCK, "{}".toByteArray(), bob.idPub, version = 2)), bob.idPub)
        assertSame(First.VersionMismatch, bob.ch.onFirstFrame(v2Knock, bobContacts))

        val client = alice.ch.Client(bob.idPub)
        val challenge = challengeOf(client.request, alice, bob)
        val v4 = craftReply(bob.idSec, alice.idPub,
            crypto.randomBytes(Fs.PKID) + crypto.x25519Keypair().first + challenge, version = SecureChannel.WIRE_VERSION + 1)
        assertSame(Verdict.VersionMismatch, client.verify(v4))
    }



    @Test
    fun ephemeral_and_prekey_secrets_are_wiped_after_use() {
        val (pkPub, _) = crypto.x25519Keypair()

        val eph = crypto.x25519Keypair()
        assertTrue(Fs.seal(crypto, "x".toByteArray(), alice.secBytes(), alice.pubBytes(), bob.pubBytes(),
            pkPub, crypto.randomBytes(Fs.PKID), ephemeral = eph) != null)
        assertTrue(eph.second.all { it == 0.toByte() })

        val eph2 = crypto.x25519Keypair()
        assertNull(Fs.seal(crypto, "x".toByteArray(), alice.secBytes(), alice.pubBytes(), bob.pubBytes(),
            ByteArray(32), crypto.randomBytes(Fs.PKID), ephemeral = eph2))
        assertTrue(eph2.second.all { it == 0.toByte() })


        assertTrue(exchange(alice, bob, FrameType.MSG, "x".toByteArray()).server.prekeyWiped)

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

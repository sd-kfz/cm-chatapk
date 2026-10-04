package org.cmchat.app

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.cmchat.app.crypto.CryptoManager
import org.cmchat.app.crypto.Fs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Loopback proofs of the forward-secrecy frame crypto (real libsodium on host). */
class FsTest {

    private val crypto = CryptoManager(LazySodiumJava(SodiumJava()))

    private class Party(val pub: String, val sec: String)
    private fun party(): Party = crypto.newIdentityKeypair().let { Party(it.first, it.second) }
    private fun prekey() = crypto.newX25519Keypair()                 // (pub, sec)
    private fun pkid() = crypto.toHexPublic(crypto.randomBytes(Fs.PKID))

    @Test
    fun round_trip_decrypts() {
        val a = party(); val b = party(); val (pkPub, pkSec) = prekey(); val id = pkid()
        val msg = "forward secret hello".toByteArray()
        val frame = Fs.seal(crypto, msg, a.sec, b.pub, pkPub, id)
        val p = Fs.parse(frame)!!
        assertArrayEquals(msg, Fs.open(crypto, p, b.sec, pkSec, a.pub))
    }

    @Test
    fun identity_key_compromise_does_NOT_decrypt_without_the_prekey_secret() {
        // Attacker later steals BOTH long-term identity secrets (a.sec, b.sec) but
        // the ephemeral (deleted) and the rotated prekey secret are gone. Opening
        // with the identity keys + a WRONG prekey secret must fail.
        val a = party(); val b = party(); val (pkPub, _) = prekey(); val id = pkid()
        val frame = Fs.seal(crypto, "secret".toByteArray(), a.sec, b.pub, pkPub, id)
        val p = Fs.parse(frame)!!
        val wrongPrekeySec = prekey().second   // attacker doesn't have the real one
        assertNull(Fs.open(crypto, p, b.sec, wrongPrekeySec, a.pub))
    }

    @Test
    fun each_message_uses_a_fresh_independent_ephemeral() {
        val a = party(); val b = party(); val (pkPub, _) = prekey(); val id = pkid()
        val f1 = Fs.parse(Fs.seal(crypto, "m1".toByteArray(), a.sec, b.pub, pkPub, id))!!
        val f2 = Fs.parse(Fs.seal(crypto, "m2".toByteArray(), a.sec, b.pub, pkPub, id))!!
        // Independent ephemerals => independent keys; a later frame reveals nothing
        // about an earlier one.
        assertNotEquals(f1.ephPubHex, f2.ephPubHex)
    }

    @Test
    fun tampered_frame_fails_aead() {
        val a = party(); val b = party(); val (pkPub, pkSec) = prekey(); val id = pkid()
        val frame = Fs.seal(crypto, "hi".toByteArray(), a.sec, b.pub, pkPub, id)
        frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 0x01).toByte()
        assertNull(Fs.open(crypto, Fs.parse(frame)!!, b.sec, pkSec, a.pub))
    }

    @Test
    fun wrong_sender_identity_fails_authentication() {
        val a = party(); val b = party(); val (pkPub, pkSec) = prekey(); val id = pkid()
        val frame = Fs.seal(crypto, "hi".toByteArray(), a.sec, b.pub, pkPub, id)
        val impostor = party()
        // Opening as if it came from someone else => DH2/DH3 differ => auth fails.
        assertNull(Fs.open(crypto, Fs.parse(frame)!!, b.sec, pkSec, impostor.pub))
    }

    @Test
    fun rotation_window_previous_prekey_still_decrypts() {
        // A message sent against a prekey the recipient has rotated past still
        // decrypts as long as that prekey's secret is still in the recent window.
        val a = party(); val b = party()
        val (oldPub, oldSec) = prekey(); val oldId = pkid()   // "previous" prekey
        val frame = Fs.seal(crypto, "late".toByteArray(), a.sec, b.pub, oldPub, oldId)
        // Recipient rotated to a new prekey but kept oldSec in the window:
        val window = mapOf(oldId to oldSec)
        val sec = window[oldId]
        assertArrayEquals("late".toByteArray(), Fs.open(crypto, Fs.parse(frame)!!, b.sec, sec!!, a.pub))
    }
}

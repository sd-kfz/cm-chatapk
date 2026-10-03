package org.cmchat.app

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.cmchat.app.crypto.CmId
import org.cmchat.app.crypto.CryptoManager
import org.cmchat.app.transport.Frame
import org.cmchat.app.transport.FrameCodec
import org.cmchat.app.transport.FrameType
import org.cmchat.app.transport.KnockPayload
import org.cmchat.app.transport.Messages
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CmIdAndFrameTest {

    private fun crypto() = CryptoManager(LazySodiumJava(SodiumJava()))

    private val sampleOnion = "cmchatexampleonionaddressv3base32abcdefghijklmnop234567ab"

    @Test
    fun cmid_round_trip() {
        val c = crypto()
        val (pub, _) = c.newIdentityKeypair()
        val id = CmId.encode(sampleOnion, pub)
        assertTrue(id.startsWith("cmc1:"))
        val decoded = CmId.decode(id)!!
        assertEquals(sampleOnion, decoded.onion)
        assertEquals(pub.lowercase(), decoded.identityPubKeyHex.lowercase())
    }

    @Test
    fun cmid_malformed_rejected() {
        assertNull(CmId.decode("not-a-cmc-id"))
        assertNull(CmId.decode("cmc1:"))
        assertNull(CmId.decode("cmc1:!!!!"))        // invalid base32
        assertNull(CmId.decode("cmc1:AAAAAAAA"))    // decodes but too short for onion+key
    }

    @Test
    fun frame_seal_open_round_trip() {
        val c = crypto()
        val codec = FrameCodec(c)
        val (aPub, aSec) = c.newIdentityKeypair()
        val (bPub, bSec) = c.newIdentityKeypair()

        val payload = "hello over tor".toByteArray()
        val sealed = codec.seal(Frame(FrameType.KNOCK, payload), peerPubKeyHex = bPub, mySecretKeyHex = aSec)

        val opened = codec.open(sealed, peerPubKeyHex = aPub, mySecretKeyHex = bSec)!!
        assertEquals(FrameType.KNOCK, opened.type)
        assertArrayEquals(payload, opened.payload)
    }

    @Test
    fun knock_sealed_box_round_trip() {
        val c = crypto()
        val (bPub, bSec) = c.newIdentityKeypair()
        // Sender knows only B's public key (from B's CMC-ID).
        val knock = KnockPayload(displayName = "Wanderer", cmId = "cmc1:EXAMPLE")
        val plain = Messages.json.encodeToString(KnockPayload.serializer(), knock).toByteArray()
        val sealed = c.sealedSeal(plain, recipientPubKeyHex = bPub)

        val opened = c.sealedOpen(sealed, myPubKeyHex = bPub, mySecretKeyHex = bSec)!!
        val decoded = Messages.json.decodeFromString(KnockPayload.serializer(), String(opened))
        assertEquals("Wanderer", decoded.displayName)
        assertEquals("cmc1:EXAMPLE", decoded.cmId)

        // A different keypair cannot open it.
        val (xPub, xSec) = c.newIdentityKeypair()
        assertNull(c.sealedOpen(sealed, xPub, xSec))
    }

    @Test
    fun tampered_frame_rejected() {
        val c = crypto()
        val codec = FrameCodec(c)
        val (aPub, aSec) = c.newIdentityKeypair()
        val (bPub, bSec) = c.newIdentityKeypair()

        val sealed = codec.seal(Frame(FrameType.MSG, "x".toByteArray()), bPub, aSec)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()
        assertNull(codec.open(sealed, aPub, bSec))
    }
}

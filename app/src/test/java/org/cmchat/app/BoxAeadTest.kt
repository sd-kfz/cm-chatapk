package org.cmchat.app

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.cmchat.app.crypto.CryptoManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test


class BoxAeadTest {

    private val crypto = CryptoManager(LazySodiumJava(SodiumJava()))

    @Test
    fun round_trip_then_tamper_fails_auth() {
        val (aPub, aSec) = crypto.newIdentityKeypair()
        val (bPub, bSec) = crypto.newIdentityKeypair()
        val msg = "hello forward secrecy".toByteArray()

        val sealed = crypto.boxSeal(msg, bPub, aSec)
        assertArrayEquals(msg, crypto.boxOpen(sealed, aPub, bSec))


        val tampered = sealed.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        assertNull("tampered ciphertext must fail auth", crypto.boxOpen(tampered, aPub, bSec))


        val (_, cSec) = crypto.newIdentityKeypair()
        assertNull("wrong key must fail auth", crypto.boxOpen(sealed, aPub, cSec))
    }
}

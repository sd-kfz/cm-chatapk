package org.cmchat.app

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.cmchat.app.crypto.CryptoManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Proves the authenticated-encryption guarantees the wire relies on: a correctly
 * sealed frame round-trips, and ANY tampered byte makes authentication fail (the
 * frame opens to null and is dropped). Runs the real libsodium on the host JVM.
 */
class BoxAeadTest {

    private val crypto = CryptoManager(LazySodiumJava(SodiumJava()))

    @Test
    fun round_trip_then_tamper_fails_auth() {
        val (aPub, aSec) = crypto.newIdentityKeypair()
        val (bPub, bSec) = crypto.newIdentityKeypair()
        val msg = "hello forward secrecy".toByteArray()

        val sealed = crypto.boxSeal(msg, bPub, aSec)          // A -> B
        assertArrayEquals(msg, crypto.boxOpen(sealed, aPub, bSec))

        // Flip one byte of the ciphertext -> AEAD must reject (null), not decrypt.
        val tampered = sealed.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        assertNull("tampered ciphertext must fail auth", crypto.boxOpen(tampered, aPub, bSec))

        // Wrong recipient key must also fail.
        val (_, cSec) = crypto.newIdentityKeypair()
        assertNull("wrong key must fail auth", crypto.boxOpen(sealed, aPub, cSec))
    }
}

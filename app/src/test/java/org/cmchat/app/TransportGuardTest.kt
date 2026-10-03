package org.cmchat.app

import org.cmchat.app.transport.Transport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportGuardTest {

    private val v3 = "a".repeat(56) // 56 base32 chars

    @Test
    fun accepts_v3_onion_with_and_without_suffix() {
        assertTrue(Transport.isOnionHost(v3))
        assertTrue(Transport.isOnionHost("$v3.onion"))
    }

    @Test
    fun rejects_clearnet_and_malformed() {
        assertFalse(Transport.isOnionHost("example.com"))
        assertFalse(Transport.isOnionHost("8.8.8.8"))
        assertFalse(Transport.isOnionHost("short.onion"))
        assertFalse(Transport.isOnionHost("${"a".repeat(16)}.onion")) // v2 length
        assertFalse(Transport.isOnionHost("${"1".repeat(56)}"))        // '1' not base32
    }

    @Test(expected = IllegalArgumentException::class)
    fun connect_refuses_non_onion() {
        Transport.connectThroughTor(9050, "example.com", 80)
    }
}

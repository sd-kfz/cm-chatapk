package org.cmchat.app

import org.cmchat.app.transport.FramePad
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePadTest {

    @Test
    fun small_payloads_all_pad_to_the_same_base_bucket() {
        // Two different short payloads must become the SAME on-wire size, so
        // length never leaks. 536 is the base bucket.
        val a = FramePad.pad(ByteArray(1) { 7 })
        val b = FramePad.pad(ByteArray(200) { 9 })
        assertEquals(536, a.size)
        assertEquals(536, b.size)
    }

    @Test
    fun roundtrip_preserves_the_inner_frame() {
        for (n in intArrayOf(0, 1, 50, 535, 536, 1000, 8000, 20000)) {
            val inner = ByteArray(n) { (it % 251).toByte() }
            val padded = FramePad.unpad(FramePad.pad(inner))
            assertArrayEquals("roundtrip n=$n", inner, padded)
        }
    }

    @Test
    fun padded_size_is_always_at_least_the_payload_plus_header() {
        for (n in intArrayOf(1, 600, 3000, 9000, 40000)) {
            val padded = FramePad.pad(ByteArray(n))
            assertTrue("n=$n padded=${padded.size}", padded.size >= n + 4)
        }
    }

    @Test
    fun the_largest_padded_frame_still_fits_the_wire_cap_after_forward_secret_sealing() {
        val padded = FramePad.pad(ByteArray((FramePad.MAX_PLAIN - 4).toInt()))
        assertTrue(padded.size + org.cmchat.app.crypto.Fs.OVERHEAD <= org.cmchat.app.transport.Transport.MAX_FRAME_BYTES)
    }

    @Test
    fun malformed_blobs_unpad_to_null() {
        assertNull(FramePad.unpad(ByteArray(2)))                 // too short for header
        // header claims a length longer than the blob -> rejected
        val bad = byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F, 1, 2, 3)
        assertNull(FramePad.unpad(bad))
    }
}

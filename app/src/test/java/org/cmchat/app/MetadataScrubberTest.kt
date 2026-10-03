package org.cmchat.app

import org.cmchat.app.media.MetadataScrubber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataScrubberTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun strips_app1_exif_keeps_image() {
        // SOI, APP1(len=8) "Exif\0GPS", APP0(len=4) JFIF-ish, SOS, data, EOI
        val jpeg = bytes(
            0xFF, 0xD8,                               // SOI
            0xFF, 0xE1, 0x00, 0x06, 'E'.code, 'x'.code, 'i'.code, 'f'.code, // APP1 Exif (len=6: 2+4)
            0xFF, 0xE0, 0x00, 0x04, 0x11, 0x22,       // APP0 (kept)
            0xFF, 0xDA, 0x00, 0x02,                   // SOS
            0x33, 0x44, 0x55,                         // scan data
            0xFF, 0xD9,                               // EOI
        )
        val out = MetadataScrubber.stripJpeg(jpeg)
        // APP1 marker (FF E1) must be gone; APP0 (FF E0) and scan data kept.
        assertFalse(containsMarker(out, 0xE1))
        assertTrue(containsMarker(out, 0xE0))
        assertTrue(out.toList().containsSub(listOf(0x33.toByte(), 0x44.toByte(), 0x55.toByte())))
        // still a valid JPEG start/end
        assertEquals(0xFF, out[0].toInt() and 0xFF)
        assertEquals(0xD8, out[1].toInt() and 0xFF)
    }

    @Test
    fun non_jpeg_unchanged() {
        val png = bytes(0x89, 0x50, 0x4E, 0x47, 1, 2, 3)
        assertArrayEqualsList(png, MetadataScrubber.stripJpeg(png))
    }

    private fun containsMarker(a: ByteArray, second: Int): Boolean {
        for (i in 0 until a.size - 1)
            if ((a[i].toInt() and 0xFF) == 0xFF && (a[i + 1].toInt() and 0xFF) == second) return true
        return false
    }

    private fun <T> List<T>.containsSub(sub: List<T>): Boolean {
        if (sub.isEmpty()) return true
        for (i in 0..size - sub.size) if (subList(i, i + sub.size) == sub) return true
        return false
    }

    private fun assertArrayEqualsList(a: ByteArray, b: ByteArray) =
        assertEquals(a.toList(), b.toList())
}

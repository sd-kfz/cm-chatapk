package org.cmchat.app

import org.cmchat.app.media.Chunked
import org.cmchat.app.media.FileNames
import org.cmchat.app.media.MediaPolicy
import org.cmchat.app.media.MetadataScrubber
import org.cmchat.app.transport.FileTransfer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * E4/E5: nothing leaves with location or camera data, nothing unparseable is
 * sent "probably clean", and a received name can't point anywhere or disguise
 * its type. The device-only part (re-encoding HEIC with Android's decoder) is
 * passed in as a function here.
 */
class MetadataScrubberTest {

    private val gps = "GPS+52.5200+013.4050".toByteArray()

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun ByteArray.has(sub: ByteArray): Boolean {
        outer@ for (i in 0..size - sub.size) { for (k in sub.indices) if (this[i + k] != sub[k]) continue@outer; return true }
        return false
    }
    private fun seg(marker: Int, payload: ByteArray) =
        bytes(0xFF, marker, (payload.size + 2) shr 8, (payload.size + 2) and 0xFF) + payload

    /** An Exif APP1 payload: big-endian TIFF, IFD0 with Orientation=[o] and a GPS-ish string after it. */
    private fun exif(o: Int): ByteArray {
        val b = ByteArrayOutputStream()
        b.write("Exif".toByteArray()); b.write(0); b.write(0)
        b.write(bytes('M'.code, 'M'.code, 0, 42, 0, 0, 0, 8))
        b.write(bytes(0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, o, 0, 0, 0, 0, 0, 0))
        b.write(gps)
        return b.toByteArray()
    }

    private fun jpeg(orientation: Int = 6, trailer: ByteArray = "ftypmp42 video with GPS".toByteArray()): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(bytes(0xFF, 0xD8))
        b.write(seg(0xE0, "JFIF\u0000\u0001\u0001".toByteArray()))
        b.write(seg(0xE1, exif(orientation)))
        b.write(seg(0xE1, "http://ns.adobe.com/xap/1.0/ <x:xmpmeta GPSLatitude>".toByteArray()))
        b.write(seg(0xFE, "comment: taken at home".toByteArray()))
        b.write(seg(0xDB, ByteArray(65) { 1 }))                  // DQT
        b.write(seg(0xC0, ByteArray(15) { 2 }))                  // SOF0
        b.write(seg(0xDA, ByteArray(10) { 3 }))                  // SOS
        b.write(bytes(0x11, 0xFF, 0x00, 0x22, 0xFF, 0xD0, 0x33)) // scan data with stuffing + a restart marker
        b.write(bytes(0xFF, 0xD9))                               // EOI
        b.write(trailer)                                         // a Motion-photo video after the image
        return b.toByteArray()
    }

    @Test
    fun jpeg_loses_exif_xmp_comments_and_the_appended_video_but_keeps_image_and_rotation() {
        val out = MetadataScrubber.stripJpeg(jpeg())!!
        assertFalse("no GPS", out.has(gps))
        assertFalse("no XMP", out.has("xmpmeta".toByteArray()))
        assertFalse("no comment", out.has("taken at home".toByteArray()))
        assertFalse("nothing after the image", out.has("ftypmp42".toByteArray()))
        assertTrue("image data kept", out.has(bytes(0x11, 0xFF, 0x00, 0x22, 0xFF, 0xD0, 0x33, 0xFF, 0xD9)))
        assertTrue("JFIF kept", out.has("JFIF".toByteArray()))
        assertEquals("rotation kept (and only that)", 6, MetadataScrubber.jpegOrientation(out))
        assertEquals(0xD9, out.last().toInt() and 0xFF)
    }

    @Test
    fun an_upright_jpeg_gets_no_exif_at_all() {
        val out = MetadataScrubber.stripJpeg(jpeg(orientation = 1))!!
        assertFalse(out.has("Exif".toByteArray()))
    }

    @Test
    fun a_broken_jpeg_is_refused_not_sent_half_cleaned() {
        val j = jpeg()
        // A segment length that runs past the end, before the image data.
        val broken = j.copyOf(40)
        assertNull(MetadataScrubber.stripJpeg(broken))
        assertNull(MetadataScrubber.stripJpeg("not a jpeg".toByteArray()))
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(bytes(data.size ushr 24, (data.size ushr 16) and 0xFF, (data.size ushr 8) and 0xFF, data.size and 0xFF))
        b.write(type.toByteArray()); b.write(data); b.write(bytes(0, 0, 0, 0))   // CRC not checked here
        return b.toByteArray()
    }
    private val pngSig = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Test
    fun png_keeps_the_picture_and_drops_text_exif_and_time() {
        val png = pngSig + pngChunk("IHDR", ByteArray(13) { 1 }) + pngChunk("tEXt", "Location\u0000".toByteArray() + gps) +
            pngChunk("eXIf", gps) + pngChunk("tIME", ByteArray(7)) + pngChunk("IDAT", ByteArray(20) { 9 }) +
            pngChunk("IEND", ByteArray(0)) + "trailing".toByteArray()
        val out = MetadataScrubber.stripPng(png)!!
        assertFalse(out.has(gps)); assertFalse(out.has("tIME".toByteArray())); assertFalse(out.has("trailing".toByteArray()))
        assertTrue(out.has("IHDR".toByteArray())); assertTrue(out.has(ByteArray(20) { 9 })); assertTrue(out.has("IEND".toByteArray()))
        // An unknown CRITICAL chunk can't be dropped safely: refused.
        assertNull(MetadataScrubber.stripPng(pngSig + pngChunk("IHDR", ByteArray(13)) + pngChunk("CgBI", ByteArray(4)) +
            pngChunk("IEND", ByteArray(0))))
    }

    private fun riffChunk(fourcc: String, data: ByteArray): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(fourcc.toByteArray())
        b.write(bytes(data.size and 0xFF, (data.size ushr 8) and 0xFF, (data.size ushr 16) and 0xFF, data.size ushr 24))
        b.write(data); if (data.size % 2 == 1) b.write(0)
        return b.toByteArray()
    }

    @Test
    fun webp_drops_exif_and_xmp_and_clears_their_flags() {
        val vp8x = ByteArray(10).also { it[0] = 0b0000_1100 }          // EXIF + XMP flags on
        val body = "WEBP".toByteArray() + riffChunk("VP8X", vp8x) + riffChunk("VP8 ", ByteArray(31) { 5 }) +
            riffChunk("EXIF", gps) + riffChunk("XMP ", "<xmp GPS/>".toByteArray())
        val webp = "RIFF".toByteArray() + bytes(body.size and 0xFF, (body.size ushr 8) and 0xFF, 0, 0) + body
        val out = MetadataScrubber.stripWebp(webp)!!
        assertFalse(out.has(gps)); assertFalse(out.has("<xmp".toByteArray()))
        assertTrue(out.has(ByteArray(31) { 5 }))
        assertEquals("flags cleared", 0, out[20].toInt() and 0b0000_1100)
        assertEquals("RIFF size right", out.size - 8, (out[4].toInt() and 0xFF) or ((out[5].toInt() and 0xFF) shl 8))
    }

    @Test
    fun gif_drops_comments_and_xmp_keeps_frames_and_looping() {
        val b = ByteArrayOutputStream()
        b.write("GIF89a".toByteArray()); b.write(bytes(1, 0, 1, 0, 0, 0, 0))          // 1x1, no global colours
        b.write(bytes(0x21, 0xFE, gps.size)); b.write(gps); b.write(0)                  // comment
        b.write(bytes(0x21, 0xFF, 11)); b.write("XMP DataXMP".toByteArray()); b.write(gps.size); b.write(gps); b.write(0)
        b.write(bytes(0x21, 0xFF, 11)); b.write("NETSCAPE2.0".toByteArray()); b.write(bytes(3, 1, 0, 0, 0))
        b.write(bytes(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x4C, 0x01, 0))           // one frame
        b.write(0x3B); b.write("junk after".toByteArray())
        val out = MetadataScrubber.stripGif(b.toByteArray())!!
        assertFalse(out.has(gps)); assertFalse(out.has("XMP".toByteArray())); assertFalse(out.has("junk".toByteArray()))
        assertTrue(out.has("NETSCAPE2.0".toByteArray())); assertTrue(out.has(bytes(0x4C, 0x01, 0)))
        assertEquals(0x3B, out.last().toInt())
    }

    private fun box(type: String, vararg children: ByteArray): ByteArray {
        val payload = children.fold(ByteArray(0)) { a, c -> a + c }
        val n = payload.size + 8
        return bytes(n ushr 24, (n ushr 16) and 0xFF, (n ushr 8) and 0xFF, n and 0xFF) + type.toByteArray(Charsets.ISO_8859_1) + payload
    }
    private fun hdlr(handler: String) = box("hdlr", ByteArray(8), handler.toByteArray(), ByteArray(12))

    private fun mp4(handler: String = "vide") = box("ftyp", "isom".toByteArray(), ByteArray(4)) +
        box("moov", box("mvhd", ByteArray(20)),
            box("trak", box("tkhd", ByteArray(20)), box("mdia", hdlr(handler), box("minf", box("vmhd", ByteArray(8)))),
                box("udta", box("©xyz", gps))),
            box("udta", box("©xyz", gps)), box("meta", box("ilst", gps))) +
        box("uuid", ByteArray(16), "<x:xmpmeta GPS>".toByteArray()) +
        box("mdat", ByteArray(64) { 7 })

    @Test
    fun video_metadata_boxes_are_emptied_and_the_video_keeps_its_shape() {
        val v = mp4()
        val out = MetadataScrubber.neutralizeIsoMedia(v)!!
        assertEquals("same size: offsets still valid", v.size, out.size)
        assertFalse("no GPS bytes left anywhere", out.has(gps))
        assertFalse(out.has("xmpmeta".toByteArray()))
        assertFalse(out.has("udta".toByteArray())); assertFalse(out.has("meta".toByteArray()))
        assertTrue("media data untouched", out.has(ByteArray(64) { 7 }))
        assertTrue(out.has("mvhd".toByteArray()) && out.has("tkhd".toByteArray()))
    }

    @Test
    fun a_video_with_a_location_track_or_a_broken_box_is_refused() {
        assertNull("timed metadata (GPS) track", MetadataScrubber.neutralizeIsoMedia(mp4(handler = "meta")))
        assertNull(MetadataScrubber.neutralizeIsoMedia(mp4(handler = "camm")))
        val broken = mp4().copyOf(60)
        assertNull("box runs past the end", MetadataScrubber.neutralizeIsoMedia(broken))
    }

    @Test
    fun the_kind_comes_from_the_bytes_not_the_name() {
        assertEquals(MetadataScrubber.Kind.JPEG, MetadataScrubber.sniff(jpeg()))
        assertEquals(MetadataScrubber.Kind.ISO_MEDIA, MetadataScrubber.sniff(mp4()))
        assertEquals(MetadataScrubber.Kind.ISO_IMAGE, MetadataScrubber.sniff(box("ftyp", "heic".toByteArray(), ByteArray(4))))
        assertEquals(MetadataScrubber.Kind.TIFF, MetadataScrubber.sniff(bytes(0x49, 0x49, 0x2A, 0, 8, 0, 0, 0)))
    }

    // ---- what may leave the phone (MediaPolicy) -----------------------------------

    private val noReencode: (ByteArray) -> ByteArray? = { null }

    @Test
    fun a_photo_named_like_a_document_is_still_cleaned() {
        val d = MediaPolicy.decide(Chunked.of(jpeg()), "application/pdf", "notes.pdf", noReencode)
        d as MediaPolicy.Decision.Ready
        assertFalse(d.file.join().has(gps))
        assertEquals("image/jpeg", d.mime)
    }

    @Test
    fun heic_is_reencoded_and_refused_if_that_fails() {
        val heic = Chunked.of(box("ftyp", "heic".toByteArray(), ByteArray(4)) + box("meta", gps))
        val fresh = jpeg(orientation = 1, trailer = ByteArray(0))
        val ok = MediaPolicy.decide(heic, "image/heic", "IMG_1.HEIC") { fresh }
        ok as MediaPolicy.Decision.Ready
        assertEquals("IMG_1.jpg", ok.name)
        assertFalse(ok.file.join().has(gps))
        assertTrue(MediaPolicy.decide(heic, "image/heic", "x.heic", noReencode) is MediaPolicy.Decision.Refused)
    }

    @Test
    fun raw_photos_unknown_videos_and_oversized_files_are_refused() {
        assertTrue(MediaPolicy.decide(Chunked.of(bytes(0x4D, 0x4D, 0, 0x2A) + gps), "image/x-adobe-dng", "a.dng", noReencode)
            is MediaPolicy.Decision.Refused)
        assertTrue(MediaPolicy.decide(Chunked.of(bytes(0x1A, 0x45, 0xDF, 0xA3) + gps), "video/webm", "v.webm", noReencode)
            is MediaPolicy.Decision.Refused)
        val tooBig = Chunked(emptyList(), FileTransfer.MAX_BYTES + 1)     // the size alone decides — nothing read
        val r = MediaPolicy.decide(tooBig, "application/zip", "big.zip", noReencode)
        assertEquals(MediaPolicy.TOO_BIG, (r as MediaPolicy.Decision.Refused).reason)
    }

    @Test
    fun a_document_goes_as_it_is_and_the_user_is_told() {
        val pdf = "%PDF-1.7 hello".toByteArray()
        val d = MediaPolicy.decide(Chunked.of(pdf), "application/pdf", "doc.pdf", noReencode) as MediaPolicy.Decision.Ready
        assertArrayEquals(pdf, d.file.join())
        assertTrue(d.note.contains("exactly as it is"))
    }

    // ---- reading a file never buffers past the cap --------------------------------

    @Test
    fun reading_stops_the_moment_the_cap_is_passed() {
        val data = ByteArray(100_000) { it.toByte() }
        val c = Chunked.read(ByteArrayInputStream(data), max = 100_000)!!
        assertEquals(100_000L, c.size)
        assertArrayEquals(data, c.join())
        assertNull(Chunked.read(ByteArrayInputStream(data), max = 99_999))
    }

    // ---- received names (E5) ------------------------------------------------------

    @Test
    fun a_received_name_can_not_point_anywhere_or_hide_its_type() {
        assertEquals("passwd", FileNames.safe("../../etc/passwd"))
        assertEquals("b.txt", FileNames.safe("C:\\Users\\a\\b.txt"))
        assertEquals("photogpj.exe", FileNames.safe("photo\u202Egpj.exe"))            // right-to-left trick removed
        assertEquals("a_b_c_.txt", FileNames.safe("a:b*c?.txt"))
        assertEquals("hidden", FileNames.safe("  ..hidden.. "))
        assertEquals("file", FileNames.safe("../"))
        assertEquals("file", FileNames.safe(null))
        assertEquals("tab", FileNames.safe("t\u0000a\u0007b\u200B"))
        val long = FileNames.safe("x".repeat(300) + ".jpg")
        assertTrue(long.length <= FileNames.MAX_LEN && long.endsWith(".jpg"))
    }
}

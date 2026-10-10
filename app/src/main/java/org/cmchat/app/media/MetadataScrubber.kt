package org.cmchat.app.media

import java.io.ByteArrayOutputStream

/**
 * Removes location / camera metadata from photos and videos BEFORE they leave
 * the phone. FAIL-CLOSED: anything it can't parse returns null, and the caller
 * refuses to send it — a file is never sent "probably clean".
 *
 *  - JPEG: drops APP1..APP15 (Exif with GPS, camera, time, thumbnail; XMP; …)
 *    and comments, and EVERYTHING after the end marker (Motion/Live photos
 *    append a whole video there). APP0/JFIF and the image data stay — plus a
 *    tiny new Exif holding ONLY the rotation, so the photo isn't sideways.
 *  - PNG: keeps only image chunks (header, palette, data, colour); drops text,
 *    eXIf, time and anything unknown. An unknown CRITICAL chunk = refused.
 *  - WebP: drops the EXIF and XMP chunks (and their header flags).
 *  - GIF: drops comments and application blocks (XMP) except the loop setting.
 *  - MP4 / MOV / 3GP / M4A: the metadata boxes (udta, meta, uuid — where phones
 *    put GPS) become zero-filled "free" boxes in place (same sizes, so the video
 *    still plays). A video with a timed metadata TRACK (GoPro / 360 GPS) is
 *    refused: those samples sit inside the media data and can't be cut safely.
 *  - TIFF / RAW (DNG): refused. HEIC / AVIF and other images: re-encoded by the
 *    caller (Android) into a fresh JPEG with no metadata.
 *
 * Pure byte work (no Android), so every rule here is unit-tested.
 */
object MetadataScrubber {

    enum class Kind { JPEG, PNG, WEBP, GIF, TIFF, ISO_IMAGE, ISO_MEDIA, OTHER }

    /** Byte-level access, so a big video is cleaned in place across its pieces. */
    interface ByteAccess {
        val length: Long
        fun at(i: Long): Int
        fun put(i: Long, v: Byte)
    }

    private class ArrayAccess(val b: ByteArray) : ByteAccess {
        override val length: Long get() = b.size.toLong()
        override fun at(i: Long): Int = b[i.toInt()].toInt() and 0xFF
        override fun put(i: Long, v: Byte) { b[i.toInt()] = v }
    }

    private val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val IMAGE_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1", "avif", "avis")

    /** What the bytes ARE (by content, never by name or claimed type). */
    fun sniff(b: ByteArray): Kind {
        if (b.size >= 3 && u(b, 0) == 0xFF && u(b, 1) == 0xD8 && u(b, 2) == 0xFF) return Kind.JPEG
        if (b.size >= 8 && b.copyOfRange(0, 8).contentEquals(PNG_SIG)) return Kind.PNG
        if (b.size >= 12 && ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 4) == "WEBP") return Kind.WEBP
        if (b.size >= 6 && (ascii(b, 0, 6) == "GIF87a" || ascii(b, 0, 6) == "GIF89a")) return Kind.GIF
        if (b.size >= 4 && ((u(b, 0) == 0x49 && u(b, 1) == 0x49 && u(b, 2) == 0x2A && u(b, 3) == 0) ||
                (u(b, 0) == 0x4D && u(b, 1) == 0x4D && u(b, 2) == 0 && u(b, 3) == 0x2A))) return Kind.TIFF
        if (b.size >= 12 && ascii(b, 4, 4) == "ftyp") {
            return if (ascii(b, 8, 4) in IMAGE_BRANDS) Kind.ISO_IMAGE else Kind.ISO_MEDIA
        }
        // Older QuickTime files can start with another top-level atom.
        if (b.size >= 8 && ascii(b, 4, 4) in setOf("moov", "mdat", "wide", "free", "skip", "pnot")) return Kind.ISO_MEDIA
        return Kind.OTHER
    }

    /** Clean [data] if it's a kind this object handles; null = can't be cleaned. */
    fun clean(data: ByteArray): ByteArray? = when (sniff(data)) {
        Kind.JPEG -> stripJpeg(data)
        Kind.PNG -> stripPng(data)
        Kind.WEBP -> stripWebp(data)
        Kind.GIF -> stripGif(data)
        Kind.ISO_MEDIA -> neutralizeIsoMedia(data)
        Kind.TIFF, Kind.ISO_IMAGE, Kind.OTHER -> null
    }

    // ---- JPEG --------------------------------------------------------------------

    fun stripJpeg(d: ByteArray): ByteArray? {
        if (sniff(d) != Kind.JPEG) return null
        val out = ByteArrayOutputStream(d.size)
        out.write(0xFF); out.write(0xD8)
        orientationSegment(jpegOrientation(d))?.let { out.write(it) }
        var i = 2
        var inScan = false
        while (i < d.size) {
            if (inScan) {
                // Entropy-coded data: copy up to the next REAL marker (FF not
                // followed by a stuffed 00, a restart marker, or a fill FF).
                val start = i
                while (i < d.size) {
                    if (u(d, i) == 0xFF && i + 1 < d.size) {
                        val n = u(d, i + 1)
                        if (n == 0x00 || n in 0xD0..0xD7) { i += 2; continue }
                        if (n != 0xFF) break
                    }
                    i++
                }
                out.write(d, start, i - start)
                if (i >= d.size) { out.write(0xFF); out.write(0xD9); return out.toByteArray() }  // no end marker: close it
                inScan = false
                continue
            }
            if (u(d, i) != 0xFF) return null                   // must be a marker here
            var j = i + 1
            while (j < d.size && u(d, j) == 0xFF) j++           // fill bytes
            if (j >= d.size) return null
            val m = u(d, j)
            when {
                // End of image: STOP — whatever follows (a Motion-photo video, a
                // vendor trailer) is dropped with its metadata.
                m == 0xD9 -> { out.write(0xFF); out.write(0xD9); return out.toByteArray() }
                m == 0x01 || m in 0xD0..0xD7 -> { out.write(0xFF); out.write(m); i = j + 1 }
                m == 0x00 || m == 0xD8 -> return null
                else -> {
                    if (j + 2 >= d.size) return null
                    val len = (u(d, j + 1) shl 8) or u(d, j + 2)
                    val end = j + 1 + len
                    if (len < 2 || end > d.size) return null
                    // APP1..APP15 + comments — except Adobe's APP14 colour-transform
                    // flags (no personal data; CMYK photos decode wrongly without it).
                    val adobe = m == 0xEE && len >= 7 && ascii(d, j + 3, 5) == "Adobe"
                    val drop = (m in 0xE1..0xEF && !adobe) || m == 0xFE
                    if (!drop) { out.write(0xFF); out.write(d, j, end - j) }
                    i = end
                    if (m == 0xDA) inScan = true
                }
            }
        }
        return null                                             // never reached the image data
    }

    /**
     * The photo's rotation (Exif tag 0x0112, 1..8) from the original Exif, or
     * null. Read only to be put back on its own — nothing else is copied.
     */
    fun jpegOrientation(d: ByteArray): Int? {
        if (sniff(d) != Kind.JPEG) return null
        var i = 2
        while (i + 4 <= d.size && u(d, i) == 0xFF) {
            val m = u(d, i + 1)
            if (m == 0xDA || m == 0xD9) return null
            val len = (u(d, i + 2) shl 8) or u(d, i + 3)
            if (len < 2 || i + 2 + len > d.size) return null
            if (m == 0xE1) exifOrientation(d, i + 4, i + 2 + len)?.let { return it }
            i += 2 + len
        }
        return null
    }

    private fun exifOrientation(d: ByteArray, seg: Int, end: Int): Int? {
        if (end - seg < 14 || ascii(d, seg, 4) != "Exif" || d[seg + 4].toInt() != 0 || d[seg + 5].toInt() != 0) return null
        val t = seg + 6
        val le = when (ascii(d, t, 2)) { "II" -> true; "MM" -> false; else -> return null }
        fun r16(p: Int) = if (le) u(d, p) or (u(d, p + 1) shl 8) else (u(d, p) shl 8) or u(d, p + 1)
        fun r32(p: Int): Long = if (le) (r16(p).toLong() or (r16(p + 2).toLong() shl 16))
            else ((r16(p).toLong() shl 16) or r16(p + 2).toLong())
        if (t + 8 > end || r16(t + 2) != 42) return null
        val off = r32(t + 4)
        if (off < 8 || t + off + 2 > end) return null
        val ifd = t + off.toInt()
        val n = r16(ifd)
        for (k in 0 until n) {
            val e = ifd + 2 + 12 * k
            if (e + 12 > end) return null
            if (r16(e) == 0x0112 && r16(e + 2) == 3) return r16(e + 8).takeIf { it in 1..8 }
        }
        return null
    }

    /** A minimal Exif segment with ONLY the rotation (none needed for "normal"). */
    private fun orientationSegment(o: Int?): ByteArray? {
        if (o == null || o !in 2..8) return null
        val b = ByteArrayOutputStream(36)
        b.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, 34))            // APP1, length 34
        b.write("Exif".toByteArray(Charsets.US_ASCII)); b.write(0); b.write(0)
        b.write(byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8))   // big-endian TIFF, IFD0 at 8
        b.write(byteArrayOf(0, 1))                                            // one entry
        b.write(byteArrayOf(0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, o.toByte(), 0, 0))   // Orientation, SHORT, 1, value
        b.write(byteArrayOf(0, 0, 0, 0))                                      // no next IFD
        return b.toByteArray()
    }

    // ---- PNG ---------------------------------------------------------------------

    private val PNG_KEEP = setOf(
        "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "cICP",
        "bKGD", "hIST", "pHYs", "sPLT", "acTL", "fcTL", "fdAT",
    )

    fun stripPng(d: ByteArray): ByteArray? {
        if (sniff(d) != Kind.PNG) return null
        val out = ByteArrayOutputStream(d.size)
        out.write(PNG_SIG)
        var i = 8
        while (true) {
            if (i + 12 > d.size) return null
            val len = be32(d, i)
            if (len < 0 || i + 12L + len > d.size) return null
            val type = ascii(d, i + 4, 4)
            val total = 12 + len
            when {
                type in PNG_KEEP -> out.write(d, i, total)
                type[0].isUpperCase() -> return null              // unknown CRITICAL chunk: can't drop it safely
                else -> {}                                        // text / eXIf / tIME / private: dropped
            }
            i += total
            if (type == "IEND") return out.toByteArray()          // trailing data dropped
        }
    }

    // ---- WebP --------------------------------------------------------------------

    fun stripWebp(d: ByteArray): ByteArray? {
        if (sniff(d) != Kind.WEBP) return null
        val riff = le32(d, 4)
        if (riff < 4 || 8L + riff > d.size) return null
        val end = 8 + riff
        val body = ByteArrayOutputStream(riff)
        var i = 12
        while (i < end) {
            if (i + 8 > end) return null
            val fourcc = ascii(d, i, 4)
            val len = le32(d, i + 4)
            if (len < 0 || i + 8L + len > end) return null
            val total = minOf(8 + len + (len and 1), end - i)
            when (fourcc) {
                "EXIF", "XMP " -> {}                               // dropped
                "VP8X" -> {
                    if (len < 1) return null
                    val c = d.copyOfRange(i, i + total)
                    c[8] = (c[8].toInt() and 0b1111_0011).toByte()  // EXIF + XMP flags off
                    body.write(c)
                }
                "VP8 ", "VP8L", "ALPH", "ANIM", "ANMF", "ICCP" -> body.write(d, i, total)
                else -> {}                                         // unknown chunk: dropped
            }
            i += total
        }
        val out = ByteArrayOutputStream(body.size() + 12)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        val n = body.size() + 4
        out.write(byteArrayOf(n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte()))
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        body.writeTo(out)
        return out.toByteArray()
    }

    // ---- GIF ---------------------------------------------------------------------

    fun stripGif(d: ByteArray): ByteArray? {
        if (sniff(d) != Kind.GIF || d.size < 13) return null
        val out = ByteArrayOutputStream(d.size)
        var i = 13
        val packed = u(d, 10)
        if (packed and 0x80 != 0) i += 3 * (1 shl ((packed and 7) + 1))
        if (i > d.size) return null
        out.write(d, 0, i)                                         // header + screen + global colours
        while (i < d.size) {
            when (u(d, i)) {
                0x3B -> { out.write(0x3B); return out.toByteArray() }   // trailer; anything after it dropped
                0x2C -> {                                          // an image
                    if (i + 10 > d.size) return null
                    val p = u(d, i + 9)
                    var j = i + 10
                    if (p and 0x80 != 0) j += 3 * (1 shl ((p and 7) + 1))
                    j += 1                                         // LZW minimum code size
                    j = skipSubBlocks(d, j) ?: return null
                    out.write(d, i, j - i); i = j
                }
                0x21 -> {                                          // an extension
                    if (i + 2 > d.size) return null
                    val label = u(d, i + 1)
                    val j = skipSubBlocks(d, i + 2) ?: return null
                    val keep = when (label) {
                        0xF9, 0x01 -> true                         // frame timing, plain text
                        0xFF -> i + 14 <= d.size && u(d, i + 2) == 11 &&
                            ascii(d, i + 3, 11).let { it == "NETSCAPE2.0" || it == "ANIMEXTS1.0" }   // looping only
                        else -> false                              // comments, XMP, unknown: dropped
                    }
                    if (keep) out.write(d, i, j - i)
                    i = j
                }
                else -> return null
            }
        }
        return null                                                // no trailer
    }

    private fun skipSubBlocks(d: ByteArray, from: Int): Int? {
        var j = from
        while (true) {
            if (j >= d.size) return null
            val n = u(d, j)
            j += 1 + n
            if (n == 0) return j
        }
    }

    // ---- MP4 / MOV / 3GP / M4A ---------------------------------------------------

    private val STRIP_BOXES = setOf("udta", "meta", "uuid", "XMP_")
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "moof", "traf")

    fun neutralizeIsoMedia(d: ByteArray): ByteArray? {
        if (sniff(d) != Kind.ISO_MEDIA) return null
        val out = d.copyOf()
        return if (neutralizeIsoMediaInPlace(ArrayAccess(out))) out else null
    }

    /** The same, in place (a big video is never copied). False = refuse it. */
    fun neutralizeIsoMediaInPlace(a: ByteAccess): Boolean = walk(a, 0, a.length, 0)

    private fun walk(b: ByteAccess, start: Long, end: Long, depth: Int): Boolean {
        var i = start
        while (i < end) {
            if (i + 8 > end) return false
            var size = be32(b, i)
            val type = ascii(b, i + 4)
            var hdr = 8L
            if (size == 1L) {
                if (i + 16 > end) return false
                size = (be32(b, i + 8) shl 32) or be32(b, i + 12); hdr = 16
            } else if (size == 0L) size = end - i
            if (size < hdr || size > end - i) return false
            val boxEnd = i + size
            when {
                type in STRIP_BOXES -> {
                    // Renamed to "free" (players skip it) AND its contents zeroed —
                    // a renamed box would still carry the coordinates as bytes.
                    "free".forEachIndexed { k, c -> b.put(i + 4 + k, c.code.toByte()) }
                    var z = i + hdr
                    while (z < boxEnd) { b.put(z, 0); z++ }
                }
                type == "hdlr" && depth >= 2 -> {
                    // A timed METADATA track (GoPro GPMF, 360 cameras, Apple
                    // location tracks): its samples are inside the media data —
                    // can't be removed safely here, so refuse the whole file.
                    if (i + hdr + 12 > boxEnd) return false
                    if (ascii(b, i + hdr + 8) in setOf("meta", "camm", "gpmd")) return false
                }
                type in CONTAINERS && depth < 6 -> if (!walk(b, i + hdr, boxEnd, depth + 1)) return false
            }
            i = boxEnd
        }
        return true
    }

    private fun be32(b: ByteAccess, i: Long): Long =
        (b.at(i).toLong() shl 24) or (b.at(i + 1).toLong() shl 16) or (b.at(i + 2).toLong() shl 8) or b.at(i + 3).toLong()
    private fun ascii(b: ByteAccess, i: Long): String =
        String(CharArray(4) { b.at(i + it).toChar() })

    // ---- bytes -------------------------------------------------------------------

    private fun u(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
    private fun ascii(b: ByteArray, from: Int, n: Int): String =
        if (from + n > b.size) "" else String(b, from, n, Charsets.ISO_8859_1)
    private fun be32(b: ByteArray, i: Int): Int =
        (u(b, i) shl 24) or (u(b, i + 1) shl 16) or (u(b, i + 2) shl 8) or u(b, i + 3)
    private fun le32(b: ByteArray, i: Int): Int =
        u(b, i) or (u(b, i + 1) shl 8) or (u(b, i + 2) shl 16) or (u(b, i + 3) shl 24)
}

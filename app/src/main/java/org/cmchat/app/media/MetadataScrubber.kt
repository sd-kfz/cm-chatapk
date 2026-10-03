package org.cmchat.app.media

/**
 * Strips metadata from images before sending. For JPEG it removes every APPn
 * marker segment except APP0/JFIF — that drops the Exif (APP1), which is where
 * GPS coordinates, camera model, timestamps and thumbnails live, plus XMP.
 *
 * Pure byte manipulation (no Android deps) so it is unit-testable. Non-JPEG
 * input is returned unchanged; callers should re-encode other formats.
 */
object MetadataScrubber {

    fun stripJpeg(data: ByteArray): ByteArray {
        // JPEG starts with SOI 0xFFD8.
        if (data.size < 4 || data[0].u() != 0xFF || data[1].u() != 0xD8) return data
        val out = ArrayList<Byte>(data.size)
        out.add(data[0]); out.add(data[1]) // SOI
        var i = 2
        while (i + 1 < data.size) {
            if (data[i].u() != 0xFF) { // not a marker — copy the rest (entropy data)
                out.add(data[i]); i++; continue
            }
            val marker = data[i + 1].u()
            when {
                // Start of scan: copy everything from here to the end (compressed data).
                marker == 0xDA -> { while (i < data.size) { out.add(data[i]); i++ }; }
                // Standalone markers with no length (RSTn, SOI, EOI, TEM).
                marker == 0x01 || marker in 0xD0..0xD9 -> { out.add(data[i]); out.add(data[i + 1]); i += 2 }
                else -> {
                    // Segment with a 2-byte big-endian length following the marker.
                    if (i + 3 >= data.size) { out.add(data[i]); i++; continue }
                    val len = ((data[i + 2].u() shl 8) or data[i + 3].u())
                    val segEnd = i + 2 + len
                    val strip = marker in 0xE1..0xEF // APP1..APP15 (Exif, XMP, ...)
                    if (!strip) {
                        var k = i
                        while (k < segEnd && k < data.size) { out.add(data[k]); k++ }
                    }
                    i = segEnd
                }
            }
        }
        return out.toByteArray()
    }

    private fun Byte.u(): Int = this.toInt() and 0xFF
}

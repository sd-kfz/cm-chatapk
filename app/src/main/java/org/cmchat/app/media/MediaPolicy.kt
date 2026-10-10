package org.cmchat.app.media

/**
 * What may leave the phone as a file — decided by what the bytes ARE, never by
 * the name or the claimed type. Nothing leaves with location or camera data:
 *
 *  - JPEG / PNG / WebP / GIF photos: cleaned ([MetadataScrubber]).
 *  - MP4 / MOV / 3GP / M4A: metadata boxes neutralised in place.
 *  - HEIC / AVIF and other image types: re-encoded as a fresh JPEG ([reencode],
 *    Android) — no metadata survives a re-encode.
 *  - TIFF / RAW photos, other video types, or anything the cleaner can't parse:
 *    REFUSED, with the reason. Never "probably clean".
 *  - Everything else (documents, archives, audio): sent exactly as it is, and
 *    the user is told so before sending.
 *
 * Pure (the Android re-encoder is passed in), so every rule is unit-tested.
 */
object MediaPolicy {

    /** What was done to a file, or why it was refused — the screen shows it in the user's language. */
    enum class Note {
        TOO_BIG, EMPTY, CANT_CLEAN, VIDEO_TRACK, RAW, VIDEO_TYPE, UNREADABLE, NO_MEMORY,
        PHOTO_CLEANED, VIDEO_CLEANED, PHOTO_CONVERTED, AS_IS,
    }

    sealed interface Decision {
        /** OK to send: [note] is shown before sending. */
        class Ready(val name: String, val mime: String, val file: Chunked, val note: Note) : Decision
        class Refused(val reason: Note) : Decision
    }

    fun decide(file: Chunked, mime: String, name: String, reencode: (ByteArray) -> ByteArray?): Decision {
        if (file.size > org.cmchat.app.transport.FileTransfer.MAX_BYTES) return Decision.Refused(Note.TOO_BIG)
        if (file.size == 0L) return Decision.Refused(Note.EMPTY)
        val safe = FileNames.safe(name)
        val kind = MetadataScrubber.sniff(file.head(16))
        return when (kind) {
            MetadataScrubber.Kind.JPEG, MetadataScrubber.Kind.PNG,
            MetadataScrubber.Kind.WEBP, MetadataScrubber.Kind.GIF -> {
                val whole = file.join()
                val clean = MetadataScrubber.clean(whole)
                whole.fill(0)
                if (clean == null) Decision.Refused(Note.CANT_CLEAN)
                else Decision.Ready(safe, mimeOf(kind), Chunked.of(clean),
                    Note.PHOTO_CLEANED)
            }
            MetadataScrubber.Kind.ISO_MEDIA ->
                if (MetadataScrubber.neutralizeIsoMediaInPlace(file)) Decision.Ready(safe, mime.ifBlank { "video/mp4" }, file,
                    Note.VIDEO_CLEANED)
                else Decision.Refused(Note.VIDEO_TRACK)
            MetadataScrubber.Kind.ISO_IMAGE -> reencoded(file, safe, reencode)
            MetadataScrubber.Kind.TIFF -> Decision.Refused(Note.RAW)
            MetadataScrubber.Kind.OTHER -> when {
                mime.startsWith("image/") -> reencoded(file, safe, reencode)
                mime.startsWith("video/") -> Decision.Refused(Note.VIDEO_TYPE)
                else -> Decision.Ready(safe, mime.ifBlank { "application/octet-stream" }, file, Note.AS_IS)
            }
        }
    }

    private fun reencoded(file: Chunked, safe: String, reencode: (ByteArray) -> ByteArray?): Decision {
        val whole = file.join()
        val jpeg = try { reencode(whole) } finally { whole.fill(0) }
        // A re-encode makes a brand-new JPEG; cleaning it too costs nothing.
        val clean = jpeg?.let { MetadataScrubber.stripJpeg(it) } ?: return Decision.Refused(Note.CANT_CLEAN)
        val name = safe.substringBeforeLast('.', safe) + ".jpg"
        return Decision.Ready(name, "image/jpeg", Chunked.of(clean), Note.PHOTO_CONVERTED)
    }

    private fun mimeOf(kind: MetadataScrubber.Kind) = when (kind) {
        MetadataScrubber.Kind.JPEG -> "image/jpeg"
        MetadataScrubber.Kind.PNG -> "image/png"
        MetadataScrubber.Kind.WEBP -> "image/webp"
        MetadataScrubber.Kind.GIF -> "image/gif"
        else -> "application/octet-stream"
    }
}

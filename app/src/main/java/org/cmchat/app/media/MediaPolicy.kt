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

    sealed interface Decision {
        /** OK to send: [note] is shown before sending. */
        class Ready(val name: String, val mime: String, val file: Chunked, val note: String) : Decision
        class Refused(val reason: String) : Decision
    }

    const val TOO_BIG = "That file is over 100 MB. Files can be at most 100 MB."
    private const val CANT_CLEAN = "This file can't be cleaned of its location / camera data, so it isn't sent."

    fun decide(file: Chunked, mime: String, name: String, reencode: (ByteArray) -> ByteArray?): Decision {
        if (file.size > org.cmchat.app.transport.FileTransfer.MAX_BYTES) return Decision.Refused(TOO_BIG)
        if (file.size == 0L) return Decision.Refused("That file is empty.")
        val safe = FileNames.safe(name)
        val kind = MetadataScrubber.sniff(file.head(16))
        return when (kind) {
            MetadataScrubber.Kind.JPEG, MetadataScrubber.Kind.PNG,
            MetadataScrubber.Kind.WEBP, MetadataScrubber.Kind.GIF -> {
                val whole = file.join()
                val clean = MetadataScrubber.clean(whole)
                whole.fill(0)
                if (clean == null) Decision.Refused(CANT_CLEAN)
                else Decision.Ready(safe, mimeOf(kind), Chunked.of(clean),
                    "Photo: location and camera data removed.")
            }
            MetadataScrubber.Kind.ISO_MEDIA ->
                if (MetadataScrubber.neutralizeIsoMediaInPlace(file)) Decision.Ready(safe, mime.ifBlank { "video/mp4" }, file,
                    "Video: location and camera data removed.")
                else Decision.Refused("This video carries data (for example a location track) that can't be removed, so it isn't sent.")
            MetadataScrubber.Kind.ISO_IMAGE -> reencoded(file, safe, reencode)
            MetadataScrubber.Kind.TIFF -> Decision.Refused("RAW / TIFF photos can't be cleaned of their location / camera data, so they aren't sent.")
            MetadataScrubber.Kind.OTHER -> when {
                mime.startsWith("image/") -> reencoded(file, safe, reencode)
                mime.startsWith("video/") -> Decision.Refused("This video type can't be cleaned of its location / camera data, so it isn't sent.")
                else -> Decision.Ready(safe, mime.ifBlank { "application/octet-stream" }, file,
                    "Not a photo or video: sent exactly as it is. Documents can carry their own hidden data (author, embedded photos).")
            }
        }
    }

    private fun reencoded(file: Chunked, safe: String, reencode: (ByteArray) -> ByteArray?): Decision {
        val whole = file.join()
        val jpeg = try { reencode(whole) } finally { whole.fill(0) }
        // A re-encode makes a brand-new JPEG; cleaning it too costs nothing.
        val clean = jpeg?.let { MetadataScrubber.stripJpeg(it) } ?: return Decision.Refused(CANT_CLEAN)
        val name = safe.substringBeforeLast('.', safe) + ".jpg"
        return Decision.Ready(name, "image/jpeg", Chunked.of(clean), "Photo: converted to JPEG without location or camera data.")
    }

    private fun mimeOf(kind: MetadataScrubber.Kind) = when (kind) {
        MetadataScrubber.Kind.JPEG -> "image/jpeg"
        MetadataScrubber.Kind.PNG -> "image/png"
        MetadataScrubber.Kind.WEBP -> "image/webp"
        MetadataScrubber.Kind.GIF -> "image/gif"
        else -> "application/octet-stream"
    }
}

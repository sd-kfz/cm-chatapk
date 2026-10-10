package org.cmchat.app.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.cmchat.app.transport.FileTransfer
import java.io.ByteArrayOutputStream

/**
 * Android side of sending a file: the size is checked from the file's own
 * record BEFORE anything is read; reading stops the moment it would pass
 * 100 MB (a wrong or missing size can't sneak a bigger file in); then
 * [MediaPolicy] decides what may leave (cleaned, re-encoded, refused).
 * Runs off the main thread.
 */
object FilePrep {

    fun prepare(ctx: Context, uri: Uri): MediaPolicy.Decision {
        val cr = ctx.contentResolver
        var name = "file"
        var size = -1L
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        if (size > FileTransfer.MAX_BYTES) return MediaPolicy.Decision.Refused(MediaPolicy.Note.TOO_BIG)
        val mime = runCatching { cr.getType(uri) }.getOrNull() ?: "application/octet-stream"
        val file = try {
            val stream = cr.openInputStream(uri) ?: return MediaPolicy.Decision.Refused(MediaPolicy.Note.UNREADABLE)
            stream.use { Chunked.read(it, FileTransfer.MAX_BYTES) }
                ?: return MediaPolicy.Decision.Refused(MediaPolicy.Note.TOO_BIG)
        } catch (_: OutOfMemoryError) {
            return MediaPolicy.Decision.Refused(MediaPolicy.Note.NO_MEMORY)
        } catch (_: Exception) {
            return MediaPolicy.Decision.Refused(MediaPolicy.Note.UNREADABLE)
        }
        return try {
            MediaPolicy.decide(file, mime, name) { reencodeJpeg(it) }
        } catch (_: OutOfMemoryError) {
            MediaPolicy.Decision.Refused(MediaPolicy.Note.NO_MEMORY)
        }
    }

    /**
     * A brand-new JPEG from the pixels alone — no metadata can survive this.
     * Rotation is applied (ImageDecoder reads it) so the photo isn't sideways,
     * and very large photos are scaled down (long side 4096 px) to fit in RAM.
     */
    private fun reencodeJpeg(bytes: ByteArray): ByteArray? = runCatching {
        val bmp = if (android.os.Build.VERSION.SDK_INT >= 28) {
            val src = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
            android.graphics.ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                val long = maxOf(info.size.width, info.size.height)
                if (long > 4096) {
                    val k = 4096.0 / long
                    decoder.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1),
                        (info.size.height * k).toInt().coerceAtLeast(1))
                }
            }
        } else {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        try {
            ByteArrayOutputStream().use { out ->
                if (!bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)) return null
                out.toByteArray()
            }
        } finally {
            bmp.recycle()
        }
    }.getOrNull()
}

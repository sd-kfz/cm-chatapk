package org.cmchat.app.media

/**
 * A received file's name is the SENDER's text: it is reduced to a plain, safe
 * name before it is ever shown or offered for saving — no folders ("../", "a/b",
 * "C:\x"), no control or invisible characters, no right-to-left tricks that
 * make "photo‮gpj.exe" look like "photo.exe.jpg", nothing a file system treats
 * specially, and at most [MAX_LEN] characters (the extension kept).
 */
object FileNames {
    const val MAX_LEN = 80
    private const val MAX_EXT = 10

    fun safe(raw: String?): String {
        var n = (raw ?: "").replace('\\', '/').substringAfterLast('/')
        n = buildString {
            var i = 0
            while (i < n.length) {
                val cp = n.codePointAt(i)
                i += Character.charCount(cp)
                val t = Character.getType(cp)
                if (Character.isISOControl(cp) || t == Character.FORMAT.toInt() ||
                    t == Character.SURROGATE.toInt() || t == Character.UNASSIGNED.toInt() ||
                    t == Character.PRIVATE_USE.toInt() || t == Character.LINE_SEPARATOR.toInt() ||
                    t == Character.PARAGRAPH_SEPARATOR.toInt()) continue
                if (cp < 0x80 && "<>:\"|?*".indexOf(cp.toChar()) >= 0) append('_') else appendCodePoint(cp)
            }
        }
        n = n.trim().trim('.').trim()
        if (n.isEmpty()) return "file"
        if (n.codePointCount(0, n.length) > MAX_LEN) {
            val dot = n.lastIndexOf('.')
            val ext = if (dot > 0 && n.length - dot <= MAX_EXT + 1) n.substring(dot) else ""
            val base = if (ext.isEmpty()) n else n.substring(0, dot)
            val keep = MAX_LEN - ext.length
            n = base.substring(0, base.offsetByCodePoints(0, minOf(keep, base.codePointCount(0, base.length)))) + ext
        }
        return n
    }
}

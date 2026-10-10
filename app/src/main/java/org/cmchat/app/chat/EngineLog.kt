package org.cmchat.app.chat

/**
 * Engine logs (the Diagnostics and Connection logs) belong in those screens
 * only — never in a conversation. Copying a log ("Copy all") and pasting it
 * into a chat would fill the conversation with [tor]/[onion]/SOCKS lines AND
 * hand the friend your connection metadata, so such a paste is not sent, and
 * one that arrives is not shown as a message.
 *
 * Deliberately narrow: only the exact formats our two logs produce count, and
 * it takes at least two such lines (or one complete timestamped log line on
 * its own), so an ordinary message that mentions "[tor]" or "SOCKS" is never
 * touched.
 */
object EngineLog {

    /** Diagnostics "Copy all": `1728550000123 I [tor] status=ON`. */
    private val diagDump = Regex("""^\d{10,13} [DIWE] \[[A-Za-z0-9_/-]{1,24}] """)

    /** Connection "Copy": `14:02:11.042 OUT resolve abcd…wxyz.onion`. */
    private val connDump = Regex("""^\d{2}:\d{2}:\d{2}\.\d{3} (OUT|IN|SYS) """)

    /** The Diagnostics screen as displayed: `[onion] published …`. */
    private val tagged = Regex(
        """^\[(tor|onion|life|bridges|buzz|watchdog|vault|crash|cover|addr|transport|selftest)] """)

    fun looksLikeLog(text: String): Boolean {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return false
        val hits = lines.count { diagDump.containsMatchIn(it) || connDump.containsMatchIn(it) || tagged.containsMatchIn(it) }
        if (lines.size == 1) return diagDump.containsMatchIn(lines[0]) || connDump.containsMatchIn(lines[0])
        return hits >= 2 && hits * 2 >= lines.size
    }

    /** Shown instead of a log that arrived as a message. */
    const val HIDDEN_NOTICE = "Your friend sent an engine log — not shown here (logs stay in Connection/Diagnostics)."

    /** Shown under the composer when a log is pasted. */
    const val NOT_SENT_HINT = "That's an engine log — logs stay in Connection/Diagnostics, so it wasn't sent."
}

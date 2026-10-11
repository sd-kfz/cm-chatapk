package org.cmchat.app.chat


object EngineLog {


    private val diagDump = Regex("""^\d{10,13} [DIWE] \[[A-Za-z0-9_/-]{1,24}] """)


    private val connDump = Regex("""^\d{2}:\d{2}:\d{2}\.\d{3} (OUT|IN|SYS) """)


    private val tagged = Regex(
        """^\[(tor|onion|life|bridges|buzz|watchdog|vault|crash|cover|addr|transport|selftest)] """)

    fun looksLikeLog(text: String): Boolean {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return false
        val hits = lines.count { diagDump.containsMatchIn(it) || connDump.containsMatchIn(it) || tagged.containsMatchIn(it) }
        if (lines.size == 1) return diagDump.containsMatchIn(lines[0]) || connDump.containsMatchIn(lines[0])
        return hits >= 2 && hits * 2 >= lines.size
    }


    const val HIDDEN_NOTICE = "Your friend sent an engine log — not shown here (logs stay in Connection/Diagnostics)."


    const val NOT_SENT_HINT = "That's an engine log — logs stay in Connection/Diagnostics, so it wasn't sent."
}

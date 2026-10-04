package org.cmchat.app.diag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Connection diagnostic ("Link Test") log — its OWN section, separate from the
 * Tor [Diag] log and the Self-Test log. It records every stage of reaching a
 * contact (OUT) and of being reached (IN) so a failed connection shows exactly
 * where it broke.
 *
 * Privacy: RAM-only (never written to disk), and every line is pushed through
 * [Redact.scrub] so a full onion address or key blob can never land here —
 * callers also pass only [Redact.onionShort] prefixes. Message contents and
 * keys are NEVER logged.
 */
object ConnDiag {

    enum class Dir { OUT, IN, SYS }

    data class Line(val atMs: Long, val dir: Dir, val text: String)

    private const val CAP = 300
    private val lock = Any()
    private val buffer = ArrayDeque<Line>(CAP)

    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()

    /** Last self-test outcome + time, shown in the state summary. */
    val lastSelfTest = MutableStateFlow<String?>(null)

    fun out(text: String) = add(Dir.OUT, text)
    fun inc(text: String) = add(Dir.IN, text)
    fun sys(text: String) = add(Dir.SYS, text)

    fun recordSelfTest(ok: Boolean, ms: Long) {
        lastSelfTest.value = "${if (ok) "OK" else "FAIL"} ${ms}ms @ ${clock(System.currentTimeMillis())}"
    }

    private fun add(dir: Dir, raw: String) {
        val line = Line(System.currentTimeMillis(), dir, Redact.scrub(raw))
        synchronized(lock) {
            if (buffer.size >= CAP) buffer.removeFirst()
            buffer.addLast(line)
            _lines.value = buffer.toList()
        }
    }

    fun dump(): String = synchronized(lock) {
        buffer.joinToString("\n") { "${clock(it.atMs)} ${it.dir} ${it.text}" }
    }

    fun clear() {
        synchronized(lock) { buffer.clear(); _lines.value = emptyList() }
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000 % 86400
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return "%02d:%02d:%02d.%03d".format(h, m, sec, ms % 1000)
    }
}

package org.cmchat.app.tools

import kotlinx.coroutines.flow.MutableStateFlow

/** A single RAM-only checklist item in Notes. */
data class NoteCheck(val id: Long, val text: String, val done: Boolean = false)

/**
 * Which dock tools are enabled (off by default), the Notes scratchpad, and the
 * Notes checklist. All RAM-only: nothing here is written to disk, and it is
 * wiped when the app/process ends or on a guardian wipe. (Converter removed.)
 */
object ToolsState {
    val calcEnabled = MutableStateFlow(false)
    val notesEnabled = MutableStateFlow(false)
    val flashlightEnabled = MutableStateFlow(false)

    /** RAM-only scratchpad; exists only while the app is open. */
    val notes = MutableStateFlow("")

    /** RAM-only checklist; ticking an item strikes it through but keeps it. */
    val checks = MutableStateFlow<List<NoteCheck>>(emptyList())

    private var checkSeq = 0L

    fun addCheck() {
        checks.value = checks.value + NoteCheck(id = ++checkSeq, text = "")
    }

    fun setCheckText(id: Long, text: String) {
        checks.value = checks.value.map { if (it.id == id) it.copy(text = text) else it }
    }

    fun toggleCheck(id: Long) {
        checks.value = checks.value.map { if (it.id == id) it.copy(done = !it.done) else it }
    }

    fun anyEnabled(): Boolean = calcEnabled.value || notesEnabled.value || flashlightEnabled.value

    fun clear() { notes.value = ""; checks.value = emptyList() }
}

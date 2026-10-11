package org.cmchat.app.diag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


object Diag {

    enum class Level { D, I, W, E }

    data class Entry(val timeMs: Long, val level: Level, val tag: String, val message: String)

    private const val MAX = 200
    private val lock = Any()
    private val buffer = ArrayDeque<Entry>(MAX)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private var droppedFrames = 0L

    fun d(tag: String, msg: String) = add(Level.D, tag, msg)
    fun i(tag: String, msg: String) = add(Level.I, tag, msg)
    fun w(tag: String, msg: String) = add(Level.W, tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) =
        add(Level.E, tag, if (t != null) "$msg: ${t.javaClass.simpleName}: ${t.message}" else msg)


    fun droppedFrame() {
        val n = synchronized(lock) { ++droppedFrames }
        add(Level.W, "transport", "dropped undecryptable frame (count=$n)")
    }

    private fun add(level: Level, tag: String, rawMessage: String) {


        val message = Redact.scrub(rawMessage)
        val entry = Entry(System.currentTimeMillis(), level, tag, message)
        synchronized(lock) {
            if (buffer.size >= MAX) buffer.removeFirst()
            buffer.addLast(entry)
            _entries.value = buffer.toList()
        }

        runCatching {
            when (level) {
                Level.D -> android.util.Log.d("cmchat/$tag", message)
                Level.I -> android.util.Log.i("cmchat/$tag", message)
                Level.W -> android.util.Log.w("cmchat/$tag", message)
                Level.E -> android.util.Log.e("cmchat/$tag", message)
            }
        }
    }

    fun dump(): String = synchronized(lock) {
        buffer.reversed().joinToString("\n") { e ->
            "${e.timeMs} ${e.level} [${e.tag}] ${e.message}"
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            droppedFrames = 0
            _entries.value = emptyList()
        }
    }
}

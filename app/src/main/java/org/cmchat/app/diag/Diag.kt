package org.cmchat.app.diag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * RAM-only diagnostics ring buffer (~200 entries). Never written to disk;
 * auto-cleared on app close and on every wipe path. In debug it also mirrors
 * to Logcat; in release those android.util.Log calls are stripped by R8, but
 * this in-app buffer stays (it is ephemeral and only shown when the user opens
 * the Diagnostics screen).
 *
 * Privacy: network-layer drops are logged as a COUNT only — never the frame
 * content or the peer.
 */
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

    /** Count-only: a frame failed to decrypt/verify and was dropped. */
    fun droppedFrame() {
        val n = synchronized(lock) { ++droppedFrames }
        add(Level.W, "transport", "dropped undecryptable frame (count=$n)")
    }

    private fun add(level: Level, tag: String, rawMessage: String) {
        // Defence in depth: scrub any full onion address or key blob before it
        // ever reaches the buffer or Logcat, no matter the call site.
        val message = Redact.scrub(rawMessage)
        val entry = Entry(System.currentTimeMillis(), level, tag, message)
        synchronized(lock) {
            if (buffer.size >= MAX) buffer.removeFirst()
            buffer.addLast(entry)
            _entries.value = buffer.toList()
        }
        // Mirrored to Logcat in debug; stripped by R8 in release.
        runCatching {   // logging must never be able to crash anything
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

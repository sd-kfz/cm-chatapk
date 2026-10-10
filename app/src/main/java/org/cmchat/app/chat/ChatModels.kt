package org.cmchat.app.chat

import org.cmchat.app.R
import org.cmchat.app.i18n.Tr

/**
 * Local-only send state, NEVER shown on screen. There are no delivery/read
 * receipts, and no "offline / retry" either (that would reveal whether a friend
 * is online): SENDING = queued in the silent Outbox, SENT = handed over to the
 * friend's phone. Used internally only (e.g. view-once removes my copy on SENT).
 */
enum class MsgState { SENDING, SENT }

/**
 * Self-destruct durations, shared by the per-message timer and the general
 * timer. OFF = never. A timed message disappears this long after it is SEEN.
 *
 * VIEW_ONCE is NOT a duration: it is true burn-after-first-view. It carries no
 * millis (so the time-based [SelfTimerRules] ignores it) and is burned
 * explicitly — the sender's own copy once it's on its way, the recipient's copy
 * once they've seen it and left the chat. See [ChatStore].
 */
enum class SelfTimer(val label: String, val millis: Long?) {
    OFF("off", null),
    VIEW_ONCE("view-once", null),
    S30("30s", 30_000L),
    M5("5m", 5 * 60_000L),
    M10("10m", 10 * 60_000L),
    M30("30m", 30 * 60_000L),
    M60("60m", 60 * 60_000L),
    M120("120m", 120 * 60_000L),
    H6("6h", 6 * 60 * 60_000L),
    H12("12h", 12 * 60 * 60_000L),
    H24("24h", 24 * 60 * 60_000L);

    companion object {
        fun fromLabel(l: String): SelfTimer = entries.firstOrNull { it.label == l } ?: OFF
    }
}

/**
 * UI display text. OFF shows as a neutral "Off" (capitalised) — it is NOT the
 * "Single Message" concept. "Single Message" is the per-message self-destruct
 * selector in the chat composer, which applies ONLY to the one message being
 * sent; there is no longer a second "Single Message" label anywhere. With OFF a
 * message follows the global/general expiry; any other value is a one-off
 * per-message timer. `label` stays the wire value, so only the DISPLAYED text
 * changes.
 */
fun SelfTimer.displayLabel(): String = when (this) {
    SelfTimer.OFF -> Tr.s(R.string.timer_off)
    SelfTimer.VIEW_ONCE -> Tr.s(R.string.timer_view_once)
    SelfTimer.S30 -> Tr.s(R.string.dur_seconds, 30)
    SelfTimer.H6, SelfTimer.H12, SelfTimer.H24 -> Tr.s(R.string.dur_hours, millis!! / 3_600_000L)
    else -> Tr.s(R.string.dur_minutes, millis!! / 60_000L)
}

/** True if this message self-destructs on first view (no timer). */
val SelfTimer.isViewOnce: Boolean get() = this == SelfTimer.VIEW_ONCE

/**
 * A file in a chat — RAM only, like a message: never written anywhere unless
 * the user taps Save, and never opened or interpreted by the app. [name] is
 * already made safe ([org.cmchat.app.media.FileNames]). Not a data class: the
 * bytes are compared by identity, and [wipe] zeroes them when it leaves the chat.
 */
class ChatFile(val name: String, val size: Long, val mime: String, val chunks: List<ByteArray>) {
    fun writeTo(out: java.io.OutputStream) = chunks.forEach { out.write(it) }
    fun wipe() = chunks.forEach { it.fill(0) }
}

/** A chat message. RAM-only; never written to disk. */
data class ChatMessage(
    val id: String,
    val mine: Boolean,
    val text: String,
    val state: MsgState,
    val selfTimer: SelfTimer = SelfTimer.OFF,
    val createdAt: Long = System.currentTimeMillis(),
    val seenAt: Long? = null,
    val system: Boolean = false,
    /** Arrived while Invisible: shown as a red italic "Missed Message" once Online. */
    val missed: Boolean = false,
    /** Arrived while the app was CLOSED (held, delivered at the next unlock): keeps
     * its "Missed Message" mark after going Online, until the chat is left. */
    val closedMiss: Boolean = false,
    /** A system ALERT (e.g. "Decoy chat tripped."): a small red timestamped line. */
    val alert: Boolean = false,
    /** A file instead of text ([text] is then its name). */
    val file: ChatFile? = null,
    /** What a system line means, so it's shown in the CURRENT language ([text] keeps the English). */
    val note: SystemNote? = null,
)

/** The meaning of a system line in a chat (the screen words it in the chosen language). */
sealed interface SystemNote {
    /** A friend's decoy fired: the chat was burned. */
    data object DecoyErased : SystemNote
    /** The Team Clock changed. [by] null = me, "" = the friend (no name known); [time] null = turned off. */
    data class TeamClock(val by: String?, val time: String?) : SystemNote
}

/**
 * Per-chat presence — the ONLY presence shown, deliberately coarse, never an
 * exact time and never "online". Within 24h it reads "last seen recently";
 * after 24h it shows nothing at all. It is kept (vault, hour-rounded) when the
 * friend goes Invisible or shuts down, and across my own restarts.
 */
object LastSeen {
    private const val DAY_MS = 24 * 60 * 60_000L

    fun bucket(lastSeenAtMs: Long?, nowMs: Long = System.currentTimeMillis()): String? {
        if (lastSeenAtMs == null || lastSeenAtMs > nowMs) return null
        return if (nowMs - lastSeenAtMs <= DAY_MS) "last seen recently" else null
    }
}

object SelfTimerRules {
    /** A message disappears this long after it is SEEN (not sent). Null = never. */
    fun expiresAt(seenAtMs: Long?, timer: SelfTimer): Long? {
        val seen = seenAtMs ?: return null
        val d = timer.millis ?: return null
        return seen + d
    }

    fun isExpired(seenAtMs: Long?, timer: SelfTimer, nowMs: Long = System.currentTimeMillis()): Boolean {
        val at = expiresAt(seenAtMs, timer) ?: return false
        return nowMs >= at
    }
}

/** "4:05 PM" from an hour (0-23) and minute — clocks and message times. */
fun formatHm12(hour: Int, minute: Int): String {
    val h12 = if (hour % 12 == 0) 12 else hour % 12
    return "%d:%02d %s".format(h12, minute, if (hour < 12) "AM" else "PM")
}

/** A message timestamp: hour:minute AM/PM in this phone's local time, nothing more. */
fun formatTimestamp(atMs: Long): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = atMs }
    return formatHm12(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
}

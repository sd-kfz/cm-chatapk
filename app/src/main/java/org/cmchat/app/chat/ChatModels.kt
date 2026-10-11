package org.cmchat.app.chat

import org.cmchat.app.R
import org.cmchat.app.i18n.Tr


enum class MsgState { SENDING, SENT }


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


fun SelfTimer.displayLabel(): String = when (this) {
    SelfTimer.OFF -> Tr.s(R.string.timer_off)
    SelfTimer.VIEW_ONCE -> Tr.s(R.string.timer_view_once)
    SelfTimer.S30 -> Tr.s(R.string.dur_seconds, 30)
    SelfTimer.H6, SelfTimer.H12, SelfTimer.H24 -> Tr.s(R.string.dur_hours, millis!! / 3_600_000L)
    else -> Tr.s(R.string.dur_minutes, millis!! / 60_000L)
}


val SelfTimer.isViewOnce: Boolean get() = this == SelfTimer.VIEW_ONCE


class ChatFile(val name: String, val size: Long, val mime: String, val chunks: List<ByteArray>) {
    fun writeTo(out: java.io.OutputStream) = chunks.forEach { out.write(it) }
    fun wipe() = chunks.forEach { it.fill(0) }
}


data class ChatMessage(
    val id: String,
    val mine: Boolean,
    val text: String,
    val state: MsgState,
    val selfTimer: SelfTimer = SelfTimer.OFF,
    val createdAt: Long = System.currentTimeMillis(),
    val seenAt: Long? = null,
    val system: Boolean = false,

    val missed: Boolean = false,

    val closedMiss: Boolean = false,

    val alert: Boolean = false,

    val file: ChatFile? = null,

    val note: SystemNote? = null,
)


sealed interface SystemNote {

    data object DecoyErased : SystemNote

    data class TeamClock(val by: String?, val time: String?) : SystemNote
}


object LastSeen {
    private const val DAY_MS = 24 * 60 * 60_000L

    fun bucket(lastSeenAtMs: Long?, nowMs: Long = System.currentTimeMillis()): String? {
        if (lastSeenAtMs == null || lastSeenAtMs > nowMs) return null
        return if (nowMs - lastSeenAtMs <= DAY_MS) "last seen recently" else null
    }
}

object SelfTimerRules {

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


fun formatHm12(hour: Int, minute: Int): String {
    val h12 = if (hour % 12 == 0) 12 else hour % 12
    return "%d:%02d %s".format(h12, minute, if (hour < 12) "AM" else "PM")
}


fun formatTimestamp(atMs: Long): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = atMs }
    return formatHm12(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
}

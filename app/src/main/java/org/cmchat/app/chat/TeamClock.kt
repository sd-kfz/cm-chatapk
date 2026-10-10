package org.cmchat.app.chat

/**
 * Team Clock: a shared, private clock for ONE conversation. You SET it like a
 * phone alarm — "make our clock show 4:30 PM now" — and from then on both
 * friends see the same team time ticking live in the chat. No time zones are
 * shown or picked anywhere.
 *
 * Under the hood the clock is an offset (minutes) from the phones' own clocks
 * in UTC, so both sides tick in step. Setting it sends the value to the friend
 * inside the encrypted conversation (TEAM_CLOCK frame); it's stored per friend
 * in the vault. Wire/vault form: "UTC+05:30", "UTC-03:07" — or empty = no clock.
 * Minute precision, -12:00..+14:00. Plain arithmetic on epoch milliseconds: no
 * time-zone database. Received values are validated strictly.
 */
object TeamClock {

    const val MIN_OFFSET = -12 * 60
    const val MAX_OFFSET = 14 * 60
    const val STEP = 1

    private val FORMAT = Regex("^UTC([+-])(\\d{2}):(\\d{2})$")

    /** Canonical string for an offset in minutes. */
    fun encode(offsetMin: Int): String {
        require(isValid(offsetMin)) { "offset out of range" }
        val sign = if (offsetMin < 0) "-" else "+"
        val a = kotlin.math.abs(offsetMin)
        return "UTC$sign%02d:%02d".format(a / 60, a % 60)
    }

    /** Parse a stored/received value; null if absent or not EXACTLY canonical. */
    fun decode(s: String?): Int? {
        if (s.isNullOrEmpty() || s.length > 9) return null
        val m = FORMAT.matchEntire(s) ?: return null
        val h = m.groupValues[2].toInt()
        val mm = m.groupValues[3].toInt()
        if (mm >= 60) return null
        val v = (h * 60 + mm) * (if (m.groupValues[1] == "-") -1 else 1)
        return if (isValid(v)) v else null
    }

    fun isValid(offsetMin: Int): Boolean = offsetMin in MIN_OFFSET..MAX_OFFSET

    private fun minuteOfDay(nowMs: Long, offsetMin: Int): Int =
        Math.floorMod(Math.floorDiv(nowMs, 60_000L) + offsetMin, 1440L).toInt()

    /** "HH:mm" (24 h) team time at [nowMs] for [offsetMin]. */
    fun timeAt(nowMs: Long, offsetMin: Int): String {
        val m = minuteOfDay(nowMs, offsetMin)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** "4:05 PM" — the team time the way a phone alarm shows it. */
    fun time12(nowMs: Long, offsetMin: Int): String {
        val m = minuteOfDay(nowMs, offsetMin)
        return formatHm12(m / 60, m % 60)
    }

    /** The team time at [nowMs] as (hour 0-23, minute) — to preset the picker. */
    fun hourMinuteAt(nowMs: Long, offsetMin: Int): Pair<Int, Int> {
        val m = minuteOfDay(nowMs, offsetMin)
        return m / 60 to m % 60
    }

    /**
     * The offset that makes the team clock show [hour]:[minute] right now — what
     * "setting it like an alarm" means. Picked from the two equivalent offsets
     * (a day apart) the one closest to zero, so it's always valid.
     */
    fun offsetFor(hour: Int, minute: Int, nowMs: Long = System.currentTimeMillis()): Int {
        val utcMinute = Math.floorMod(Math.floorDiv(nowMs, 60_000L), 1440L).toInt()
        var d = Math.floorMod(hour * 60 + minute - utcMinute, 1440)
        if (d >= 720) d -= 1440                          // -720 .. +719
        return d
    }

    /** Short label, e.g. "UTC+5:30", "UTC−3", "UTC". */
    fun label(offsetMin: Int): String {
        if (offsetMin == 0) return "UTC"
        val sign = if (offsetMin < 0) "−" else "+"
        val a = kotlin.math.abs(offsetMin)
        return if (a % 60 == 0) "UTC$sign${a / 60}" else "UTC$sign${a / 60}:%02d".format(a % 60)
    }

    /** This phone's current offset, rounded to the nearest valid step. */
    fun deviceOffset(nowMs: Long = System.currentTimeMillis()): Int {
        val raw = java.util.TimeZone.getDefault().getOffset(nowMs) / 60_000
        val stepped = Math.round(raw / STEP.toDouble()).toInt() * STEP
        return stepped.coerceIn(MIN_OFFSET, MAX_OFFSET)
    }
}

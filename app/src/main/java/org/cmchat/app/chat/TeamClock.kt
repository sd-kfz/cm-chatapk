package org.cmchat.app.chat

/**
 * Team Clock: a shared, private clock for ONE conversation. Both friends see
 * the same "team time" — a UTC offset the two of them agree on (e.g. the time
 * where a meeting happens) — ticking live in the chat. Setting it sends the new
 * value to the friend inside the encrypted conversation (TEAM_CLOCK frame), so
 * both sides always show the same clock; it's stored per friend in the vault.
 *
 * Wire/vault form: "UTC+05:30", "UTC-03:00", "UTC+00:00" — or empty = no clock.
 * Offsets run from UTC-12:00 to UTC+14:00 in 15-minute steps (covers every real
 * zone, incl. +05:45 and +12:45). Time is computed from epoch milliseconds with
 * plain arithmetic: no time-zone database, so no device setting can skew it.
 * Received values are validated strictly; anything else is ignored.
 */
object TeamClock {

    const val MIN_OFFSET = -12 * 60
    const val MAX_OFFSET = 14 * 60
    const val STEP = 15

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

    fun isValid(offsetMin: Int): Boolean =
        offsetMin in MIN_OFFSET..MAX_OFFSET && offsetMin % STEP == 0

    /** "HH:mm" team time at [nowMs] for [offsetMin]. */
    fun timeAt(nowMs: Long, offsetMin: Int): String {
        val minutesOfDay = Math.floorMod(Math.floorDiv(nowMs, 60_000L) + offsetMin, 1440L)
        return "%02d:%02d".format(minutesOfDay / 60, minutesOfDay % 60)
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

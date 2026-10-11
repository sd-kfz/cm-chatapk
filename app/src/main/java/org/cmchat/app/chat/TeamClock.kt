package org.cmchat.app.chat


object TeamClock {

    const val MIN_OFFSET = -12 * 60
    const val MAX_OFFSET = 14 * 60
    const val STEP = 1

    private val FORMAT = Regex("^UTC([+-])(\\d{2}):(\\d{2})$")


    fun encode(offsetMin: Int): String {
        require(isValid(offsetMin)) { "offset out of range" }
        val sign = if (offsetMin < 0) "-" else "+"
        val a = kotlin.math.abs(offsetMin)
        return "UTC$sign%02d:%02d".format(a / 60, a % 60)
    }


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


    fun timeAt(nowMs: Long, offsetMin: Int): String {
        val m = minuteOfDay(nowMs, offsetMin)
        return "%02d:%02d".format(m / 60, m % 60)
    }


    fun time12(nowMs: Long, offsetMin: Int): String {
        val m = minuteOfDay(nowMs, offsetMin)
        return formatHm12(m / 60, m % 60)
    }


    fun hourMinuteAt(nowMs: Long, offsetMin: Int): Pair<Int, Int> {
        val m = minuteOfDay(nowMs, offsetMin)
        return m / 60 to m % 60
    }


    fun offsetFor(hour: Int, minute: Int, nowMs: Long = System.currentTimeMillis()): Int {
        val utcMinute = Math.floorMod(Math.floorDiv(nowMs, 60_000L), 1440L).toInt()
        var d = Math.floorMod(hour * 60 + minute - utcMinute, 1440)
        if (d >= 720) d -= 1440
        return d
    }


    fun label(offsetMin: Int): String {
        if (offsetMin == 0) return "UTC"
        val sign = if (offsetMin < 0) "−" else "+"
        val a = kotlin.math.abs(offsetMin)
        return if (a % 60 == 0) "UTC$sign${a / 60}" else "UTC$sign${a / 60}:%02d".format(a % 60)
    }


    fun deviceOffset(nowMs: Long = System.currentTimeMillis()): Int {
        val raw = java.util.TimeZone.getDefault().getOffset(nowMs) / 60_000
        val stepped = Math.round(raw / STEP.toDouble()).toInt() * STEP
        return stepped.coerceIn(MIN_OFFSET, MAX_OFFSET)
    }
}

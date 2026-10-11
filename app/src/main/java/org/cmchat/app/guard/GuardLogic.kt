package org.cmchat.app.guard


object GuardLogic {

    const val CERBERUS_90_MIN = 90L * 60_000L
    const val CERBERUS_180_MIN = 180L * 60_000L
    const val KILL_MAX_MS = 24L * 60 * 60_000L


    fun nextOccurrence(hour: Int, minute: Int, nowMs: Long,
                       tz: java.util.TimeZone = java.util.TimeZone.getDefault()): Long {
        val c = java.util.Calendar.getInstance(tz).apply {
            timeInMillis = nowMs
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= nowMs) c.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }


    fun cerberusDeadline(lastTouchMs: Long, cerberusIdleMs: Long): Long =
        lastTouchMs + cerberusIdleMs


    fun cerberusExpired(lastTouchMs: Long, cerberusIdleMs: Long, nowMs: Long): Boolean =
        nowMs >= cerberusDeadline(lastTouchMs, cerberusIdleMs)


    fun nextDeadline(lastTouchMs: Long, cerberusIdleMs: Long, killDeadlineMs: Long?): Long {
        val cerberus = cerberusDeadline(lastTouchMs, cerberusIdleMs)
        return if (killDeadlineMs == null) cerberus else minOf(cerberus, killDeadlineMs)
    }


    fun firstToFire(lastTouchMs: Long, cerberusIdleMs: Long, killDeadlineMs: Long?): Guardian {
        val cerberus = cerberusDeadline(lastTouchMs, cerberusIdleMs)
        return if (killDeadlineMs != null && killDeadlineMs < cerberus) Guardian.KILL
        else Guardian.CERBERUS
    }
}

enum class Guardian { CERBERUS, KILL }

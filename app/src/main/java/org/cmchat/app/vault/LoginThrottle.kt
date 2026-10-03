package org.cmchat.app.vault

/**
 * Escalating lock-out after failed PIN attempts. The per-attempt delays are a
 * fixed schedule; once exhausted, a 30-minute lock-out applies, after which the
 * counter resets to zero. There is NO password recovery.
 */
object LoginThrottle {
    val SCHEDULE = intArrayOf(2, 4, 8, 20, 40, 60, 80, 120, 150, 200, 250, 300)
    const val LOCKOUT_SECONDS = 30 * 60

    /** Seconds to wait after [attempt] consecutive failures (1-based). */
    fun delaySeconds(attempt: Int): Int = when {
        attempt <= 0 -> 0
        attempt <= SCHEDULE.size -> SCHEDULE[attempt - 1]
        else -> LOCKOUT_SECONDS
    }

    /** "45s" or "30m 00s" for the UI. */
    fun format(seconds: Int): String =
        if (seconds < 60) "${seconds}s"
        else "${seconds / 60}m ${(seconds % 60).toString().padStart(2, '0')}s"
}

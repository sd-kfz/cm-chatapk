package org.cmchat.app.vault


object LoginThrottle {
    val SCHEDULE = intArrayOf(2, 4, 8, 20, 40, 60, 80, 120, 150, 200, 250, 300)
    const val LOCKOUT_SECONDS = 30 * 60


    fun delaySeconds(attempt: Int): Int = when {
        attempt <= 0 -> 0
        attempt <= SCHEDULE.size -> SCHEDULE[attempt - 1]
        else -> LOCKOUT_SECONDS
    }


    fun format(seconds: Int): String =
        if (seconds < 60) "${seconds}s"
        else "${seconds / 60}m ${(seconds % 60).toString().padStart(2, '0')}s"
}

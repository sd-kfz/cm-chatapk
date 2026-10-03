package org.cmchat.app.guard

/**
 * Pure timing logic for the two guardians, kept separate from Android so it is
 * unit-testable.
 *
 *  - Cerberus: fires after [cerberusIdleMs] of no interaction (touching the
 *    app, including reopening from recents, resets the idle clock).
 *  - Kill Timer: fires at a fixed wall-clock deadline once armed.
 *
 * Whichever deadline comes first wins.
 */
object GuardLogic {

    const val CERBERUS_90_MIN = 90L * 60_000L
    const val CERBERUS_180_MIN = 180L * 60_000L
    const val KILL_MAX_MS = 24L * 60 * 60_000L

    /** Absolute time Cerberus would fire, given the last interaction time. */
    fun cerberusDeadline(lastTouchMs: Long, cerberusIdleMs: Long): Long =
        lastTouchMs + cerberusIdleMs

    /** True if Cerberus should fire now. */
    fun cerberusExpired(lastTouchMs: Long, cerberusIdleMs: Long, nowMs: Long): Boolean =
        nowMs >= cerberusDeadline(lastTouchMs, cerberusIdleMs)

    /**
     * The earliest deadline among the armed guardians, or null if none armed.
     * @param killDeadlineMs absolute fire time of the kill timer, or null if unarmed
     */
    fun nextDeadline(lastTouchMs: Long, cerberusIdleMs: Long, killDeadlineMs: Long?): Long {
        val cerberus = cerberusDeadline(lastTouchMs, cerberusIdleMs)
        return if (killDeadlineMs == null) cerberus else minOf(cerberus, killDeadlineMs)
    }

    /** Which guardian fires first (or null if neither has a deadline yet). */
    fun firstToFire(lastTouchMs: Long, cerberusIdleMs: Long, killDeadlineMs: Long?): Guardian {
        val cerberus = cerberusDeadline(lastTouchMs, cerberusIdleMs)
        return if (killDeadlineMs != null && killDeadlineMs < cerberus) Guardian.KILL
        else Guardian.CERBERUS
    }
}

enum class Guardian { CERBERUS, KILL }

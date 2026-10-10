package org.cmchat.app

import org.cmchat.app.guard.GuardLogic
import org.cmchat.app.guard.Guardian
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardLogicTest {

    private val t0 = 1_000_000_000L
    private val idle = GuardLogic.CERBERUS_90_MIN

    @Test
    fun cerberus_idle_threshold() {
        assertFalse(GuardLogic.cerberusExpired(t0, idle, t0 + idle - 1))
        assertTrue(GuardLogic.cerberusExpired(t0, idle, t0 + idle))
        assertTrue(GuardLogic.cerberusExpired(t0, idle, t0 + idle + 5_000))
        // a touch resets the clock (later lastTouch pushes the deadline out)
        assertFalse(GuardLogic.cerberusExpired(t0 + 10_000, idle, t0 + idle))
    }

    @Test
    fun first_to_fire_whichever_is_sooner() {
        // kill sooner than cerberus
        val killSoon = t0 + 10 * 60_000L
        assertEquals(Guardian.KILL, GuardLogic.firstToFire(t0, idle, killSoon))
        assertEquals(killSoon, GuardLogic.nextDeadline(t0, idle, killSoon))

        // cerberus sooner than kill
        val killLate = t0 + idle + 60_000L
        assertEquals(Guardian.CERBERUS, GuardLogic.firstToFire(t0, idle, killLate))
        assertEquals(GuardLogic.cerberusDeadline(t0, idle), GuardLogic.nextDeadline(t0, idle, killLate))

        // no kill armed -> cerberus
        assertEquals(Guardian.CERBERUS, GuardLogic.firstToFire(t0, idle, null))
        assertEquals(GuardLogic.cerberusDeadline(t0, idle), GuardLogic.nextDeadline(t0, idle, null))
    }

    @Test
    fun kill_timer_set_like_an_alarm_fires_at_the_next_matching_time() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val now = 1_700_000_000_000L                       // 22:13:20 UTC
        // Later today.
        assertEquals(now - (13 * 60_000L + 20_000L) + 60 * 60_000L,
            GuardLogic.nextOccurrence(23, 0, now, utc))
        // Already passed today -> tomorrow, never in the past.
        val t = GuardLogic.nextOccurrence(7, 30, now, utc)
        assertTrue(t > now && t - now <= 24 * 60 * 60_000L)
        assertEquals(7 * 60 + 30, ((t / 60_000L) % 1440).toInt())
        // The exact current minute counts as passed (fires tomorrow, not now).
        assertTrue(GuardLogic.nextOccurrence(22, 13, now, utc) > now)
    }
}

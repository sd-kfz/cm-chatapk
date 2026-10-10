package org.cmchat.app

import org.cmchat.app.tor.TorClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The clock-skew diagnostic line (Connection log) reads Tor's consensus time correctly. */
class TorClockTest {

    @Test
    fun parses_tor_consensus_time_as_utc() {
        assertEquals(1_700_000_000_000L - 13 * 60_000L - 20_000L,
            TorClock.parseUtc("2023-11-14 22:00:00"))
        assertEquals(TorClock.parseUtc("2023-11-14 22:00:00"), TorClock.parseUtc("\"2023-11-14 22:00:00\"\n"))
        assertNull(TorClock.parseUtc("garbage"))
    }

    @Test
    fun verdicts() {
        assertEquals("Clock check: OK", TorClock.verdict(42))
        assertEquals("Clock check: not available", TorClock.verdict(null))
        assertTrue(TorClock.verdict(-90).contains("90 min BEHIND"))
        assertTrue(TorClock.verdict(600).contains("600 min AHEAD"))
    }
}

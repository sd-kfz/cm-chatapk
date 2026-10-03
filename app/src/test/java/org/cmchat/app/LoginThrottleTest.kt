package org.cmchat.app

import org.cmchat.app.vault.LoginThrottle
import org.junit.Assert.assertEquals
import org.junit.Test

class LoginThrottleTest {

    @Test
    fun escalating_schedule_then_lockout() {
        assertEquals(2, LoginThrottle.delaySeconds(1))
        assertEquals(4, LoginThrottle.delaySeconds(2))
        assertEquals(300, LoginThrottle.delaySeconds(12))
        // 13th and beyond -> 30-minute lockout
        assertEquals(30 * 60, LoginThrottle.delaySeconds(13))
        assertEquals(30 * 60, LoginThrottle.delaySeconds(50))
        assertEquals(0, LoginThrottle.delaySeconds(0))
    }

    @Test
    fun formats_minutes_and_seconds() {
        assertEquals("45s", LoginThrottle.format(45))
        assertEquals("30m 00s", LoginThrottle.format(1800))
        assertEquals("1m 05s", LoginThrottle.format(65))
    }
}

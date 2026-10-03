package org.cmchat.app

import org.cmchat.app.chat.LastSeen
import org.cmchat.app.chat.SelfTimer
import org.cmchat.app.chat.SelfTimerRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLogicTest {

    private val now = 1_000_000_000L

    @Test
    fun last_seen_buckets() {
        assertNull(LastSeen.bucket(null, now))
        assertEquals("last seen recently", LastSeen.bucket(now - 10 * 60_000L, now))
        assertEquals("last seen recently", LastSeen.bucket(now - 23 * 60 * 60_000L, now))
        assertNull(LastSeen.bucket(now - 25 * 60 * 60_000L, now))   // >24h -> nothing
        assertNull(LastSeen.bucket(now + 5_000L, now))             // future -> nothing
    }

    @Test
    fun self_timer_expiry_counts_from_seen() {
        // off -> never expires
        assertFalse(SelfTimerRules.isExpired(seenAtMs = now, SelfTimer.OFF, now + 10_000_000L))
        // not seen -> never expires
        assertFalse(SelfTimerRules.isExpired(seenAtMs = null, SelfTimer.S30, now + 10_000_000L))
        // seen, 30s: not yet at 29s, expired at 31s
        assertFalse(SelfTimerRules.isExpired(now, SelfTimer.S30, now + 29_000L))
        assertTrue(SelfTimerRules.isExpired(now, SelfTimer.S30, now + 31_000L))
        assertEquals(now + 30_000L, SelfTimerRules.expiresAt(now, SelfTimer.S30))
    }

    @Test
    fun self_timer_label_round_trip() {
        assertEquals(SelfTimer.M5, SelfTimer.fromLabel("5m"))
        assertEquals(SelfTimer.OFF, SelfTimer.fromLabel("nonsense"))
    }
}

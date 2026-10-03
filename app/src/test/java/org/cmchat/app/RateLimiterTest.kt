package org.cmchat.app

import org.cmchat.app.transport.RateLimiter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

    @Test
    fun drops_after_burst_then_refills() {
        val rl = RateLimiter(burst = 3, refillPerSec = 1.0)
        val t = 1_000_000L
        // First 3 allowed (burst), 4th dropped (same instant).
        assertTrue(rl.allow("a", t))
        assertTrue(rl.allow("a", t))
        assertTrue(rl.allow("a", t))
        assertFalse(rl.allow("a", t))
        // After ~2s, ~2 tokens refilled.
        assertTrue(rl.allow("a", t + 2_000))
        assertTrue(rl.allow("a", t + 2_000))
        assertFalse(rl.allow("a", t + 2_000))
    }

    @Test
    fun keys_are_independent() {
        val rl = RateLimiter(burst = 1, refillPerSec = 1.0)
        val t = 5_000_000L
        assertTrue(rl.allow("x", t))
        assertFalse(rl.allow("x", t))
        assertTrue(rl.allow("y", t))   // different key has its own bucket
    }
}

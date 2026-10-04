package org.cmchat.app

import org.cmchat.app.transport.ReplayGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayGuardTest {

    @Test
    fun in_order_sequences_are_accepted_once() {
        val g = ReplayGuard()
        for (i in 0L..100L) assertTrue("seq $i", g.check("k", i))
    }

    @Test
    fun duplicate_is_rejected() {
        val g = ReplayGuard()
        assertTrue(g.check("k", 5))
        assertFalse("replay of 5", g.check("k", 5))
    }

    @Test
    fun out_of_order_within_window_accepted_then_duplicate_rejected() {
        val g = ReplayGuard()
        assertTrue(g.check("k", 10))
        assertTrue("older-but-in-window 7", g.check("k", 7))
        assertFalse("replay of 7", g.check("k", 7))
        assertFalse("replay of 10", g.check("k", 10))
    }

    @Test
    fun too_old_outside_window_is_rejected() {
        val g = ReplayGuard(window = 64)
        assertTrue(g.check("k", 200))
        assertFalse("200-100 is outside the 64 window", g.check("k", 100))
    }

    @Test
    fun separate_sessions_are_independent() {
        val g = ReplayGuard()
        assertTrue(g.check("a:sess1", 0))
        // A fresh session (sender rebooted, counter reset) is not a replay.
        assertTrue(g.check("a:sess2", 0))
    }

    @Test
    fun negative_sequence_rejected() {
        assertFalse(ReplayGuard().check("k", -1))
    }
}

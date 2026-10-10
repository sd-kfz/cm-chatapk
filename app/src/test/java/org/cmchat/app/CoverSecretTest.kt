package org.cmchat.app

import org.cmchat.app.tools.CoverSecret
import org.cmchat.app.tools.CoverSecret.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** K2: the cover calculator's secret — the same key 10× gets in; "reset" 20× forgets the key. */
class CoverSecretTest {

    private fun CoverSecret.times(k: String, n: Int): List<Action> = (1..n).map { press(k) }

    @Test
    fun the_first_key_tapped_ten_times_becomes_the_key_and_opens() {
        val s = CoverSecret(null)
        assertEquals("9 isn't enough", List(9) { Action.NONE }, s.times("7", 9))
        assertEquals(Action.OPEN, s.press("7"))
        assertEquals("7", s.key)
    }

    @Test
    fun only_that_key_opens_and_any_other_key_breaks_the_run() {
        val s = CoverSecret("7")
        assertEquals(Action.NONE, s.times("3", 10).last())          // another key: just a calculator
        s.times("7", 9); s.press("+")                                 // interrupted at 9
        assertEquals(Action.NONE, s.times("7", 9).last())
        assertEquals(Action.OPEN, s.press("7"))
    }

    @Test
    fun reset_twenty_times_forgets_the_key() {
        val s = CoverSecret("7")
        assertEquals(Action.NONE, s.times(CoverSecret.RESET, 19).last())
        assertEquals(Action.FORGET, s.press(CoverSecret.RESET))
        assertNull(s.key)
        assertEquals("a new key is picked", Action.OPEN, s.times("2", 10).last())
        assertEquals("2", s.key)
    }
}

package org.pocketcalc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TwinTest {

    @Test
    fun the_stored_hash_finds_the_right_app_and_nothing_else() {
        assertTrue(Target.matches("org.cmchat.app"))
        assertFalse(Target.matches("org.cmchat.app2"))
        assertFalse(Target.matches("org.pocketcalc.app"))
    }

    @Test
    fun ten_of_the_same_key_open_and_twenty_resets_forget() {
        val s = Secret(null)
        repeat(9) { assertEquals(Secret.Action.NONE, s.press("7")) }
        assertEquals(Secret.Action.OPEN, s.press("7"))          // first time: 7 becomes THE key
        repeat(10) { assertEquals("another key never opens", Secret.Action.NONE, s.press("3")) }
        val t = Secret("7")
        repeat(10) { t.press("3") }
        assertEquals("another key never opens", "7", t.key)
        repeat(19) { assertEquals(Secret.Action.NONE, t.press(Secret.RESET)) }
        assertEquals(Secret.Action.FORGET, t.press(Secret.RESET))
        assertEquals(null, t.key)
    }

    @Test
    fun a_broken_sequence_starts_counting_again() {
        val s = Secret("5")
        repeat(9) { s.press("5") }
        s.press("6")
        repeat(9) { assertEquals(Secret.Action.NONE, s.press("5")) }
        assertEquals(Secret.Action.OPEN, s.press("5"))
    }

    @Test
    fun it_works_as_a_calculator() {
        val e = CalcEngine()
        e.digit(1); e.digit(2); e.op('+'); e.digit(3); e.equals()
        assertEquals("15", e.display)
        e.op('/'); e.digit(0); e.equals()
        assertEquals(CalcEngine.ERROR, e.display)
    }

    @Test
    fun nothing_in_this_app_names_what_it_opens() {
        // No readable trace of the other app in this app's code or resources.
        val src = File("src/main").walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
        for (f in src) assertFalse("${f.path} mentions it", f.readText().contains("cmchat", ignoreCase = true))
    }
}

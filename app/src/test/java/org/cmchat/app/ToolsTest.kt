package org.cmchat.app

import org.cmchat.app.tools.CalcEngine
import org.cmchat.app.tools.Calculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ToolsTest {

    private fun close(a: Double, b: Double) = assertTrue("$a vs $b", abs(a - b) < 1e-6)

    @Test
    fun calculator_precedence_and_parens() {
        close(7.0, Calculator.eval("1 + 2 * 3")!!)
        close(9.0, Calculator.eval("(1 + 2) * 3")!!)
        close(2.5, Calculator.eval("5 / 2")!!)
        close(-1.0, Calculator.eval("-3 + 2")!!)
        close(3.14, Calculator.eval("3.14")!!)
    }

    @Test
    fun calculator_rejects_bad_input() {
        assertNull(Calculator.eval("1 +"))
        assertNull(Calculator.eval("(1 + 2"))
        assertNull(Calculator.eval("1 / 0"))
        assertNull(Calculator.eval("abc"))
    }

    @Test
    fun calc_engine_immediate_execution() {
        val e = CalcEngine()
        // 12 + 3 = 15 (immediate execution, like the pocket calculator)
        e.digit(1); e.digit(2); e.op('+'); e.digit(3); e.equals()
        assertEquals("15", e.display)
    }

    @Test
    fun calc_engine_chain_and_sqrt_and_percent() {
        val e = CalcEngine()
        // 2 + 3 * 4 applies + first (pocket semantics): (2+3)=5, then *4 = 20
        e.digit(2); e.op('+'); e.digit(3); e.op('*'); e.digit(4); e.equals()
        assertEquals("20", e.display)

        val s = CalcEngine()
        s.digit(9); s.sqrt()
        assertEquals("3", s.display)

        val p = CalcEngine()
        // 200 + 10% -> base 200, 10% of 200 = 20
        p.digit(2); p.digit(0); p.digit(0); p.op('+'); p.digit(1); p.digit(0); p.percent()
        assertEquals("20", p.display)
    }

    @Test
    fun calc_engine_memory() {
        val e = CalcEngine()
        e.digit(5); e.memPlus()           // M = 5
        assertTrue(e.hasMemory)
        e.digit(2); e.memPlus()           // M = 7
        e.memRecall()
        assertEquals("7", e.display)
    }

    @Test
    fun calc_engine_overflow_is_error() {
        val e = CalcEngine()
        // 999999999 * 999999999 = ~1e18 -> more than 15 digits -> Error
        "999999999".forEach { e.digit(it - '0') }
        e.op('*')
        "999999999".forEach { e.digit(it - '0') }
        e.equals()
        assertEquals("Error", e.display)
    }

    @Test
    fun calc_engine_divide_by_zero_is_error() {
        val e = CalcEngine()
        e.digit(5); e.op('/'); digitZero(e); e.equals()
        assertEquals("Error", e.display)
    }

    private fun digitZero(e: CalcEngine) = e.digit(0)
}

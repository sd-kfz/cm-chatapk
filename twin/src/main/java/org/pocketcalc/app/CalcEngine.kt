package org.pocketcalc.app

import kotlin.math.sqrt


class CalcEngine {
    var display: String = "0"; private set
    var memory: Double = 0.0; private set
    var hasMemory: Boolean = false; private set

    private var acc: Double? = null
    private var pending: Char? = null
    private var fresh = true
    private var lastCe = false

    private fun current(): Double = display.toDoubleOrNull() ?: 0.0

    fun digit(d: Int) {
        lastCe = false
        val next = if (fresh || display == "0") d.toString() else display + d.toString()

        if (next.count { it.isDigit() } > MAX_DIGITS) return
        display = next
        fresh = false
    }

    fun dot() {
        lastCe = false
        if (fresh) { display = "0."; fresh = false }
        else if (!display.contains('.')) display += "."
    }


    fun op(o: Char) {
        lastCe = false
        val cur = current()
        acc = if (acc == null || pending == null) cur else apply(acc!!, cur, pending!!)
        display = format(acc!!)
        pending = o
        fresh = true
    }

    fun equals() {
        lastCe = false
        val p = pending ?: return
        val result = apply(acc ?: 0.0, current(), p)
        display = format(result)
        acc = null; pending = null; fresh = true
    }

    fun percent() {

        lastCe = false
        val base = acc ?: current()
        val result = base * current() / 100.0
        display = format(result); fresh = true
    }

    fun sqrt() {
        lastCe = false
        val v = current()
        display = if (v < 0) "Error" else format(sqrt(v))
        fresh = true
    }


    fun clearCe() {
        if (lastCe) { acc = null; pending = null; memory = memory; }
        display = "0"; fresh = true
        lastCe = !lastCe
    }

    fun memPlus() { memory += current(); hasMemory = memory != 0.0; fresh = true }
    fun memMinus() { memory -= current(); hasMemory = memory != 0.0; fresh = true }


    fun memRecall() {
        if (display == format(memory) && !fresh) { memory = 0.0; hasMemory = false; display = "0" }
        else display = format(memory)
        fresh = true
    }

    private fun apply(a: Double, b: Double, o: Char): Double = when (o) {
        '+' -> a + b
        '-' -> a - b
        '*' -> a * b
        '/' -> if (b == 0.0) Double.NaN else a / b
        else -> b
    }

    private fun format(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return ERROR

        if (d == Math.floor(d) && !d.isInfinite() && kotlin.math.abs(d) < 1e15) {
            return d.toLong().toString()
        }
        if (kotlin.math.abs(d) >= 1e15) return ERROR

        var s = java.math.BigDecimal(d).round(java.math.MathContext(MAX_DIGITS)).toPlainString()
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return if (s.replace("-", "").replace(".", "").length > MAX_DIGITS) ERROR else s
    }

    companion object {
        const val MAX_DIGITS = 15
        const val ERROR = "Error"
    }
}

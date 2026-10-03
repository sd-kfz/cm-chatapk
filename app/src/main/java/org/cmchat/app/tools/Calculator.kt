package org.cmchat.app.tools

/**
 * Basic arithmetic evaluator: + - * / with precedence, parentheses, decimals
 * and unary minus. Offline, no dependencies. Returns null on a malformed
 * expression or division by zero.
 */
object Calculator {

    fun eval(expr: String): Double? = runCatching { evalRpn(toRpn(tokenize(expr))) }.getOrNull()

    private sealed interface Tok
    private data class Num(val v: Double) : Tok
    private data class Op(val c: Char) : Tok
    private data object LParen : Tok
    private data object RParen : Tok

    private fun tokenize(s: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    out.add(Num(s.substring(start, i).toDouble()))
                }
                c == '(' -> { out.add(LParen); i++ }
                c == ')' -> { out.add(RParen); i++ }
                c in "+-*/" -> {
                    // unary minus/plus: at start or after an operator/left paren
                    val unary = out.isEmpty() || out.last() is Op || out.last() is LParen
                    if ((c == '-' || c == '+') && unary) {
                        out.add(Num(0.0)); out.add(Op(c))
                    } else out.add(Op(c))
                    i++
                }
                else -> throw IllegalArgumentException("bad char $c")
            }
        }
        return out
    }

    private fun prec(c: Char) = if (c == '+' || c == '-') 1 else 2

    private fun toRpn(tokens: List<Tok>): List<Tok> {
        val out = ArrayList<Tok>()
        val ops = ArrayDeque<Tok>()
        for (t in tokens) when (t) {
            is Num -> out.add(t)
            is Op -> {
                while (ops.isNotEmpty() && ops.last() is Op &&
                    prec((ops.last() as Op).c) >= prec(t.c)) out.add(ops.removeLast())
                ops.addLast(t)
            }
            LParen -> ops.addLast(t)
            RParen -> {
                while (ops.isNotEmpty() && ops.last() != LParen) out.add(ops.removeLast())
                require(ops.isNotEmpty()) { "mismatched parens" }
                ops.removeLast()
            }
        }
        while (ops.isNotEmpty()) {
            val o = ops.removeLast()
            require(o is Op) { "mismatched parens" }
            out.add(o)
        }
        return out
    }

    private fun evalRpn(rpn: List<Tok>): Double {
        val st = ArrayDeque<Double>()
        for (t in rpn) when (t) {
            is Num -> st.addLast(t.v)
            is Op -> {
                val b = st.removeLast(); val a = st.removeLast()
                st.addLast(
                    when (t.c) {
                        '+' -> a + b; '-' -> a - b; '*' -> a * b
                        '/' -> { require(b != 0.0) { "div by zero" }; a / b }
                        else -> throw IllegalArgumentException()
                    }
                )
            }
            else -> throw IllegalArgumentException("stray paren")
        }
        require(st.size == 1) { "bad expression" }
        return st.last()
    }
}

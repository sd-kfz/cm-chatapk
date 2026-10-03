package org.cmchat.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private val EyeBlue = Color(0xFF7FE7FF)
private val EyeRed = Color(0xFFFF3B3B)

@Composable
fun CerberusMark(on: Boolean, sizeDp: Int = 18, modifier: Modifier = Modifier) {
    Canvas(modifier.size(sizeDp.dp)) {
        val s = size.minDimension / 36f
        val ink = if (on) EyeBlue else EyeRed
        val cx = 18f; val cy = 18f; val r = 15f
        val inner = 15f / 1.6180339887f
        val ang = listOf(-90f, -30f, 30f, 90f, 150f, 210f)
        fun rd(a: Float) = a * Math.PI.toFloat() / 180f
        fun v(a: Float) = Offset(cx + r * cos(rd(a)), cy + r * sin(rd(a)))
        val V = ang.map { v(it) }
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun toward(fr: Offset, to: Offset, d: Float): Offset {
            val dx = to.x - fr.x; val dy = to.y - fr.y
            val L = hypot(dx, dy).let { if (it == 0f) 1f else it }
            return Offset(fr.x + dx / L * d, fr.y + dy / L * d)
        }
        val sw = 1.2f * s
        val round = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)

        if (on) {
            val hex = Path()
            for (i in 0 until 6) {
                val prev = V[(i + 5) % 6]; val cur = V[i]; val nxt = V[(i + 1) % 6]
                val a = toward(cur, prev, 5f); val b = toward(cur, nxt, 5f)
                if (i == 0) hex.moveTo(a.x * s, a.y * s) else hex.lineTo(a.x * s, a.y * s)
                hex.quadraticTo(cur.x * s, cur.y * s, b.x * s, b.y * s)
            }
            hex.close()
            drawCircle(ink, (r + 1.5f) * s, p(cx, cy), style = Stroke(width = sw), alpha = 0.12f)
            drawPath(hex, ink, alpha = 0.9f, style = round)
            drawCircle(ink, inner * s, p(cx, cy), style = Stroke(width = sw), alpha = 0.55f)
            for (a in ang) {
                val i1 = Offset(cx + inner * cos(rd(a)), cy + inner * sin(rd(a)))
                val o1 = v(a)
                drawLine(ink, p(i1.x, i1.y), p(o1.x, o1.y), strokeWidth = sw, alpha = 0.24f)
            }
            drawCircle(ink, 5.2f * s, p(cx, cy), style = Stroke(width = sw), alpha = 0.3f)
            drawCircle(ink, 3.6f * s, p(cx, cy), alpha = 0.92f)
        } else {
            val dip = 30f * (r / 33f)
            val top = Offset(cx, cy - r + dip)
            val bodyPts = listOf(V[5], V[4], V[3], V[2], V[1])
            val body = Path()
            body.moveTo(bodyPts[0].x * s, bodyPts[0].y * s)
            for (k in 1 until bodyPts.size) body.lineTo(bodyPts[k].x * s, bodyPts[k].y * s)
            drawPath(body, ink, alpha = 0.9f, style = round)
            drawCircle(ink, inner * s, p(cx, cy), style = Stroke(width = sw), alpha = 0.45f)
            for (a in ang.drop(1)) {
                val i1 = Offset(cx + inner * cos(rd(a)), cy + inner * sin(rd(a)))
                val o1 = v(a)
                drawLine(ink, p(i1.x, i1.y), p(o1.x, o1.y), strokeWidth = sw, alpha = 0.34f)
            }
            val brow = Path()
            brow.moveTo(V[5].x * s, V[5].y * s)
            brow.lineTo(top.x * s, top.y * s)
            brow.lineTo(V[1].x * s, V[1].y * s)
            drawPath(brow, ink, alpha = 0.95f, style = Stroke(width = 1.7f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(ink, 1.4f * s, p(cx, cy))
        }
    }
}

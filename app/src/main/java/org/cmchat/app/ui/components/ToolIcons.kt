package org.cmchat.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * The tool icons on the Friends screen, DRAWN in the same thin-stroke style as
 * the minimise / power icons (font glyphs like ▦ ☑ ☀ look different on every
 * phone, or show as empty boxes).
 */
enum class ToolIcon { CALCULATOR, NOTES, FLASHLIGHT }

@Composable
fun ToolGlyph(icon: ToolIcon, color: Color, sizeDp: Int = 24) {
    Canvas(Modifier.size(sizeDp.dp)) {
        val s = size.minDimension / 24f
        val stroke = Stroke(width = 1.9f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (icon) {
            ToolIcon.CALCULATOR -> {
                roundRect(color, 5f, 2.5f, 19f, 21.5f, 2.5f, s, stroke)
                roundRect(color, 8f, 5.5f, 16f, 9.5f, 1f, s, stroke)
                for (r in 0..2) for (c in 0..2) {
                    drawCircle(color, radius = 1.1f * s, center = Offset((8.5f + 3.5f * c) * s, (13f + 3.3f * r) * s))
                }
            }
            ToolIcon.NOTES -> {
                roundRect(color, 5f, 2.5f, 19f, 21.5f, 2.5f, s, stroke)
                for (y in listOf(8f, 12f, 16f)) {
                    drawLine(color, Offset(8.5f * s, y * s), Offset(15.5f * s, y * s), strokeWidth = 1.9f * s, cap = StrokeCap.Round)
                }
            }
            ToolIcon.FLASHLIGHT -> {
                val head = Path().apply {
                    moveTo(7f * s, 3f * s); lineTo(17f * s, 3f * s); lineTo(15f * s, 9f * s); lineTo(9f * s, 9f * s); close()
                }
                drawPath(head, color, style = stroke)
                roundRect(color, 9f, 9f, 15f, 21.5f, 1.5f, s, stroke)
                drawCircle(color, radius = 1.2f * s, center = Offset(12f * s, 14f * s))
            }
        }
    }
}

private fun DrawScope.roundRect(c: Color, l: Float, t: Float, r: Float, b: Float, rad: Float, s: Float, st: Stroke) =
    drawRoundRect(c, topLeft = Offset(l * s, t * s), size = Size((r - l) * s, (b - t) * s),
        cornerRadius = CornerRadius(rad * s, rad * s), style = st)

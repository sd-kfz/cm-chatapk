package org.cmchat.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp


@Composable
fun PowerGlyph(color: Color, sizeDp: Int = 24, strokeUnits: Float = 2.2f) {
    Canvas(Modifier.size(sizeDp.dp)) {
        val s = size.minDimension / 24f


        val r = 8f * s
        val cx = 12f * s; val cy = 13.08f * s
        drawArc(
            color = color, startAngle = -49.5f, sweepAngle = 279f, useCenter = false,
            topLeft = Offset(cx - r, cy - r), size = Size(2 * r, 2 * r),
            style = Stroke(width = strokeUnits * s, cap = StrokeCap.Round),
        )
        drawLine(color, Offset(12f * s, 3.5f * s), Offset(12f * s, 11.5f * s),
            strokeWidth = strokeUnits * s, cap = StrokeCap.Round)
    }
}

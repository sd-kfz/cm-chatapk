package org.cmchat.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import org.cmchat.app.ui.theme.CmBlueGlow
import org.cmchat.app.ui.theme.CmRedGlow
import org.cmchat.app.ui.theme.Nunito
import kotlin.math.exp

/**
 * The CM-Chat wordmark with a letter-by-letter glow SWEEP. A bright point moves
 * across the letters once per [sweepMs] (6.5s on the Circle page, ~3.25s on the
 * lock / naming screens). Each letter's glow brightens as the sweep passes it.
 */
@Composable
fun CmChatLogo(modifier: Modifier = Modifier, size: Int = 26, sweepMs: Int = 6500) {
    val text = "CM-Chat"
    val transition = rememberInfiniteTransition(label = "logoSweep")
    // Sweep travels a little past both ends so the edge letters also peak.
    val pos by transition.animateFloat(
        initialValue = -1f,
        targetValue = text.length.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = sweepMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pos",
    )
    Box(modifier) {
        Text(
            buildAnnotatedString {
                text.forEachIndexed { i, ch ->
                    val base = if (i < 4) CmBlueGlow else CmRedGlow      // "CM-C" cyan, "hat" red
                    val d = i - pos
                    val boost = exp(-(d * d) / 1.2f)                      // bright near the sweep
                    val glow = base.copy(alpha = 0.25f + 0.65f * boost)
                    val shadow = Shadow(color = glow, offset = Offset(0f, 0f),
                        blurRadius = 10f + 18f * boost)
                    withStyle(SpanStyle(color = base, shadow = shadow, fontWeight = FontWeight.Bold)) {
                        append(ch.toString())
                    }
                }
            },
            fontFamily = Nunito,
            fontSize = size.sp,
        )
    }
}

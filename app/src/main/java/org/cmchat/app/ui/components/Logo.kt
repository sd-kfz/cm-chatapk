package org.cmchat.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cmchat.app.ui.theme.CmBlueGlow
import org.cmchat.app.ui.theme.CmRedGlow
import org.cmchat.app.ui.theme.CmTextDim
import org.cmchat.app.ui.theme.Nunito
import kotlin.math.exp

/**
 * The CM-Chat wordmark with a letter-by-letter glow SWEEP. A bright point moves
 * across the letters once per [sweepMs]. Each letter's glow brightens (brighter,
 * softer bloom) as the sweep passes it; the whole thing is clipped to the logo's
 * own bounds so the glow never spills past its edges.
 *
 * When [active] is false (e.g. Tor not connected on the Circle page) the logo is
 * rendered desaturated grey with NO glow and NO sweep. The login/naming screen
 * always passes active = true.
 */
@Composable
fun CmChatLogo(modifier: Modifier = Modifier, size: Int = 26, sweepMs: Int = 6500, active: Boolean = true) {
    val text = "CM-Chat"
    val transition = rememberInfiniteTransition(label = "logoSweep")
    val pos by transition.animateFloat(
        initialValue = -1f,
        targetValue = text.length.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = sweepMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pos",
    )
    // A little inner padding gives the bloom room to radiate inside the clipped
    // bounds instead of being cut flush at the glyph edge.
    Box(modifier.clipToBounds().padding(horizontal = 6.dp, vertical = 4.dp)) {
        Text(
            buildAnnotatedString {
                text.forEachIndexed { i, ch ->
                    if (!active) {
                        // Black-and-white, no glow, when the engine is offline.
                        withStyle(SpanStyle(color = CmTextDim, fontWeight = FontWeight.Bold)) {
                            append(ch.toString())
                        }
                        return@forEachIndexed
                    }
                    val base = if (i < 4) CmBlueGlow else CmRedGlow      // "CM-C" cyan, "hat" red
                    val d = i - pos
                    val boost = exp(-(d * d) / 1.1f)                     // bright near the sweep
                    // Stronger, softer bloom: higher floor + bigger peak + wider blur.
                    val glow = base.copy(alpha = 0.45f + 0.9f * boost)
                    val shadow = Shadow(color = glow, offset = Offset(0f, 0f),
                        blurRadius = 16f + 30f * boost)
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

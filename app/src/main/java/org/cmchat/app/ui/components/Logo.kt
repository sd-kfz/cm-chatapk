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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
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

/** "CM-C" cyan, "hat" red, each glowing in its own colour. */
private fun glowWord(blur: Float, alpha: Float): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = CmBlueGlow, shadow = Shadow(CmBlueGlow.copy(alpha = alpha), Offset.Zero, blur))) {
        append("CM-C")
    }
    withStyle(SpanStyle(color = CmRedGlow, shadow = Shadow(CmRedGlow.copy(alpha = alpha), Offset.Zero, blur))) {
        append("hat")
    }
}

private val BASE = glowWord(blur = 16f, alpha = 0.45f)
private val BLOOM = glowWord(blur = 40f, alpha = 1f)

/**
 * The CM-Chat wordmark with a glow SWEEP: a bright bloom moves across the
 * letters once per [sweepMs], over a constant soft glow.
 *
 * Smooth on old phones: both text layers are laid out ONCE. The sweep position
 * is read only in the draw phase, so each frame just repaints one gradient mask
 * over the bloom layer — no recomposition, no re-layout, no per-letter rebuild
 * (rebuilding every letter's blur 60x a second is what made it lag).
 *
 * When [active] is false (e.g. Tor not connected) it is plain grey with no glow
 * and no animation at all (nothing ticks).
 */
@Composable
fun CmChatLogo(modifier: Modifier = Modifier, size: Int = 26, sweepMs: Int = 6500, active: Boolean = true) {
    // Inner padding gives the bloom room to radiate inside the clipped bounds.
    val pad = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
    if (!active) {
        Box(modifier.clipToBounds()) {
            Text("CM-Chat", pad, color = CmTextDim, fontFamily = Nunito, fontSize = size.sp,
                fontWeight = FontWeight.Bold)
        }
        return
    }
    // The sweep position, ~20 times a second (a slow glow needs no more), and
    // only while the screen is actually showing: in the background it stops.
    val sweep = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, sweepMs) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) {
                withFrameMillis { ms -> sweep.floatValue = (ms % sweepMs) / sweepMs.toFloat() }
                delay(50)
            }
        }
    }
    Box(modifier.clipToBounds()) {
        // Constant soft glow.
        Text(BASE, pad, fontFamily = Nunito, fontSize = size.sp, fontWeight = FontWeight.Bold)
        // The bright bloom, shown only under a moving band.
        Text(
            BLOOM,
            Modifier
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val w = this.size.width   // the DrawScope's size, not the font size
                    val band = w * 0.3f
                    // The band enters from off the left edge and leaves off the right.
                    val cx = -band + (w + 2 * band) * sweep.floatValue
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0f to Color.Transparent, 0.5f to Color.Black, 1f to Color.Transparent,
                            startX = cx - band, endX = cx + band,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
                .then(pad),
            fontFamily = Nunito, fontSize = size.sp, fontWeight = FontWeight.Bold,
        )
    }
}

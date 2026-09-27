package app.voidfiles.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import app.voidfiles.ui.theme.VoidTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Ring of dots, Nothing Glyph style. With a [progress] the ring fills clockwise;
 * without one a bright segment runs around it.
 */
@Composable
fun DotRing(progress: Float?, modifier: Modifier = Modifier, dots: Int = 24) {
    val c = VoidTheme.colors
    val spin = rememberInfiniteTransition(label = "ring")
    val head by spin.animateFloat(
        0f, dots.toFloat(),
        infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "head",
    )
    Canvas(modifier) {
        val radius = min(size.width, size.height) / 2f
        val dotR = radius * 0.09f
        val ringR = radius - dotR
        val center = Offset(size.width / 2, size.height / 2)
        for (i in 0 until dots) {
            val angle = -PI / 2 + 2 * PI * i / dots
            val pos = Offset(center.x + (ringR * cos(angle)).toFloat(), center.y + (ringR * sin(angle)).toFloat())
            val color = if (progress != null) {
                if (i < (progress.coerceIn(0f, 1f) * dots).toInt()) c.accent else c.textMuted.copy(alpha = 0.25f)
            } else {
                val dist = ((head - i) % dots + dots) % dots
                if (dist < 6) c.accent.copy(alpha = 1f - dist / 6f) else c.textMuted.copy(alpha = 0.2f)
            }
            drawCircle(color, dotR, pos)
        }
    }
}

/** Five pulsing dots, used as a loading indicator. */
@Composable
fun DotLoader(modifier: Modifier = Modifier, dots: Int = 5) {
    val c = VoidTheme.colors
    val t = rememberInfiniteTransition(label = "loader")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Canvas(modifier) {
        val gap = 10.dp.toPx()
        val r = min(size.height / 2, 2.4.dp.toPx())
        val total = gap * (dots - 1)
        val start = size.width / 2 - total / 2
        for (i in 0 until dots) {
            val local = ((phase * dots - i) % dots + dots) % dots
            val a = if (local < 1f) 1f - local else 0.25f
            drawCircle(c.accent.copy(alpha = a.coerceIn(0.25f, 1f)), r, Offset(start + gap * i, size.height / 2))
        }
    }
}

/** A ring of dots bursting outwards – played once whenever [trigger] changes (e.g. after a copy). */
@Composable
fun GlyphFlash(trigger: Int, modifier: Modifier = Modifier) {
    val c = VoidTheme.colors
    val anim = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        anim.snapTo(0f)
        anim.animateTo(1f, tween(750, easing = FastOutSlowInEasing))
    }
    val p = anim.value
    if (p >= 1f) return
    Canvas(modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val maxR = min(size.width, size.height) / 2f
        for (ring in 0 until 2) {
            val rp = (p - ring * 0.15f).coerceIn(0f, 1f)
            if (rp <= 0f) continue
            val r = maxR * (0.25f + 0.75f * rp)
            val dots = 18 + ring * 10
            for (i in 0 until dots) {
                val angle = 2 * PI * i / dots
                drawCircle(
                    c.accent.copy(alpha = (1f - rp) * 0.9f),
                    radius = 3.dp.toPx() * (1f - rp * 0.5f),
                    center = Offset(center.x + (r * cos(angle)).toFloat(), center.y + (r * sin(angle)).toFloat()),
                )
            }
        }
    }
}

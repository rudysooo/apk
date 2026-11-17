package com.rudysooo.messages

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Horloge partagée du fond animé : tous les écrans restent synchronisés pendant les transitions. */
val LocalAurora = staticCompositionLocalOf<State<Float>> { mutableFloatStateOf(0.3f) }

@Composable
fun rememberAuroraClock(animated: Boolean): State<Float> {
    val t = rememberInfiniteTransition(label = "aurora")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(32_000, easing = LinearEasing)), label = "t")
    val still = remember { mutableFloatStateOf(0.3f) }
    return if (animated) t else still
}

private fun shiftHue(c: Color, deg: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[0] = (hsv[0] + deg + 360f) % 360f
    hsv[1] = (hsv[1] * 1.05f).coerceAtMost(1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

@Composable
fun isDark() = MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * Fond « aurore » : quatre halos de couleur qui dérivent lentement.
 * La valeur animée n'est lue qu'au dessin : aucune recomposition, juste un redessin léger.
 */
fun Modifier.aurora(intensity: Float = 1f): Modifier = composed {
    val clock = LocalAurora.current
    val dark = isDark()
    val accent = MaterialTheme.colorScheme.primary
    val colors = remember(accent) { listOf(accent, shiftHue(accent, 48f), shiftHue(accent, -60f), shiftHue(accent, 165f)) }
    val base = if (dark) Color(0xFF05060B) else Color(0xFFF2F3F9)
    val alpha = (if (dark) 0.55f else 0.38f) * intensity
    // vitesses entières : la boucle se referme sans à-coup
    val sx = intArrayOf(1, 2, 1, 1); val sy = intArrayOf(1, 1, 2, 1)
    val ph = floatArrayOf(0f, 1.7f, 3.4f, 5.1f); val rf = floatArrayOf(0.62f, 0.55f, 0.58f, 0.5f)
    drawBehind {
        drawRect(base)
        val v = clock.value * 2f * PI.toFloat()
        val w = size.width; val h = size.height; val r0 = maxOf(w, h)
        for (i in colors.indices) {
            val cx = w * (0.5f + 0.42f * sin(v * sx[i] + ph[i]))
            val cy = h * (0.42f + 0.38f * cos(v * sy[i] + ph[i] * 1.3f))
            val r = r0 * rf[i] * (0.9f + 0.1f * sin(v * 2f + i))
            val c = colors[i]
            drawCircle(
                Brush.radialGradient(listOf(c.copy(alpha = alpha), c.copy(alpha = alpha * 0.35f), Color.Transparent), Offset(cx, cy), r),
                r, Offset(cx, cy),
            )
        }
    }
}

/** Surface en « verre » translucide posée sur le fond animé. */
fun Modifier.glass(shape: Shape, strong: Boolean = false): Modifier = composed {
    val dark = isDark()
    val fill = if (dark) Color.White.copy(alpha = if (strong) 0.11f else 0.07f) else Color.White.copy(alpha = if (strong) 0.86f else 0.66f)
    val stroke = if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.95f)
    clip(shape).background(fill, shape).border(0.6.dp, stroke, shape)
}

/** Couleur de barre semi-opaque, pour que le texte qui défile dessous reste lisible. */
@Composable
fun barColor() = if (isDark()) Color(0xFF05060B).copy(alpha = 0.78f) else Color(0xFFF2F3F9).copy(alpha = 0.82f)

package com.rudysooo.messages

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/** 0 = couleurs du système (Material You, Android 12+). */
val Accents = listOf(0, 0xFF2B6CF6, 0xFF7C4DFF, 0xFF00A884, 0xFFE91E63, 0xFFFF7043, 0xFF00ACC1).map { it.toInt() }

val AvatarColors = listOf(
    0xFF5C6BC0, 0xFF26A69A, 0xFFEF6C00, 0xFFAB47BC, 0xFF42A5F5, 0xFFEC407A, 0xFF66BB6A, 0xFF8D6E63,
).map { Color(it.toInt()) }

private fun scheme(accent: Int, dark: Boolean): ColorScheme {
    val p = Color(accent)
    val onP = if (p.luminance() > 0.55f) Color.Black else Color.White
    return if (dark) darkColorScheme(
        primary = p, onPrimary = onP,
        primaryContainer = p.copy(alpha = 1f).let { Color(it.red * 0.35f, it.green * 0.35f, it.blue * 0.35f) },
        onPrimaryContainer = Color.White,
        background = Color(0xFF0F1115), onBackground = Color(0xFFE8EAF0),
        surface = Color(0xFF0F1115), onSurface = Color(0xFFE8EAF0),
        surfaceVariant = Color(0xFF232731), onSurfaceVariant = Color(0xFFB4B9C6),
        surfaceContainer = Color(0xFF181B22), surfaceContainerHigh = Color(0xFF20242C),
        outline = Color(0xFF4A4F5C),
    ) else lightColorScheme(
        primary = p, onPrimary = onP,
        primaryContainer = Color(p.red * 0.25f + 0.75f, p.green * 0.25f + 0.75f, p.blue * 0.25f + 0.75f),
        onPrimaryContainer = Color(0xFF111418),
        background = Color(0xFFF6F7FB), onBackground = Color(0xFF14171C),
        surface = Color(0xFFF6F7FB), onSurface = Color(0xFF14171C),
        surfaceVariant = Color(0xFFE6E9F1), onSurfaceVariant = Color(0xFF50555F),
        surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFEDEFF5),
        outline = Color(0xFFB9BDC9),
    )
}

@Composable
fun isDarkTheme(s: Settings) = when (s.themeMode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }

@Composable
fun MessagesTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = isDarkTheme(settings)
    val ctx = LocalContext.current
    val colors = if (settings.accent == 0 && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else scheme(if (settings.accent == 0) Accents[1] else settings.accent, dark)
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density, base.fontScale * settings.textScale)) {
        MaterialTheme(
            colorScheme = colors,
            shapes = Shapes(
                small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}

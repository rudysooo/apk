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
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle

@OptIn(ExperimentalTextApi::class)
private fun jakarta(w: Int) = Font(R.font.jakarta, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val AppFont = FontFamily(jakarta(400), jakarta(500), jakarta(600), jakarta(700), jakarta(800))

private fun TextStyle.f() = copy(fontFamily = AppFont)

private val AppTypography = Typography().let { t ->
    Typography(
        t.displayLarge.f(), t.displayMedium.f(), t.displaySmall.f(),
        t.headlineLarge.f(), t.headlineMedium.f(), t.headlineSmall.f(),
        t.titleLarge.f(), t.titleMedium.f(), t.titleSmall.f(),
        t.bodyLarge.f(), t.bodyMedium.f(), t.bodySmall.f(),
        t.labelLarge.f(), t.labelMedium.f(), t.labelSmall.f(),
    )
}

/** 0 = couleurs du système (Material You, Android 12+). */
val Accents = listOf(0, 0xFF0A84FF, 0xFF30D158, 0xFFFF9F0A, 0xFFFF375F, 0xFFBF5AF2, 0xFF64D2FF).map { it.toInt() }

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
        background = Color(0xFF000000), onBackground = Color(0xFFF2F2F7),
        surface = Color(0xFF000000), onSurface = Color(0xFFF2F2F7),
        surfaceVariant = Color(0xFF2C2C2E), onSurfaceVariant = Color(0xFF8E8E93),
        surfaceContainer = Color(0xFF1C1C1E), surfaceContainerHigh = Color(0xFF2C2C2E),
        outline = Color(0xFF3A3A3C),
    ) else lightColorScheme(
        primary = p, onPrimary = onP,
        primaryContainer = Color(p.red * 0.25f + 0.75f, p.green * 0.25f + 0.75f, p.blue * 0.25f + 0.75f),
        onPrimaryContainer = Color(0xFF111418),
        background = Color(0xFFF2F2F7), onBackground = Color(0xFF000000),
        surface = Color(0xFFF2F2F7), onSurface = Color(0xFF000000),
        surfaceVariant = Color(0xFFE5E5EA), onSurfaceVariant = Color(0xFF8E8E93),
        surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFE9E9EB),
        outline = Color(0xFFC6C6C8),
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
            typography = AppTypography,
            shapes = Shapes(
                small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}

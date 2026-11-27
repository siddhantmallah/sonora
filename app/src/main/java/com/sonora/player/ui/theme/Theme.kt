package com.sonora.player.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.sonora.player.data.AppSettings
import com.sonora.player.data.ThemeMode

/** Sonora brand colours. */
object Brand {
    val Pink = Color(0xFFFF2D6F)
    val Coral = Color(0xFFFF5A5F)
    val Violet = Color(0xFF7C4DFF)
    val Indigo = Color(0xFF4C3CFF)
    val Gold = Color(0xFFFFC94D)

    /** Signature gradient used for hero cards, Liked songs, Replay. */
    val gradient = Brush.linearGradient(listOf(Violet, Pink, Coral))

    // iOS-like system greys for grouped lists.
    val DarkBg = Color(0xFF000000)
    val DarkCell = Color(0xFF1C1C1E)
    val DarkCell2 = Color(0xFF2C2C2E)
    val DarkSeparator = Color(0xFF38383A)
    val Gray = Color(0xFF8E8E93)
    val LightBg = Color(0xFFF2F2F7)
    val LightCell = Color(0xFFFFFFFF)
    val LightCell2 = Color(0xFFE5E5EA)
    val LightSeparator = Color(0xFFC6C6C8)
}

/** Sonora's dark palette; also used for glass sheets on the player. */
val DarkScheme = darkColorScheme(
    primary = Brand.Pink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A0F22),
    onPrimaryContainer = Color(0xFFFFD9E3),
    secondary = Brand.Violet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2A1F4D),
    onSecondaryContainer = Color(0xFFE6DEFF),
    tertiary = Brand.Coral,
    background = Brand.DarkBg,
    onBackground = Color.White,
    surface = Brand.DarkBg,
    onSurface = Color.White,
    onSurfaceVariant = Brand.Gray,
    surfaceContainerLowest = Brand.DarkBg,
    surfaceContainerLow = Color(0xFF111113),
    surfaceContainer = Brand.DarkCell,
    surfaceContainerHigh = Brand.DarkCell,
    surfaceContainerHighest = Brand.DarkCell2,
    outline = Brand.DarkSeparator,
    outlineVariant = Brand.DarkSeparator,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFFE8195B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E3),
    onPrimaryContainer = Color(0xFF3E0018),
    secondary = Brand.Violet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E1FF),
    onSecondaryContainer = Color(0xFF22005D),
    tertiary = Brand.Coral,
    background = Brand.LightBg,
    onBackground = Color.Black,
    surface = Brand.LightBg,
    onSurface = Color.Black,
    onSurfaceVariant = Color(0xFF6C6C70),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F7FA),
    surfaceContainer = Brand.LightCell,
    surfaceContainerHigh = Brand.LightCell,
    surfaceContainerHighest = Brand.LightCell2,
    outline = Brand.LightSeparator,
    outlineVariant = Brand.LightSeparator,
)

/** iOS-flavoured type scale: heavy large titles, tight tracking, comfortable body text. */
private val AppTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
        headlineMedium = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
        headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp),
        bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.1).sp),
        bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
        labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun isDark(settings: AppSettings): Boolean = when (settings.themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun SonoraTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = isDark(settings)
    val context = LocalContext.current
    var scheme: ColorScheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    // "Pure black" only matters for Material You; the Sonora dark theme is already true black.
    if (dark && settings.amoled && settings.dynamicColor) {
        scheme = scheme.copy(background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black)
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}

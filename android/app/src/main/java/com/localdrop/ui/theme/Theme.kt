package com.localdrop.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val LocalDropDarkScheme = darkColorScheme(
    primary = Color(0xFFC0C1FF),
    onPrimary = Color(0xFF1000A9),
    primaryContainer = Color(0xFF8083FF),
    onPrimaryContainer = Color(0xFF0D0096),
    inversePrimary = Color(0xFF494BD6),
    secondary = Color(0xFF4EDEA3),
    onSecondary = Color(0xFF003824),
    secondaryContainer = Color(0xFF00A572),
    onSecondaryContainer = Color(0xFF00311F),
    tertiary = Color(0xFF4CD7F6),
    onTertiary = Color(0xFF003640),
    tertiaryContainer = Color(0xFF009EB9),
    onTertiaryContainer = Color(0xFF002F38),
    background = Color(0xFF0D1322),
    onBackground = Color(0xFFDDE2F8),
    surface = Color(0xFF0D1322),
    onSurface = Color(0xFFDDE2F8),
    surfaceVariant = Color(0xFF2F3445),
    onSurfaceVariant = Color(0xFFC7C4D7),
    surfaceContainerLowest = Color(0xFF080E1D),
    surfaceContainerLow = Color(0xFF151B2B),
    surfaceContainer = Color(0xFF191F2F),
    surfaceContainerHigh = Color(0xFF242A3A),
    surfaceContainerHighest = Color(0xFF2F3445),
    outline = Color(0xFF908FA0),
    outlineVariant = Color(0xFF464554),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LocalDropLightScheme = lightColorScheme(
    primary = Color(0xFF494BD6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1E0FF),
    onPrimaryContainer = Color(0xFF0D0096),
    secondary = Color(0xFF00875A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF9DF8CC),
    onSecondaryContainer = Color(0xFF002113),
    tertiary = Color(0xFF007B92),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFACEDFF),
    onTertiaryContainer = Color(0xFF001F26),
    background = Color(0xFFF6F7FC),
    onBackground = Color(0xFF151B2B),
    surface = Color(0xFFF6F7FC),
    onSurface = Color(0xFF151B2B),
    surfaceVariant = Color(0xFFE3E6F2),
    onSurfaceVariant = Color(0xFF464554),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F2FA),
    surfaceContainer = Color(0xFFE8EBF6),
    surfaceContainerHigh = Color(0xFFDDE2F2),
    surfaceContainerHighest = Color(0xFFD2D8EC),
)

private val LocalDropTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, lineHeight = 14.sp),
)

private val LocalDropShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

enum class ThemeMode { System, Dark, Light }

@Composable
fun LocalDropTheme(themeMode: ThemeMode = ThemeMode.Dark, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = if (dark) LocalDropDarkScheme else LocalDropLightScheme,
        typography = LocalDropTypography,
        shapes = LocalDropShapes,
        content = content
    )
}

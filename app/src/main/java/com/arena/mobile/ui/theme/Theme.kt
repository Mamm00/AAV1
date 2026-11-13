package com.arena.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Arena brand-adjacent palette. Dark is the default experience.
val ArenaBlue = Color(0xFF4F7CFF)
val ArenaBlueDim = Color(0xFF2E4BB8)
val ArenaSurfaceDark = Color(0xFF0E1116)
val ArenaSurfaceElevatedDark = Color(0xFF171B22)
val ArenaSurfaceLight = Color(0xFFF7F8FA)
val ArenaAmber = Color(0xFFFFB300)
val ArenaGreen = Color(0xFF2ECC71)
val ArenaRed = Color(0xFFE74C3C)

val DarkColorScheme = darkColorScheme(
    primary = ArenaBlue,
    onPrimary = Color.White,
    secondary = ArenaAmber,
    background = ArenaSurfaceDark,
    onBackground = Color(0xFFE6E9EF),
    surface = ArenaSurfaceDark,
    onSurface = Color(0xFFE6E9EF),
    surfaceVariant = ArenaSurfaceElevatedDark,
    onSurfaceVariant = Color(0xFFA7AEBB),
    error = ArenaRed,
)

val LightColorScheme = lightColorScheme(
    primary = ArenaBlueDim,
    onPrimary = Color.White,
    secondary = ArenaAmber,
    background = ArenaSurfaceLight,
    onBackground = Color(0xFF12151A),
    surface = Color.White,
    onSurface = Color(0xFF12151A),
    surfaceVariant = Color(0xFFEDF0F5),
    onSurfaceVariant = Color(0xFF4A5260),
    error = ArenaRed,
)

private val ArenaTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    // Monospace style used by Agent bash output and the code editor.
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

/**
 * App theme. Dark by default; [darkTheme] is resolved by MainActivity from
 * user preferences (DARK/LIGHT/SYSTEM).
 */
@Composable
fun ArenaTheme(
    darkTheme: Boolean = true,
    colorScheme: androidx.compose.material3.ColorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ArenaTypography,
        content = content,
    )
}

/** Minimum tap target per material/adaptive guidelines. */
val MinTouchTarget = 48.dp

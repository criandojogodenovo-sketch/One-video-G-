package com.onevideog.editor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// One Video G palette: charcoal dark, restrained amber accent.
val Amber = Color(0xFFF98A0B)
val AmberDim = Color(0xFFB36A14)
val Charcoal = Color(0xFF0F0F17)
val SurfaceDark = Color(0xFF171821)
val SurfaceVariantDark = Color(0xFF1F2029)
val TextPrimary = Color(0xFFECEBF0)
val TextSecondary = Color(0xFFA7A9B3)
val DangerRed = Color(0xFFE2574C)

private val DarkScheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1A1205),
    secondary = Color(0xFFCFD0D8),
    onSecondary = Color(0xFF16171E),
    background = Charcoal,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondary,
    error = DangerRed,
    onError = Color.White,
    outline = Color(0xFF3A3B45),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFFC96F00),
    onPrimary = Color.White,
    secondary = Color(0xFF45464F),
    background = Color(0xFFFAF9F7),
    onBackground = Color(0xFF1B1C22),
    surface = Color.White,
    onSurface = Color(0xFF1B1C22),
    surfaceVariant = Color(0xFFEFEDEA),
    onSurfaceVariant = Color(0xFF5A5B64),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

@Composable
fun OvgTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}

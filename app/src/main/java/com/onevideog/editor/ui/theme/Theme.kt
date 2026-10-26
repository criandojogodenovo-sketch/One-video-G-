package com.onevideog.editor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * One Video G — v0.9.2 visual identity, derived from the app icon:
 * deep-black canvas, glazed metallic orange accents and graphite-metal
 * surfaces with a thin light edge on top (light 2.5D feel, gradient/shadow
 * only — no heavy blur, no shaders, so it stays smooth on weak GPUs).
 */

// Core identity.
val BgDeep = Color(0xFF050505)          // deep black canvas
val BgElevated = Color(0xFF0D0F14)      // elevated deep blue-black
val OrangeHi = Color(0xFFFFB347)        // glazed orange highlight
val OrangeMid = Color(0xFFFF7A00)       // primary orange
val OrangeLo = Color(0xFFF25C05)        // deep orange
val Amber = OrangeMid                   // legacy alias (theme accent)
val AmberDim = Color(0xFFB36A14)        // legacy alias

// Graphite-metal surfaces (dark).
val Graphite1 = Color(0xFF1A1D24)       // raised graphite
val Graphite0 = Color(0xFF12141A)       // base graphite surface
val GraphiteDim = Color(0xFF0B0C10)     // recessed panel
val EdgeLight = Color(0xFF3A3F4C)       // thin lit edge / outline

// Text.
val TextPrimary = Color(0xFFECEBF0)
val TextSecondary = Color(0xFFB4B6C2)
val TextFaint = Color(0xFF7E828F)

val DangerRed = Color(0xFFE2574C)
val SuccessGreen = Color(0xFF55B882)

private val DarkScheme = darkColorScheme(
    primary = OrangeMid,
    onPrimary = Color(0xFF1A0E02),
    primaryContainer = Color(0xFF3A2206),
    onPrimaryContainer = OrangeHi,
    secondary = Color(0xFFCFD0D8),
    onSecondary = Color(0xFF16171E),
    tertiary = OrangeLo,
    onTertiary = Color(0xFF1A0E02),
    background = BgDeep,
    onBackground = TextPrimary,
    surface = Graphite0,
    onSurface = TextPrimary,
    surfaceVariant = Graphite1,
    onSurfaceVariant = TextSecondary,
    surfaceDim = GraphiteDim,
    surfaceBright = Color(0xFF232730),
    error = DangerRed,
    onError = Color.White,
    outline = EdgeLight,
    outlineVariant = Color(0xFF262A33),
)

private val LightScheme = lightColorScheme(
    // Light mode stays available via Settings; the same glazed orange leads,
    // surfaces become warm graphite-tinted paper for readability.
    primary = Color(0xFFD97200),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE8CC),
    onPrimaryContainer = Color(0xFF4A2800),
    secondary = Color(0xFF45464F),
    background = Color(0xFFF7F5F2),
    onBackground = Color(0xFF17181D),
    surface = Color.White,
    onSurface = Color(0xFF17181D),
    surfaceVariant = Color(0xFFECE9E4),
    onSurfaceVariant = Color(0xFF5A5B64),
    error = Color(0xFFB3261E),
    onError = Color.White,
    outline = Color(0xFFD4D0C9),
)

@Composable
fun OvgTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = OvgTypography,
        shapes = OvgShapes,
        content = content,
    )
}

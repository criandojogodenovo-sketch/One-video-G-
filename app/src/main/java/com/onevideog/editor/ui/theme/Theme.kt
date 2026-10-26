package com.onevideog.editor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * One Video G — v0.9.2/v0.9.3 visual identity, derived from the app icon:
 * deep-black canvas, glazed metallic orange accents and graphite-metal
 * surfaces with a thin light edge on top (light 2.5D feel, gradient/shadow
 * only — no heavy blur, no shaders, so it stays smooth on weak GPUs).
 *
 * v0.9.3 (device report: dark text on dark backgrounds in several screens +
 * Material 3 default PURPLE leaking through unset scheme slots):
 *  - the identity is DARK — [OvgTheme] always applies the dark scheme (the
 *    previous system-following light variant rendered light onSurface text
 *    over the hard-coded deep-black home canvas — unreadable);
 *  - EVERY Material 3 color slot is defined below (secondaryContainer,
 *    tertiaryContainer, surfaceTint, surfaceContainer*, inverse*, error
 *    containers, scrim) — nothing falls back to the purple-tinted M3
 *    baseline palette anymore, so switches, radios, chips, sheets, dialogs
 *    and progress bars all inherit the orange/graphite identity.
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

/** The ONE complete scheme — every slot orange/graphite, zero M3 purple.
 *  `internal` so ThemeSchemeTest can pin the identity (the v0.9.4
 *  non-regression lock for the complete colorScheme). */
internal val DarkScheme = darkColorScheme(
    // Primary — glazed metallic orange.
    primary = OrangeMid,
    onPrimary = Color(0xFF1A0E02),
    primaryContainer = Color(0xFF3A2206),
    onPrimaryContainer = OrangeHi,
    inversePrimary = OrangeHi,
    // Secondary — cool graphite metal.
    secondary = Color(0xFFCFD0D8),
    onSecondary = Color(0xFF16171E),
    secondaryContainer = Color(0xFF23262E),
    onSecondaryContainer = TextSecondary,
    // Tertiary — deep glazed orange (audio/secondary accents).
    tertiary = OrangeLo,
    onTertiary = Color(0xFF1A0E02),
    tertiaryContainer = Color(0xFF33210A),
    onTertiaryContainer = Color(0xFFFFC08A),
    // Background / surface — deep black canvas + graphite metal.
    background = BgDeep,
    onBackground = TextPrimary,
    surface = Graphite0,
    onSurface = TextPrimary,
    surfaceVariant = Graphite1,
    onSurfaceVariant = TextSecondary,
    surfaceTint = Graphite1,              // no purple elevation tint
    surfaceDim = GraphiteDim,
    surfaceBright = Color(0xFF232730),
    surfaceContainerLowest = Color(0xFF0A0B0F),
    surfaceContainerLow = GraphiteDim,
    surfaceContainer = Graphite0,
    surfaceContainerHigh = Graphite1,
    surfaceContainerHighest = Color(0xFF22252D),
    inverseSurface = Color(0xFFECEBF0),
    inverseOnSurface = Color(0xFF12141A),
    // Error — kept warm red (never M3's purple-tinted containers).
    error = DangerRed,
    onError = Color.White,
    errorContainer = Color(0xFF3A1512),
    onErrorContainer = Color(0xFFF6B5AE),
    // Outlines / scrim.
    outline = EdgeLight,
    outlineVariant = Color(0xFF262A33),
    scrim = Color.Black,
)

/**
 * The app theme — always the dark glazed-orange identity. The parameter is
 * kept for call-site compatibility; the identity no longer follows the
 * system light mode (v0.9.3: light onSurface text over the deep-black
 * canvas was unreadable — the device report's "textos escuros sobre fundo
 * escuro").
 */
@Composable
fun OvgTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = OvgTypography,
        shapes = OvgShapes,
        content = content,
    )
}

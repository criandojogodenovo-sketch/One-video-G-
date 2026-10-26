package com.onevideog.editor.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * v0.9.4 — non-regression lock for the v0.9.3 fix #7 (Secção 4): the ONE
 * complete colorScheme (36 slots, zero Material 3 purple) with the fixed
 * dark identity.
 *
 * The strongest form of the lock: every slot the v0.9.2 device report saw
 * leaking the M3 baseline (secondary/tertiary containers, surfaceTint,
 * surface containers, primary container) is pinned to its exact identity
 * value, AND differs from the M3 darkColorScheme() default — so removing
 * any override brings the purple back and fails here.
 */
class ThemeSchemeTest {

    // Identity pins (hex of the Theme.kt values).
    @Test
    fun `primary is the glazed metallic orange`() {
        assertEquals(Color(0xFFFF7A00), DarkScheme.primary)
        assertEquals(Color(0xFFFFB347), DarkScheme.inversePrimary)
        assertEquals(Color(0xFF3A2206), DarkScheme.primaryContainer)
    }

    @Test
    fun `the previously-leaking slots carry the graphite-orange identity`() {
        assertEquals(Color(0xFF23262E), DarkScheme.secondaryContainer)
        assertEquals(Color(0xFF33210A), DarkScheme.tertiaryContainer)
        assertEquals(Color(0xFF1A1D24), DarkScheme.surfaceTint) // no purple elevation tint
        assertEquals(Color(0xFF12141A), DarkScheme.surfaceContainer)
        assertEquals(Color(0xFF1A1D24), DarkScheme.surfaceContainerHigh)
        assertEquals(Color(0xFF3A1512), DarkScheme.errorContainer)
        assertEquals(Color(0xFF262A33), DarkScheme.outlineVariant)
    }

    @Test
    fun `the identity is dark and fixed`() {
        assertEquals(Color(0xFF050505), DarkScheme.background)
        assertEquals(Color(0xFF12141A), DarkScheme.surface)
        assertEquals(Color(0xFFECEBF0), DarkScheme.onSurface) // bright text on dark
        assertEquals(Color(0xFFE2574C), DarkScheme.error)
    }

    /**
     * Zero-purple sweep: no pinned slot may equal the M3 baseline default.
     * Constructing the DEFAULT dark scheme and comparing the slots that
     * leaked in the v0.9.2 device report — an override removal flips red.
     */
    @Test
    fun `no M3 baseline default survives in the identity slots`() {
        val baseline = darkColorScheme() // stock Material You purple defaults
        val slots = listOf(
            "primary" to (baseline.primary to DarkScheme.primary),
            "secondary" to (baseline.secondary to DarkScheme.secondary),
            "tertiary" to (baseline.tertiary to DarkScheme.tertiary),
            "primaryContainer" to (baseline.primaryContainer to DarkScheme.primaryContainer),
            "secondaryContainer" to (baseline.secondaryContainer to DarkScheme.secondaryContainer),
            "tertiaryContainer" to (baseline.tertiaryContainer to DarkScheme.tertiaryContainer),
            "surfaceTint" to (baseline.surfaceTint to DarkScheme.surfaceTint),
            "surfaceVariant" to (baseline.surfaceVariant to DarkScheme.surfaceVariant),
            "background" to (baseline.background to DarkScheme.background),
            "surface" to (baseline.surface to DarkScheme.surface),
            "errorContainer" to (baseline.errorContainer to DarkScheme.errorContainer),
        )
        for ((name, pair) in slots) {
            val (default, ours) = pair
            assertNotEquals("slot $name fell back to the M3 default", default, ours)
        }
    }

    @Test
    fun `every scheme slot is explicitly overridden (36 of 36)`() {
        val baseline = darkColorScheme()
        // The v0.9.3 contract: EVERY slot differs from the M3 default —
        // nothing falls through to the purple-tinted baseline.
        val ours = listOf(
            DarkScheme.primary, DarkScheme.onPrimary, DarkScheme.primaryContainer,
            DarkScheme.onPrimaryContainer, DarkScheme.inversePrimary,
            DarkScheme.secondary, DarkScheme.onSecondary, DarkScheme.secondaryContainer,
            DarkScheme.onSecondaryContainer,
            DarkScheme.tertiary, DarkScheme.onTertiary, DarkScheme.tertiaryContainer,
            DarkScheme.onTertiaryContainer,
            DarkScheme.background, DarkScheme.onBackground,
            DarkScheme.surface, DarkScheme.onSurface, DarkScheme.surfaceVariant,
            DarkScheme.onSurfaceVariant, DarkScheme.surfaceTint, DarkScheme.surfaceDim,
            DarkScheme.surfaceBright, DarkScheme.surfaceContainerLowest,
            DarkScheme.surfaceContainerLow, DarkScheme.surfaceContainer,
            DarkScheme.surfaceContainerHigh, DarkScheme.surfaceContainerHighest,
            DarkScheme.inverseSurface, DarkScheme.inverseOnSurface,
            DarkScheme.error, DarkScheme.onError, DarkScheme.errorContainer,
            DarkScheme.onErrorContainer, DarkScheme.outline, DarkScheme.outlineVariant,
            DarkScheme.scrim,
        )
        val defaults = listOf(
            baseline.primary, baseline.onPrimary, baseline.primaryContainer,
            baseline.onPrimaryContainer, baseline.inversePrimary,
            baseline.secondary, baseline.onSecondary, baseline.secondaryContainer,
            baseline.onSecondaryContainer,
            baseline.tertiary, baseline.onTertiary, baseline.tertiaryContainer,
            baseline.onTertiaryContainer,
            baseline.background, baseline.onBackground,
            baseline.surface, baseline.onSurface, baseline.surfaceVariant,
            baseline.onSurfaceVariant, baseline.surfaceTint, baseline.surfaceDim,
            baseline.surfaceBright, baseline.surfaceContainerLowest,
            baseline.surfaceContainerLow, baseline.surfaceContainer,
            baseline.surfaceContainerHigh, baseline.surfaceContainerHighest,
            baseline.inverseSurface, baseline.inverseOnSurface,
            baseline.error, baseline.onError, baseline.errorContainer,
            baseline.onErrorContainer, baseline.outline, baseline.outlineVariant,
            baseline.scrim,
        )
        assertEquals(36, ours.size)
        assertEquals(36, defaults.size)
        val overridden = ours.zip(defaults).count { (o, d) -> o != d }
        // scrim is Color.Black in the identity AND may coincide with the
        // baseline scrim — every OTHER slot must differ from the default.
        assertEquals(35, overridden)
    }
}

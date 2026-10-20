package com.onevideog.editor.editor

import com.onevideog.editor.data.OverlayTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.4.0 (Etapa E1 — "Validar NaN, infinito e unidades"): the single commit
 * boundary for transform values must produce a FINITE, in-range transform no
 * matter what a gesture, panel or future caller hands it. `coerceIn` alone is
 * not enough: every comparison against NaN is false, so `NaN.coerceIn(0f,1f)`
 * returns NaN and silently disables all downstream clamping.
 */
class OverlayTransformSanitizeTest {

    // ---- non-finite inputs fall back to defaults ----------------------------

    @Test
    fun `NaN components fall back to the field defaults`() {
        val bad = OverlayTransform(
            x = Float.NaN, y = Float.NaN, scale = Float.NaN,
            rotationDeg = Float.NaN, opacity = Float.NaN,
        )
        val fixed = bad.withClampedValues()
        assertEquals(0.5f, fixed.x, 1e-6f)
        assertEquals(0.8f, fixed.y, 1e-6f)
        assertEquals(0.6f, fixed.scale, 1e-6f)
        assertEquals(0f, fixed.rotationDeg, 1e-6f)
        assertEquals(1f, fixed.opacity, 1e-6f)
        assertFalse(fixed.hasNonFinite)
    }

    @Test
    fun `infinite components fall back to the field defaults`() {
        val bad = OverlayTransform(
            x = Float.POSITIVE_INFINITY,
            y = Float.NEGATIVE_INFINITY,
            scale = Float.POSITIVE_INFINITY,
            rotationDeg = Float.NEGATIVE_INFINITY,
            opacity = Float.POSITIVE_INFINITY,
        )
        val fixed = bad.withClampedValues()
        assertFalse(fixed.hasNonFinite)
        assertTrue(fixed.x in 0f..1f && fixed.y in 0f..1f)
        assertTrue(fixed.scale in 0.05f..1.6f && fixed.opacity in 0f..1f)
    }

    // ---- finite inputs keep their existing clamp contract -------------------

    @Test
    fun `finite values are clamped exactly as before`() {
        val t = OverlayTransform(x = 2f, y = -1f, scale = 99f, rotationDeg = 0f, opacity = 7f)
        val fixed = t.withClampedValues()
        assertEquals(1f, fixed.x, 1e-6f)
        assertEquals(0f, fixed.y, 1e-6f)
        assertEquals(1.6f, fixed.scale, 1e-6f)
        assertEquals(1f, fixed.opacity, 1e-6f)
    }

    @Test
    fun `finite in-range values pass through unchanged`() {
        val t = OverlayTransform(x = 0.25f, y = 0.75f, scale = 1.2f, rotationDeg = 45f, opacity = 0.5f)
        assertEquals(t, t.withClampedValues())
    }

    @Test
    fun `rotation is NOT wrapped to -180-180 (multi-turn keyframes stay intact)`() {
        // Wrapping would corrupt keyframe interpolation that intentionally
        // spans more than a full turn (e.g. 170 -> 190 must not become
        // 170 -> -170, which interpolates backwards through 0).
        val t = OverlayTransform(rotationDeg = 270f)
        assertEquals(270f, t.withClampedValues().rotationDeg, 1e-6f)
        val neg = OverlayTransform(rotationDeg = -540f)
        assertEquals(-540f, neg.withClampedValues().rotationDeg, 1e-6f)
    }

    // ---- detector ------------------------------------------------------------

    @Test
    fun `hasNonFinite detects each poisoned component`() {
        assertTrue(OverlayTransform(x = Float.NaN).hasNonFinite)
        assertTrue(OverlayTransform(y = Float.NaN).hasNonFinite)
        assertTrue(OverlayTransform(scale = Float.NaN).hasNonFinite)
        assertTrue(OverlayTransform(opacity = Float.NaN).hasNonFinite)
        assertTrue(OverlayTransform(rotationDeg = Float.NaN).hasNonFinite)
        assertFalse(OverlayTransform().hasNonFinite)
    }

    @Test
    fun `sanitize is idempotent`() {
        val bad = OverlayTransform(x = Float.NaN, scale = 42f, opacity = -3f)
        val once = bad.withClampedValues()
        val twice = once.withClampedValues()
        assertEquals(once, twice)
    }
}

package com.onevideog.editor.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.4.0 (Etapa D4/D3) — gizmo gesture math:
 *  - rotation handle and two-finger twist accumulate [GizmoMath.angleDelta]
 *    per pointer event; the shortest-arc normalization means crossing the
 *    atan2 ±180° seam (or the fingers' vector passing it) produces a small
 *    delta, NEVER the ~360° flip the old absolute-delta formula produced
 *    (device-visible quadrant jump).
 *  - [GizmoMath.pinchScale] keeps aspect (single factor), clamps to the same
 *    model range as the scale handle, and survives degenerate distances.
 */
class GizmoMathTest {

    // ---- angleDelta: no quadrant jumps ------------------------------------

    @Test
    fun `crossing the 180 degree seam yields a small delta not a full flip`() {
        // Pointer/finger vector sweeping clockwise over the seam: the raw
        // atan2 value jumps from +170 to -170, but the shortest arc is +20.
        assertEquals(20f, GizmoMath.angleDelta(170f, -170f), 1e-4f)
        // ...and back: -20, symmetric.
        assertEquals(-20f, GizmoMath.angleDelta(-170f, 170f), 1e-4f)
    }

    @Test
    fun `seam crossing in the counter-clockwise direction stays short`() {
        assertEquals(-20f, GizmoMath.angleDelta(-170f, 170f), 1e-4f)
        assertEquals(20f, GizmoMath.angleDelta(170f, -170f), 1e-4f)
    }

    @Test
    fun `full circle wrap is one degree not 359`() {
        assertEquals(-1f, GizmoMath.angleDelta(0f, 359f), 1e-4f)
        assertEquals(1f, GizmoMath.angleDelta(359f, 0f), 1e-4f)
    }

    @Test
    fun `zero delta when the angle does not change`() {
        assertEquals(0f, GizmoMath.angleDelta(45f, 45f), 1e-4f)
    }

    @Test
    fun `accumulated small deltas match the true swept angle across the seam`() {
        // Simulate per-event accumulation sweeping from 170 through the seam
        // to 190 (= -170 as atan2 reports it): each raw value is wrapped into
        // atan2's (-180, 180] output range exactly like the pointer handler
        // receives it. The accumulated sum must equal the true +20° sweep and
        // must never jump by ~360.
        var acc = 0f
        var prev = wrap(170f)
        var raw = 171f
        while (acc < 19.5f) {
            val now = wrap(raw)
            acc += GizmoMath.angleDelta(prev, now)
            prev = now
            raw += 1f
        }
        assertTrue("accumulated=$acc", acc in 19.99f..20.01f)
        assertTrue(kotlin.math.abs(acc) < 45f)
    }

    /** atan2's output range: (-180, 180]. */
    private fun wrap(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    @Test
    fun `delta normalizes both endpoints into the shortest-arc space`() {
        // Values beyond ±180 (accumulated multi-turn rotation) still take the
        // shortest arc: 540 ≡ 180, and 180 -> -170 sweeps +10 (180 → 190 ≡
        // -170), not -350.
        assertEquals(10f, GizmoMath.angleDelta(540f, -170f), 0.5f)
    }

    // ---- pinchScale: uniform, clamped, degenerate-safe ---------------------

    @Test
    fun `spreading fingers grows the scale proportionally`() {
        val s = GizmoMath.pinchScale(baseScale = 0.5f, startDist = 100f, nowDist = 200f)
        assertEquals(1.0f, s, 1e-4f)
    }

    @Test
    fun `closing fingers shrinks the scale proportionally`() {
        val s = GizmoMath.pinchScale(baseScale = 0.8f, startDist = 200f, nowDist = 100f)
        assertEquals(0.4f, s, 1e-4f)
    }

    @Test
    fun `result is clamped to the model range`() {
        assertEquals(1.6f, GizmoMath.pinchScale(1.5f, 100f, 400f), 1e-4f)
        assertEquals(0.05f, GizmoMath.pinchScale(0.06f, 400f, 50f), 1e-4f)
    }

    @Test
    fun `degenerate start distance never explodes the factor`() {
        // Fingers on the same pixel at gesture start: guarded to 1 px.
        val s = GizmoMath.pinchScale(0.5f, startDist = 0f, nowDist = 50f)
        assertTrue(s in 0.05f..1.6f)
    }

    @Test
    fun `non-finite current distance keeps the base scale`() {
        val s = GizmoMath.pinchScale(0.7f, 100f, Float.NaN)
        assertEquals(0.7f, s, 1e-4f)
    }
}

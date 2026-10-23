package com.onevideog.editor.editor

import com.onevideog.editor.data.Curve
import com.onevideog.editor.data.CurvePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.7.0 (Fase J) — the CurveMath contract, locked on the JVM:
 *  - Fritsch–Carlson monotonicity: a monotone control-point curve produces a
 *    NON-DECREASING LUT (no overshoot/oscillation between points — the
 *    property a natural cubic spline cannot guarantee);
 *  - a linear curve's LUT IS the identity (lut[i] == i/255);
 *  - the 2-point (0,0)-(1,1) curve is identity (isIdentity + LUT);
 *  - round-trip: sampling the LUT at the control points' x reproduces the
 *    points' y (the spline INTERPOLATES its data);
 *  - validation/sanitize/identity rules the ViewModel relies on.
 */
class CurveMathTest {

    private fun curve(vararg pts: Pair<Float, Float>) =
        Curve(pts.map { CurvePoint(it.first, it.second) })

    // ---------------------------------------------------------- monotonicity

    @Test
    fun `monotone curve produces a non-decreasing lut`() {
        val c = curve(0f to 0f, 0.3f to 0.1f, 0.6f to 0.7f, 1f to 1f)
        val lut = CurveMath.buildLut(c)
        assertEquals(CurveMath.LUT_SIZE, lut.size)
        for (i in 1 until lut.size) {
            assertTrue(
                "LUT decreased at i=$i: ${lut[i - 1]} -> ${lut[i]}",
                lut[i] >= lut[i - 1] - 1e-6f,
            )
        }
    }

    @Test
    fun `monotone curve with a flat segment stays within the segment bounds`() {
        // Fritsch–Carlson must never overshoot beyond the flat segment's y.
        val c = curve(0f to 0f, 0.5f to 0.5f, 0.7f to 0.5f, 1f to 1f)
        val lut = CurveMath.buildLut(c)
        for (i in 0 until CurveMath.LUT_SIZE) {
            val x = i / (CurveMath.LUT_SIZE - 1f)
            val y = lut[i]
            assertTrue("overshoot above at x=$x: $y", y <= 1f + 1e-6f)
            // Between the two 0.5 points the curve must stay <= 0.5 + eps.
            if (x in 0.5f..0.7f) {
                assertTrue("flat segment overshoot at x=$x: $y", y <= 0.5f + 0.01f)
            }
        }
    }

    @Test
    fun `s-curve preset is monotone`() {
        val lut = CurveMath.buildLut(Curve(CurveMath.Presets.S_CURVE))
        for (i in 1 until lut.size) {
            assertTrue(lut[i] >= lut[i - 1] - 1e-6f)
        }
    }

    @Test
    fun `even a strongly kinked monotone curve never reverses`() {
        val c = curve(0f to 0f, 0.1f to 0.9f, 0.2f to 0.95f, 0.9f to 0.96f, 1f to 1f)
        val lut = CurveMath.buildLut(c)
        for (i in 1 until lut.size) {
            assertTrue(lut[i] >= lut[i - 1] - 1e-6f)
        }
    }

    // -------------------------------------------------------------- identity

    @Test
    fun `linear lut is the identity`() {
        val lut = CurveMath.buildLut(curve(0f to 0f, 1f to 1f))
        for (i in 0 until CurveMath.LUT_SIZE) {
            val expected = i / (CurveMath.LUT_SIZE - 1f)
            assertEquals("at i=$i", expected, lut[i], 1e-3f)
        }
    }

    @Test
    fun `two point zero-one curve is identity`() {
        val c = curve(0f to 0f, 1f to 1f)
        assertTrue(CurveMath.isIdentity(c))
        assertTrue(CurveMath.isValid(c))
    }

    @Test
    fun `isIdentity tolerates 1e-3 but not more`() {
        assertTrue(CurveMath.isIdentity(curve(0f to 0.0009f, 1f to 1f)))
        assertFalse(CurveMath.isIdentity(curve(0f to 0f, 1f to 1.01f)))
        assertFalse(CurveMath.isIdentity(curve(0f to 0f, 0.5f to 0.55f, 1f to 1f)))
    }

    // ------------------------------------------------------------ round-trip

    @Test
    fun `lut reproduces the control points`() {
        val pts = listOf(
            CurvePoint(0f, 0.05f), CurvePoint(0.25f, 0.2f),
            CurvePoint(0.75f, 0.8f), CurvePoint(1f, 0.95f),
        )
        val lut = CurveMath.buildLut(Curve(pts))
        for (p in pts) {
            val idx = (p.x * (CurveMath.LUT_SIZE - 1)).toFloat().let { kotlin.math.round(it).toInt() }
            assertEquals("at x=${p.x}", p.y, lut[idx], 0.02f)
        }
    }

    @Test
    fun `round trip points to lut to sampled points stays close`() {
        val pts = listOf(
            CurvePoint(0f, 0f), CurvePoint(0.3f, 0.15f),
            CurvePoint(0.6f, 0.85f), CurvePoint(1f, 1f),
        )
        val lut = CurveMath.buildLut(Curve(pts))
        // Resample the LUT back into a coarse point list and compare against
        // the direct spline evaluation AT THE LUT'S OWN x (idx/255) — the
        // same source of truth.
        val xs = pts.map { it.x }.toFloatArray()
        val ys = pts.map { it.y }.toFloatArray()
        val tangents = CurveMath.fritschCarlsonTangents(xs, ys)
        for (x in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val idx = kotlin.math.round(x * (CurveMath.LUT_SIZE - 1)).toInt()
            val expected = CurveMath.evaluateSpline(
                xs, ys, tangents,
                idx / (CurveMath.LUT_SIZE - 1f),
            )
            assertEquals("at x=$x", expected, lut[idx], 1e-3f)
        }
    }

    // ------------------------------------------------------------ validation

    @Test
    fun `validation rejects too few points out of range and unsorted`() {
        assertEquals(CurveMath.InvalidReason.TOO_FEW_POINTS, CurveMath.validate(curve(0f to 0f)))
        assertEquals(
            CurveMath.InvalidReason.POINT_OUT_OF_RANGE,
            CurveMath.validate(curve(0f to 0f, 1f to 1.2f)),
        )
        assertEquals(
            CurveMath.InvalidReason.POINT_OUT_OF_RANGE,
            CurveMath.validate(curve(-0.1f to 0f, 1f to 1f)),
        )
        assertEquals(
            CurveMath.InvalidReason.NOT_SORTED,
            CurveMath.validate(curve(0.5f to 0.2f, 0.4f to 0.5f, 1f to 1f)),
        )
        assertNull(CurveMath.validate(curve(0f to 0f, 0.5f to 0.5f, 1f to 1f)))
    }

    @Test
    fun `sanitize clamps sorts and drops x-duplicates`() {
        val sanitized = CurveMath.sanitize(
            listOf(
                CurvePoint(1f, 1.5f),
                CurvePoint(0.5f, 0.3f),
                CurvePoint(0.5f + 1e-5f, 0.9f),
                CurvePoint(-0.2f, -1f),
                CurvePoint(0.2f, 0.1f),
            ),
        )
        assertNotNull(sanitized)
        val pts = sanitized!!.points
        // Sorted by x, coordinates clamped, the near-duplicate 0.5 dropped.
        assertEquals(listOf(0f, 0.2f, 0.5f, 1f), pts.map { it.x })
        assertEquals(listOf(0f, 0.1f, 0.3f, 1f), pts.map { it.y })
    }

    @Test
    fun `sanitize returns null when fewer than two points survive`() {
        assertNull(CurveMath.sanitize(listOf(CurvePoint(0.5f, 0.5f))))
        assertNull(CurveMath.sanitize(emptyList()))
        // Two points merged into one by the x-separation rule.
        assertNull(
            CurveMath.sanitize(
                listOf(CurvePoint(0.5f, 0.4f), CurvePoint(0.5f + 1e-6f, 0.6f)),
            ),
        )
    }

    // ----------------------------------------------------------- apply (CPU)

    @Test
    fun `applyLut master maps all three channels and preserves alpha`() {
        val pixels = intArrayOf(0xFF102030.toInt(), 0x80405060.toInt())
        val identity = CurveMath.buildLut(curve(0f to 0f, 1f to 1f))
        val out = CurveMath.applyLut(pixels, identity, CurveMath.Channel.MASTER)
        assertEquals(pixels.toList(), out.toList())

        // Lift everything: a constant-ish brightening curve.
        val lift = CurveMath.buildLut(curve(0f to 0.2f, 1f to 1f))
        val lifted = CurveMath.applyLut(pixels, lift, CurveMath.Channel.MASTER)
        val c0 = lifted[0]
        assertEquals(0xFF, (c0 ushr 24) and 0xFF) // alpha preserved (pixel 0 is opaque)
        assertTrue(((c0 ushr 16) and 0xFF) > 0x10) // red brightened
        assertTrue(((c0 ushr 8) and 0xFF) > 0x20)  // green brightened
        assertTrue((c0 and 0xFF) > 0x30)           // blue brightened
        // The second pixel keeps its translucent alpha.
        assertEquals(0x80, (lifted[1] ushr 24) and 0xFF)
    }

    @Test
    fun `applyLut red only touches red`() {
        val pixels = intArrayOf(0xFF102030.toInt())
        val lift = CurveMath.buildLut(curve(0f to 0.2f, 1f to 1f))
        val out = CurveMath.applyLut(pixels, lift, CurveMath.Channel.RED)
        val c = out[0]
        assertTrue(((c ushr 16) and 0xFF) > 0x10)
        assertEquals(0x20, (c ushr 8) and 0xFF)
        assertEquals(0x30, c and 0xFF)
    }

    @Test
    fun `applyCurves runs master first then per channel`() {
        // Master lifts 0 -> 0.2; the red curve then maps 0.2 -> 0.1 (down);
        // green and blue carry the IDENTITY so only the master touches them.
        // If the order were wrong (R first, master after) the result for the
        // red channel would differ — the test pins the documented sequence.
        val master = CurveMath.buildLut(curve(0f to 0.2f, 1f to 1f))
        val identity = CurveMath.buildLut(curve(0f to 0f, 1f to 1f))
        val red = CurveMath.buildLut(
            curve(0f to 0f, 0.2f to 0.1f, 1f to 1f),
        )
        val pixels = intArrayOf(0xFF000000.toInt())
        val out = CurveMath.applyCurves(pixels, master, red, identity, identity)
        val r = (out[0] ushr 16) and 0xFF
        // 0 --master--> 0.2*255=51 --red(51)--> ~0.1*255=25 (within LUT rounding)
        assertTrue("red after master+red = $r", r in 20..31)
        val g = (out[0] ushr 8) and 0xFF
        // Green got only the master: ~51.
        assertTrue("green after master = $g", g in 48..55)
    }
}

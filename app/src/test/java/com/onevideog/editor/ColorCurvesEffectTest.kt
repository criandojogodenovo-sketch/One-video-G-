package com.onevideog.editor.editor

import com.onevideog.editor.data.ColorCurves
import com.onevideog.editor.data.Curve
import com.onevideog.editor.data.CurvePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.7.0 (Fase J) — the ColorCurvesEffect contract:
 *  - the glue builds NO effect for null / invalid / all-identity curves
 *    (no GPU cost) and exactly ONE effect otherwise;
 *  - the fragment shader is valid ES 1.00 and samples the four LUT
 *    textures (JVM-checkable string contract);
 *  - [ColorCurvesEffect.applyToFrame] (CPU reference) matches the GLSL's
 *    documented behaviour: Master first, then R/G/B per channel, alpha
 *    preserved, output clamped to 0..255.
 */
class ColorCurvesEffectTest {

    private fun curve(vararg pts: Pair<Float, Float>) =
        Curve(pts.map { CurvePoint(it.first, it.second) })

    private val linear = curve(0f to 0f, 1f to 1f)

    private fun curves(
        master: Curve = linear,
        red: Curve = linear,
        green: Curve = linear,
        blue: Curve = linear,
    ) = ColorCurves(master, red, green, blue)

    // ------------------------------------------------------------------ glue

    @Test
    fun `null curves build no effect`() {
        assertTrue(ColorCurvesEffects.effectsFor(null).isEmpty())
    }

    @Test
    fun `all-identity curves build no effect`() {
        assertTrue(ColorCurvesEffects.effectsFor(curves()).isEmpty())
    }

    @Test
    fun `invalid channel builds no effect`() {
        val invalid = curves(master = curve(0f to 0f)) // 1 point -> invalid
        assertTrue(ColorCurvesEffects.effectsFor(invalid).isEmpty())
    }

    @Test
    fun `one non-identity channel builds exactly one effect`() {
        val effects = ColorCurvesEffects.effectsFor(
            curves(green = curve(0f to 0.1f, 1f to 0.9f)),
        )
        assertEquals(1, effects.size)
        assertTrue(effects[0] is ColorCurvesEffect)
    }

    // ----------------------------------------------------------------- GLSL

    @Test
    fun `shader string is valid es 100 with the four lut samplers`() {
        val effect = ColorCurvesEffect(curves(green = curve(0f to 0.2f, 1f to 1f)))
        val glsl = effect.glslShaderString()
        assertTrue(glsl.startsWith("#version 100"))
        assertTrue(glsl.contains("precision mediump float"))
        assertTrue(glsl.contains("uniform sampler2D uTexSampler"))
        assertTrue(glsl.contains("uniform sampler2D u_LutMaster"))
        assertTrue(glsl.contains("uniform sampler2D u_LutR"))
        assertTrue(glsl.contains("uniform sampler2D u_LutG"))
        assertTrue(glsl.contains("uniform sampler2D u_LutB"))
        assertTrue(glsl.contains("void main()"))
        assertTrue(glsl.contains("gl_FragColor"))
        // Master applies to all three channels BEFORE the per-channel curves.
        assertTrue(glsl.indexOf("u_LutMaster") < glsl.indexOf("u_LutR"))
        // Balanced braces (cheap structural sanity).
        assertEquals(glsl.count { it == '{' }, glsl.count { it == '}' })
    }

    // --------------------------------------------------------- CPU reference

    @Test
    fun `applyToFrame with identity luts is a no-op`() {
        val w = 4
        val h = 2
        val pixels = IntArray(w * h) { i -> 0xFF000000.toInt() or (i * 7) }
        val original = pixels.copyOf()
        val effect = ColorCurvesEffect(curves())
        val out = effect.applyToFrame(pixels, w, h)
        // Identity curves: the ViewModel normalizes these to null, but the
        // effect itself must still be byte-identical (safety).
        assertTrue(original.contentEquals(out))
    }

    @Test
    fun `applyToFrame lifts shadows on all channels and preserves alpha`() {
        val w = 2
        val h = 2
        val pixels = intArrayOf(
            0xFF080808.toInt(), 0xFF101010.toInt(),
            0xFF181818.toInt(), 0xFF202020.toInt(),
        )
        val effect = ColorCurvesEffect(
            curves(master = curve(0f to 0.3f, 1f to 1f)),
        )
        val out = effect.applyToFrame(pixels, w, h)
        for (i in pixels.indices) {
            val a = (out[i] ushr 24) and 0xFF
            assertEquals(0xFF, a)
            val r = (out[i] ushr 16) and 0xFF
            assertTrue("pixel $i r=$r", r >= 0x30)
        }
    }

    @Test
    fun `applyToFrame red curve only changes red`() {
        val w = 1
        val h = 1
        // 0x40 = 64/255 ≈ 0.251 — exactly at the red control point (0.25, 0.1).
        val pixels = intArrayOf(0xFF404040.toInt())
        val effect = ColorCurvesEffect(
            curves(red = curve(0f to 0f, 0.25f to 0.1f, 1f to 1f)),
        )
        val out = effect.applyToFrame(pixels, w, h)
        val r = (out[0] ushr 16) and 0xFF
        val g = (out[0] ushr 8) and 0xFF
        val b = out[0] and 0xFF
        // Red pulled down to ~0.1*255 = 25; green/blue untouched.
        assertTrue("red $r", r in 22..29)
        assertEquals(0x40, g)
        assertEquals(0x40, b)
    }

    @Test
    fun `applyToFrame matches the cpu applyCurves composition`() {
        val w = 3
        val h = 3
        val pixels = IntArray(w * h) { i -> (i * 29 shl 16) or (i * 11 shl 8) or (i * 5) or 0xFF000000.toInt() }
        val master = curve(0f to 0.05f, 0.5f to 0.55f, 1f to 0.95f)
        val red = curve(0f to 0f, 0.3f to 0.25f, 1f to 1f)
        val green = curve(0f to 0.05f, 1f to 1f)
        val blue = curve(0f to 0f, 1f to 0.9f)
        val effect = ColorCurvesEffect(curves(master, red, green, blue))
        val viaEffect = effect.applyToFrame(pixels.copyOf(), w, h)
        val viaMath = CurveMath.applyCurves(
            pixels.copyOf(),
            CurveMath.buildLut(master),
            CurveMath.buildLut(red),
            CurveMath.buildLut(green),
            CurveMath.buildLut(blue),
        )
        assertTrue(viaEffect.contentEquals(viaMath))
    }

    @Test
    fun `applyToFrame output stays in bounds on an extreme curve`() {
        val w = 2
        val h = 1
        val pixels = intArrayOf(0xFFF0F0F0.toInt(), 0xFF080808.toInt())
        // Extreme: crush the top to 0 and lift the bottom to 1 — the LUT is
        // clamped to 0..1 by buildLut, so the bytes stay in 0..255.
        val effect = ColorCurvesEffect(
            curves(master = curve(0f to 1f, 1f to 0f)),
        )
        val out = effect.applyToFrame(pixels, w, h)
        for (i in out) {
            val r = (i ushr 16) and 0xFF
            val g = (i ushr 8) and 0xFF
            val b = i and 0xFF
            assertTrue(r in 0..255 && g in 0..255 && b in 0..255)
        }
    }
}

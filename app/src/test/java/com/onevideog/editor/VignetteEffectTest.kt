package com.onevideog.editor.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.6.0 (I3) — the vignette contract: a valid Media3-convention GLSL
 * fragment shader implementing `1 - intensity * d2` (d2 normalized to 1 at
 * the corners), a CPU reference that leaves the center untouched and
 * darkens the edges, and the intensity-0 identity.
 */
class VignetteEffectTest {

    private val w = 32
    private val h = 32

    private fun greyFrame(value: Int): IntArray =
        IntArray(w * h) { 0xFF000000.toInt() or (value shl 16) or (value shl 8) or value }

    private fun pixel(pixels: IntArray, x: Int, y: Int): Int = pixels[y * w + x]

    @Test
    fun `glsl shader string is a valid media3 fragment shader`() {
        val code = VignetteEffect(0.5f).glslShaderString()
        assertTrue(code.startsWith("#version 100"))
        assertTrue(code.contains("uniform sampler2D uTexSampler"))
        assertTrue(code.contains("varying vec2 vTexSamplingCoord"))
        assertTrue(code.contains("u_Intensity"))
        assertTrue(code.contains("void main()"))
        assertTrue(code.contains("gl_FragColor"))
    }

    @Test
    fun `intensity zero is the identity`() {
        val frame = greyFrame(180)
        val out = VignetteEffect(0f).applyToFrame(frame.copyOf(), w, h)
        assertEquals(frame.toList(), out.toList())
    }

    @Test
    fun `applyToFrame darkens edges but not the center`() {
        val effect = VignetteEffect(0.8f)
        val frame = greyFrame(200)
        val out = effect.applyToFrame(frame.copyOf(), w, h)
        assertNotEquals(frame.toList(), out.toList())
        // Center pixel ~ untouched (d2 ~ 0).
        val center = pixel(out, w / 2, h / 2) and 0xFF
        assertEquals(200.0, center.toDouble(), 3.0)
        // Corner pixel strongly darkened. The pixel CENTER sits inset half
        // a texel from the frame corner, so its d2 is (1 - 1/w)²-scaled —
        // assert against the exact documented curve instead of a loose
        // constant.
        val corner = pixel(out, 0, 0) and 0xFF
        val expectedCorner = 200f * ClipEffectMath.vignetteFactorAt(
            ClipEffectMath.vignetteD2(0, 0, w, h),
            0.8f,
        )
        assertEquals(expectedCorner.toDouble(), corner.toDouble(), 1.5)
        assertTrue(corner < 65)
        // The vignette is monotone: quarter point darker than center, corner darker still.
        val quarter = pixel(out, w / 4, h / 4) and 0xFF
        assertTrue(quarter < center)
        assertTrue(corner < quarter)
    }

    @Test
    fun `higher intensity darkens the same corner more`() {
        val frame = greyFrame(220)
        val soft = VignetteEffect(0.3f).applyToFrame(frame.copyOf(), w, h)
        val strong = VignetteEffect(0.9f).applyToFrame(frame.copyOf(), w, h)
        val softCorner = pixel(soft, 0, 0) and 0xFF
        val strongCorner = pixel(strong, 0, 0) and 0xFF
        assertTrue(strongCorner < softCorner)
    }

    @Test
    fun `factor formula matches the documented curve`() {
        assertEquals(1f, ClipEffectMath.vignetteFactorAt(0f, 1f), 1e-6f)
        assertEquals(0f, ClipEffectMath.vignetteFactorAt(1f, 1f), 1e-6f)
        assertEquals(0.5f, ClipEffectMath.vignetteFactorAt(1f, 0.5f), 1e-6f)
        // Beyond the corner the clamp holds the curve at 0.
        assertEquals(0f, ClipEffectMath.vignetteFactorAt(1.5f, 1f), 1e-6f)
    }

    @Test
    fun `alpha is preserved and output size kept`() {
        val frame = IntArray(w * h) { 0x80FF8080.toInt() } // semi-transparent
        val out = VignetteEffect(0.7f).applyToFrame(frame.copyOf(), w, h)
        assertEquals(frame.size, out.size)
        for (i in out.indices) {
            assertEquals(0x80, (out[i] ushr 24) and 0xFF)
        }
    }
}

package com.onevideog.editor.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.6.0 (I4) — the sharpen contract: a valid Media3-convention GLSL
 * fragment shader implementing the 3x3 unsharp mask, a CPU reference that
 * is the identity at intensity 0 and a real edge enhancer otherwise.
 */
class SharpenEffectTest {

    private val w = 16
    private val h = 16

    /** Flat grey frame with one hard vertical edge at x = w/2. */
    private fun edgeFrame(): IntArray {
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = if (x < w / 2) 80 else 180
                pixels[y * w + x] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
            }
        }
        return pixels
    }

    private fun red(pixels: IntArray, x: Int, y: Int): Int =
        (pixels[y * w + x] ushr 16) and 0xFF

    @Test
    fun `glsl shader string is a valid media3 fragment shader`() {
        val code = SharpenEffect(0.5f).glslShaderString()
        assertTrue(code.startsWith("#version 100"))
        assertTrue(code.contains("uniform sampler2D uTexSampler"))
        assertTrue(code.contains("varying vec2 vTexSamplingCoord"))
        assertTrue(code.contains("u_Amount"))
        assertTrue(code.contains("u_TexelSize"))
        assertTrue(code.contains("void main()"))
        assertTrue(code.contains("gl_FragColor"))
    }

    @Test
    fun `intensity zero is the identity`() {
        val frame = edgeFrame()
        val out = ClipEffectMath.applySharpen(frame.copyOf(), w, h, 0f)
        assertEquals(frame.toList(), out.toList())
    }

    @Test
    fun `applyToFrame changes the frame and overshoots the edge`() {
        val effect = SharpenEffect(0.5f)
        val frame = edgeFrame()
        val out = effect.applyToFrame(frame.copyOf(), w, h)
        assertNotEquals(frame.toList(), out.toList())
        // Unsharp mask overshoots: the dark side dips below 80, the bright
        // side peaks above 180.
        assertTrue(red(out, w / 2 - 1, h / 2) < 80)
        assertTrue(red(out, w / 2, h / 2) > 180)
        // The far flat area stays flat (neighbours equal the center).
        assertEquals(80, red(out, 1, 1))
        assertEquals(180, red(out, w - 2, h - 2))
    }

    @Test
    fun `amount maps linearly with the documented maximum`() {
        assertEquals(0f, ClipEffectMath.sharpenAmount(0f), 1e-6f)
        assertEquals(ClipEffectMath.MAX_AMOUNT, ClipEffectMath.sharpenAmount(1f), 1e-6f)
    }

    @Test
    fun `stronger intensity overshoots more`() {
        val frame = edgeFrame()
        val soft = ClipEffectMath.applySharpen(frame.copyOf(), w, h, 0.3f)
        val strong = ClipEffectMath.applySharpen(frame.copyOf(), w, h, 0.9f)
        assertTrue(red(strong, w / 2, h / 2) > red(soft, w / 2, h / 2))
        assertTrue(red(strong, w / 2 - 1, h / 2) < red(soft, w / 2 - 1, h / 2))
    }

    @Test
    fun `alpha preserved and output size kept`() {
        val frame = IntArray(w * h) { 0xC0FFFFFF.toInt() }
        val out = SharpenEffect(1f).applyToFrame(frame.copyOf(), w, h)
        assertEquals(frame.size, out.size)
        for (i in out.indices) {
            assertEquals(0xC0, (out[i] ushr 24) and 0xFF)
        }
    }
}

package com.onevideog.editor.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorMatrixMathTest {

    @Test
    fun `default adjustments produce identity`() {
        val m = ColorMatrixMath.rgbMatrix44(0f, 1f, 1f)
        val identity = ColorMatrixMath.identity44()
        for (i in identity.indices) assertEquals(identity[i], m[i], 1e-5f)
    }

    @Test
    fun `brightness matrix offsets every channel equally`() {
        val m = ColorMatrixMath.brightnessMatrix44(0.2f)
        // Column-major: translation lives in the 4th column (indices 3,7,11).
        assertEquals(0.2f, m[3], 1e-5f)
        assertEquals(0.2f, m[7], 1e-5f)
        assertEquals(0.2f, m[11], 1e-5f)
    }

    @Test
    fun `android 4x5 matrix matches the composed pipeline on grey`() {
        // For a constant grey g: out = k*g + (k*b + 0.5 - 0.5k)  (saturation
        // preserves constants because luma weights sum to 1).
        val b = 0.1f
        val k = 1.4f
        val s = 0.8f
        val m = ColorMatrixMath.androidColorMatrix45(b, k, s)
        val g = 0.5f
        val out = m[0] * g + m[1] * g + m[2] * g + m[4]
        val expected = k * g + (k * b + 0.5f - 0.5f * k)
        assertEquals(expected, out, 1e-4f)
    }

    @Test
    fun `android 4x5 matrix is identity when defaults`() {
        val m = ColorMatrixMath.androidColorMatrix45(0f, 1f, 1f)
        assertEquals(1f, m[0], 1e-5f)
        assertEquals(1f, m[6], 1e-5f)
        assertEquals(1f, m[12], 1e-5f)
        assertEquals(0f, m[4], 1e-5f)
        assertEquals(0f, m[9], 1e-5f)
        assertEquals(0f, m[14], 1e-5f)
    }

    @Test
    fun `zero saturation maps every pixel to equal luma weights`() {
        val m = ColorMatrixMath.androidColorMatrix45(0f, 1f, 0f)
        val r = 0.9f
        val g = 0.2f
        val b = 0.1f
        val outR = m[0] * r + m[1] * g + m[2] * b + m[4]
        val outG = m[5] * r + m[6] * g + m[7] * b + m[9]
        val outB = m[10] * r + m[11] * g + m[12] * b + m[14]
        assertEquals(outR, outG, 1e-4f)
        assertEquals(outG, outB, 1e-4f)
        // Rec.709 luma of the input.
        assertEquals(0.2126f * r + 0.7152f * g + 0.0722f * b, outR, 1e-3f)
    }

    @Test
    fun `slider mappings stay in documented ranges`() {
        assertTrue(ColorMatrixMath.brightnessFromSlider(-100) < 0f)
        assertTrue(ColorMatrixMath.brightnessFromSlider(100) > 0f)
        assertEquals(1f, ColorMatrixMath.contrastFromSlider(0), 1e-5f)
        assertEquals(1f, ColorMatrixMath.saturationFromSlider(0), 1e-5f)
        assertEquals(0f, ColorMatrixMath.saturationFromSlider(-100), 1e-5f)
    }
}

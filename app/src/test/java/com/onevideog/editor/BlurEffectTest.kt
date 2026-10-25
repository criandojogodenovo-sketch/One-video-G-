package com.onevideog.editor.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.6.0 (I2) — the Gaussian blur contract: a valid Media3-convention GLSL
 * fragment shader, a 7-tap separable kernel whose weights sum to 1, and a
 * CPU reference ([GaussianBlurEffect.applyToFrame]) that is the identity at
 * sigma 0 and a real low-pass filter otherwise.
 */
class BlurEffectTest {

    private val w = 16
    private val h = 16

    /** Solid frame with one bright pixel in the middle — blur spreads it. */
    private fun spikeFrame(): IntArray {
        val pixels = IntArray(w * h) { 0xFF000000.toInt() }
        pixels[(h / 2) * w + w / 2] = 0xFFFFFFFF.toInt()
        return pixels
    }

    private fun centerBrightness(pixels: IntArray): Int = (pixels[(h / 2) * w + w / 2] ushr 16) and 0xFF

    private fun totalLuma(pixels: IntArray): Long =
        pixels.sumOf { (((it ushr 16) and 0xFF) + ((it ushr 8) and 0xFF) + (it and 0xFF)).toLong() }

    @Test
    fun `glsl shader string is a valid media3 fragment shader`() {
        val code = GaussianBlurEffect(1f).glslShaderString()
        assertTrue(code.startsWith("#version 100"))
        assertTrue(code.contains("precision mediump float"))
        assertTrue(code.contains("uniform sampler2D uTexSampler"))
        assertTrue(code.contains("varying vec2 vTexSamplingCoord"))
        assertTrue(code.contains("void main()"))
        assertTrue(code.contains("gl_FragColor"))
        // The separable uniforms the glue fills.
        assertTrue(code.contains("u_TexelStep"))
        assertTrue(code.contains("u_Weights[7]"))
    }

    @Test
    fun `blur is ONE GlEffect carrying both separable passes`() {
        // v0.9.1 (BUG A): the two separable passes live inside ONE shader
        // program (Media3 SeparableConvolutionShaderProgram pattern) — the
        // GL chain must never contain a chained blur pair again: after the
        // v0.9.0 camera the shape [DS(camera) -> BlurH -> BlurV] died on
        // realme RMX3624 / Mali-G57 with a deterministic NPE, while
        // [DS(camera) -> ONE custom program] (curves) provably worked.
        val effects = ClipGlEffects.effectsFor(
            listOf(com.onevideog.editor.data.ClipEffect(com.onevideog.editor.data.EffectKind.BLUR, 0.5f)),
        )
        assertEquals(1, effects.size)
        assertTrue(effects[0] is GaussianBlurEffect)
    }

    @Test
    fun `weights sum to one and are symmetric`() {
        for (sigma in listOf(0.5f, 1.5f, 3f)) {
            val weights = ClipEffectMath.blurWeights(sigma)
            assertEquals(7, weights.size)
            assertEquals(1f, weights.sum(), 1e-4f)
            for (i in 0..2) {
                assertEquals(weights[i], weights[6 - i], 1e-6f)
            }
            // Monotonically decreasing away from the center tap.
            for (i in 0..2) {
                assertTrue(weights[i] <= weights[i + 1])
            }
        }
    }

    @Test
    fun `sigma zero is the identity kernel`() {
        val weights = ClipEffectMath.blurWeights(0f)
        assertEquals(0f, weights[3] - 1f, 1e-6f)
        val frame = spikeFrame()
        val out = ClipEffectMath.applyBlurPass(frame, w, h, sigma = 0f, horizontal = true)
        assertEquals(frame.toList(), out.toList())
    }

    @Test
    fun `applyToFrame changes the frame and spreads the spike`() {
        val effect = GaussianBlurEffect(sigma = 2f)
        val frame = spikeFrame()
        val out = effect.applyToFrame(frame.copyOf(), w, h)
        // Output differs from the input...
        assertNotEquals(frame.toList(), out.toList())
        // ...the center lost brightness...
        assertTrue(centerBrightness(out) < 255)
        // ...the neighbours gained some (the spike spread)...
        val neighbour = (h / 2) * w + w / 2 + 1
        assertTrue(((out[neighbour] ushr 16) and 0xFF) > 0)
        // ...and the two-pass blur preserves the total luma (normalized kernel).
        val blurred = ClipEffectMath.applyBlur(frame, w, h, 2f)
        assertEquals(totalLuma(frame).toDouble(), totalLuma(blurred).toDouble(), 40.0)
    }

    @Test
    fun `higher sigma blurs more`() {
        val frame = spikeFrame()
        val soft = ClipEffectMath.applyBlur(frame, w, h, 1f)
        val strong = ClipEffectMath.applyBlur(frame, w, h, 3f)
        // The stronger blur keeps LESS of the spike in the center pixel.
        assertTrue(centerBrightness(strong) < centerBrightness(soft))
    }

    @Test
    fun `intensity maps linearly to sigma with the documented maximum`() {
        assertEquals(0f, ClipEffectMath.blurSigma(0f), 1e-6f)
        assertEquals(ClipEffectMath.MAX_SIGMA, ClipEffectMath.blurSigma(1f), 1e-6f)
        assertEquals(ClipEffectMath.MAX_SIGMA / 2, ClipEffectMath.blurSigma(0.5f), 1e-6f)
    }

    @Test
    fun `output size is preserved`() {
        val frame = spikeFrame()
        val out = ClipEffectMath.applyBlur(frame, w, h, 1.5f)
        assertEquals(frame.size, out.size)
    }
}

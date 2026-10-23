package com.onevideog.editor.editor

import com.onevideog.editor.data.ClipEffect
import com.onevideog.editor.data.EffectKind
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * v0.6.0 (Fase I) — pure 2D-effect math: the GLSL sources rendered by BOTH
 * pipelines (preview and export share the exact same shader strings through
 * [ClipEffectsFactory.effectsFor]) and the CPU reference implementations the
 * JVM tests assert against. No Android types — fully JVM-testable.
 *
 * Intensity contract: every effect receives the model's normalized 0..1
 * intensity ([ClipEffect.intensity]) and maps it to its own physical
 * parameter here. intensity == 0 is the IDENTITY for every effect.
 */
object ClipEffectMath {

    // ---------------------------------------------------------- BLUR (I2)

    /** Sigma range: intensity 1.0 -> sigma 3.0 (per the Fase I spec). */
    const val MAX_SIGMA = 3.0f

    /** 7-tap kernel (radius 3) — a 5x5 / 7x7-equivalent separable kernel. */
    const val TAPS = 7
    const val RADIUS = 3

    /**
     * Normalized Gaussian weights for [sigma]; sigma <= 0 collapses to the
     * identity kernel (center weight 1). One FloatArray of length [TAPS].
     */
    fun blurWeights(sigma: Float): FloatArray {
        if (sigma <= 0f) {
            return floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f)
        }
        val s2 = 2f * sigma * sigma
        val raw = FloatArray(TAPS) { i ->
            val d = (i - RADIUS).toFloat()
            exp(-(d * d) / s2)
        }
        val sum = raw.sum()
        return FloatArray(TAPS) { raw[it] / sum }
    }

    /** intensity -> sigma (0 = no blur, 1 = MAX_SIGMA). */
    fun blurSigma(intensity: Float): Float =
        intensity.coerceIn(0f, 1f) * MAX_SIGMA

    /**
     * CPU reference of ONE separable pass. ARGB in/out; [horizontal] picks
     * the axis. Edges are clamped (no wrap). The full blur = horizontal
     * pass THEN vertical pass (the GPU runs the same two passes).
     */
    fun applyBlurPass(
        pixels: IntArray,
        width: Int,
        height: Int,
        sigma: Float,
        horizontal: Boolean,
    ): IntArray {
        require(pixels.size == width * height) { "pixel buffer must match w*h" }
        val weights = blurWeights(sigma)
        val out = IntArray(pixels.size)
        val main = if (horizontal) width else height
        val cross = if (horizontal) height else width
        for (crossIdx in 0 until cross) {
            for (mainIdx in 0 until main) {
                var r = 0f; var g = 0f; var b = 0f
                for (t in -RADIUS..RADIUS) {
                    val srcMain = (mainIdx + t).coerceIn(0, main - 1)
                    val index = if (horizontal) crossIdx * width + srcMain else srcMain * width + crossIdx
                    val c = pixels[index]
                    val w = weights[t + RADIUS]
                    r += ((c ushr 16) and 0xFF) * w
                    g += ((c ushr 8) and 0xFF) * w
                    b += (c and 0xFF) * w
                }
                val index = if (horizontal) crossIdx * width + mainIdx else mainIdx * width + crossIdx
                out[index] = (0xFF shl 24) or
                    (r.roundToInt().coerceIn(0, 255) shl 16) or
                    (g.roundToInt().coerceIn(0, 255) shl 8) or
                    b.roundToInt().coerceIn(0, 255)
            }
        }
        return out
    }

    /** Full two-pass blur (H then V) — the CPU reference of the GPU pair. */
    fun applyBlur(
        pixels: IntArray,
        width: Int,
        height: Int,
        sigma: Float,
    ): IntArray = applyBlurPass(
        applyBlurPass(pixels, width, height, sigma, horizontal = true),
        width, height, sigma, horizontal = false,
    )

    /** Separable blur fragment shader — ONE pass; the glue installs it twice. */
    fun blurFragmentShaderCode(): String = """
#version 100
precision mediump float;
uniform sampler2D uTexSampler;
varying vec2 vTexSamplingCoord;
uniform vec2 u_TexelStep;
uniform float u_Weights[7];

void main() {
  vec3 acc = vec3(0.0);
  for (int i = 0; i < 7; i++) {
    int d = i - 3;
    vec2 uv = vTexSamplingCoord + u_TexelStep * float(d);
    acc += texture2D(uTexSampler, uv).rgb * u_Weights[i];
  }
  gl_FragColor = vec4(acc, 1.0);
}
""".trim()

    // ------------------------------------------------------ VIGNETTE (I3)

    /**
     * Brightness factor at normalized squared distance [d2] (0 = center,
     * 1 = exact corner): 1 - intensity * d2 — the Fase I formula, with d
     * normalized so the corners sit at d2 == 1 (intensity 1.0 -> black
     * corners, unchanged center).
     */
    fun vignetteFactorAt(d2: Float, intensity: Float): Float =
        1f - intensity.coerceIn(0f, 1f) * d2.coerceIn(0f, 1f)

    /**
     * Normalized squared distance of a pixel: ((x/w - 0.5) * 2)² +
     * ((y/h - 0.5) * 2)², divided by 2 so the exact CORNER == 1.
     */
    fun vignetteD2(x: Int, y: Int, width: Int, height: Int): Float {
        val nx = (x + 0.5f) / width * 2f - 1f
        val ny = (y + 0.5f) / height * 2f - 1f
        return (nx * nx + ny * ny) / 2f
    }

    /** CPU reference (single pass, corners darken with intensity). */
    fun applyVignette(
        pixels: IntArray,
        width: Int,
        height: Int,
        intensity: Float,
    ): IntArray {
        require(pixels.size == width * height) { "pixel buffer must match w*h" }
        val out = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val k = vignetteFactorAt(vignetteD2(x, y, width, height), intensity)
                val index = y * width + x
                val c = pixels[index]
                val a = (c ushr 24) and 0xFF
                val r = (((c ushr 16) and 0xFF) * k).toInt().coerceIn(0, 255)
                val g = (((c ushr 8) and 0xFF) * k).toInt().coerceIn(0, 255)
                val b = ((c and 0xFF) * k).toInt().coerceIn(0, 255)
                out[index] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    fun vignetteFragmentShaderCode(): String = """
#version 100
precision mediump float;
uniform sampler2D uTexSampler;
varying vec2 vTexSamplingCoord;
uniform float u_Intensity;

void main() {
  vec2 d = (vTexSamplingCoord - vec2(0.5)) * 2.0;
  float d2 = (d.x * d.x + d.y * d.y) / 2.0;
  float k = 1.0 - clamp(u_Intensity, 0.0, 1.0) * clamp(d2, 0.0, 1.0);
  vec4 c = texture2D(uTexSampler, vTexSamplingCoord);
  gl_FragColor = vec4(c.rgb * k, c.a);
}
""".trim()

    // ------------------------------------------------------- SHARPEN (I4)

    /** Unsharp-mask amount range: intensity 1.0 -> a = 1.0. */
    const val MAX_AMOUNT = 1.0f

    /** intensity -> unsharp amount a (0 = identity). */
    fun sharpenAmount(intensity: Float): Float =
        intensity.coerceIn(0f, 1f) * MAX_AMOUNT

    /**
     * CPU reference of the 3x3 unsharp mask:
     * out = center * (1 + 4a) - a * (up + down + left + right).
     * Edges clamp; alpha preserved. a = 0 -> identity.
     */
    fun applySharpen(
        pixels: IntArray,
        width: Int,
        height: Int,
        intensity: Float,
    ): IntArray {
        require(pixels.size == width * height) { "pixel buffer must match w*h" }
        val a = sharpenAmount(intensity)
        val out = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val c = pixels[index]
                val up = pixels[(y - 1).coerceIn(0, height - 1) * width + x]
                val down = pixels[(y + 1).coerceIn(0, height - 1) * width + x]
                val left = pixels[y * width + (x - 1).coerceIn(0, width - 1)]
                val right = pixels[y * width + (x + 1).coerceIn(0, width - 1)]
                out[index] = if (a == 0f) {
                    c
                } else {
                    val r = ((c ushr 16) and 0xFF) * (1f + 4f * a) -
                        a * (((up ushr 16) and 0xFF) + ((down ushr 16) and 0xFF) +
                            ((left ushr 16) and 0xFF) + ((right ushr 16) and 0xFF))
                    val g = ((c ushr 8) and 0xFF) * (1f + 4f * a) -
                        a * (((up ushr 8) and 0xFF) + ((down ushr 8) and 0xFF) +
                            ((left ushr 8) and 0xFF) + ((right ushr 8) and 0xFF))
                    val b = (c and 0xFF) * (1f + 4f * a) -
                        a * ((up and 0xFF) + (down and 0xFF) + (left and 0xFF) + (right and 0xFF))
                    val alpha = (c ushr 24) and 0xFF
                    (alpha shl 24) or
                        (r.toInt().coerceIn(0, 255) shl 16) or
                        (g.toInt().coerceIn(0, 255) shl 8) or
                        b.toInt().coerceIn(0, 255)
                }
            }
        }
        return out
    }

    fun sharpenFragmentShaderCode(): String = """
#version 100
precision mediump float;
uniform sampler2D uTexSampler;
varying vec2 vTexSamplingCoord;
uniform vec2 u_TexelSize;
uniform float u_Amount;

void main() {
  vec4 c = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 up = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, u_TexelSize.y)).rgb;
  vec3 down = texture2D(uTexSampler, vTexSamplingCoord - vec2(0.0, u_TexelSize.y)).rgb;
  vec3 left = texture2D(uTexSampler, vTexSamplingCoord - vec2(u_TexelSize.x, 0.0)).rgb;
  vec3 right = texture2D(uTexSampler, vTexSamplingCoord + vec2(u_TexelSize.x, 0.0)).rgb;
  vec3 sharp = c.rgb * (1.0 + 4.0 * u_Amount) - u_Amount * (up + down + left + right);
  gl_FragColor = vec4(clamp(sharp, 0.0, 1.0), c.a);
}
""".trim()
}

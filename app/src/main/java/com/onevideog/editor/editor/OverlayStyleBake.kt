package com.onevideog.editor.editor

import com.onevideog.editor.data.OverlayGlowStyle
import com.onevideog.editor.data.OverlayShadowStyle
import com.onevideog.editor.data.OverlayStyle
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * v0.9.0 (Fase L, L1 — D3) — PURE bake math for overlay styles: shadow + glow
 * composed UNDER the overlay's content pixels. CPU reference implementation
 * over IntArrays (ARGB, non-premultiplied — the exact array Bitmap.getPixels
 * / setPixels exchange), in the [ClipEffectMath] pattern: no android.graphics
 * in the core, fully JVM-testable, small goldens.
 *
 * Bake-to-texture contract (Opção A): the bitmap builder in
 * [OverlayBitmapRenderer] renders the content bitmap WITHOUT native paint
 * shadows, converts it to an IntArray, runs [bake] here and wraps the result
 * into the bitmap that PREVIEW and EXPORT both consume from the SAME
 * LruCache — one baked bitmap per (content, style) key, style parity by
 * construction. No second render path for styled content exists.
 *
 * Semantics (all fractions are of the CONTENT bitmap height, [refSidePx]):
 *  - shadow: the content's alpha channel, shifted by (offsetX, offsetY),
 *    blurred with a separable Gaussian (sigma = blur*refSide / 3), tinted
 *    [OverlayShadowStyle.colorArgb] and scaled by [OverlayShadowStyle.opacity];
 *  - glow: the content's alpha channel, blurred with
 *    sigma = radius*refSide / 3, tinted [OverlayGlowStyle.colorArgb] scaled by
 *    [OverlayGlowStyle.strength];
 *  - composition order: glow (back) ← shadow ← content (front);
 *  - output canvas: the content centred with a symmetric [padPx] border so
 *    nothing clips — the pad is deterministic from the style, and the
 *    content centre stays the bitmap centre (both pipelines rely on this).
 */
object OverlayStyleBake {

    /** The baked result: content centred in a larger canvas, [padPx] on each side. */
    class BakedPixels(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        /** Symmetric border around the ORIGINAL content, in px. */
        val padPx: Int,
    )

    /**
     * Deterministic pad cap (fraction of the reference side): a corrupt /
     * extreme style can inflate the canvas by at most this — beyond it the
     * blur is clamped (documented limitation, matches the anchor-coerce
     * behaviour of the camera).
     */
    const val MAX_PAD_FRACTION = 0.25f

    /** Style blur/radius extents are computed with sigma = px / SIGMA_DIV. */
    const val SIGMA_DIV = 3f

    /**
     * The symmetric pad [bake] will use for [style] at a content side of
     * [refSidePx]: the largest extent any layer can reach past the content
     * edge — |offset| + 3σ for the shadow, 3σ for the glow — capped at
     * [MAX_PAD_FRACTION] × refSide. Pure, unit-tested (the bitmap builder
     * and both pipelines derive every drawn size from this number).
     */
    fun padPx(style: OverlayStyle, refSidePx: Int): Int {
        if (refSidePx <= 0) return 0
        val cap = (refSidePx * MAX_PAD_FRACTION).toInt().coerceAtLeast(1)
        var extent = 0f
        style.shadow?.let { s ->
            val blurSigma = s.blur.coerceIn(0f, MAX_PAD_FRACTION) * refSidePx / SIGMA_DIV
            extent = max(extent, abs(s.offsetX) * refSidePx + blurSigma * SIGMA_DIV)
            extent = max(extent, abs(s.offsetY) * refSidePx + blurSigma * SIGMA_DIV)
        }
        style.glow?.let { g ->
            val glowSigma = g.radius.coerceIn(0f, MAX_PAD_FRACTION) * refSidePx / SIGMA_DIV
            extent = max(extent, glowSigma * SIGMA_DIV)
        }
        return ceil(extent).toInt().coerceIn(0, cap)
    }

    /**
     * Normalized Gaussian kernel for [sigmaPx]: 2*radius+1 weights summing to
     * 1, radius = ceil(3σ). sigma <= 0 → the identity kernel (center 1).
     */
    fun gaussianKernel(sigmaPx: Float): FloatArray {
        if (sigmaPx <= 0f) return floatArrayOf(1f)
        val radius = ceil(sigmaPx * SIGMA_DIV).toInt().coerceAtLeast(1)
        val s2 = 2f * sigmaPx * sigmaPx
        val raw = FloatArray(2 * radius + 1) { i ->
            val d = (i - radius).toFloat()
            exp(-(d * d) / s2)
        }
        val sum = raw.sum()
        return FloatArray(raw.size) { raw[it] / sum }
    }

    /**
     * ONE separable pass over a per-pixel alpha plane (0..1 floats), clamped
     * edges. [horizontal] picks the axis. The full blur = horizontal THEN
     * vertical.
     */
    fun blurAlphaPass(
        alpha: FloatArray,
        width: Int,
        height: Int,
        sigmaPx: Float,
        horizontal: Boolean,
    ): FloatArray {
        require(alpha.size == width * height) { "alpha plane must match w*h" }
        val kernel = gaussianKernel(sigmaPx)
        val radius = (kernel.size - 1) / 2
        val out = FloatArray(alpha.size)
        val main = if (horizontal) width else height
        val cross = if (horizontal) height else width
        for (crossIdx in 0 until cross) {
            for (mainIdx in 0 until main) {
                var acc = 0f
                for (t in -radius..radius) {
                    val srcMain = (mainIdx + t).coerceIn(0, main - 1)
                    val index = if (horizontal) crossIdx * width + srcMain else srcMain * width + crossIdx
                    acc += alpha[index] * kernel[t + radius]
                }
                val index = if (horizontal) crossIdx * width + mainIdx else mainIdx * width + crossIdx
                out[index] = acc
            }
        }
        return out
    }

    /** Full separable blur (horizontal then vertical). */
    fun blurAlpha(alpha: FloatArray, width: Int, height: Int, sigmaPx: Float): FloatArray {
        if (sigmaPx <= 0f) return alpha.copyOf()
        return blurAlphaPass(
            blurAlphaPass(alpha, width, height, sigmaPx, horizontal = true),
            width, height, sigmaPx, horizontal = false,
        )
    }

    /**
     * Standard source-over compositing of two non-premultiplied ARGB pixels.
     * Public because the JVM goldens pin it directly.
     */
    fun over(top: Int, bottom: Int): Int {
        val aT = (top ushr 24) and 0xFF
        if (aT == 0xFF) return top
        if (aT == 0) return bottom
        val aB = (bottom ushr 24) and 0xFF
        val aTf = aT / 255f
        val aBf = aB / 255f
        val aOut = aTf + aBf * (1f - aTf)
        if (aOut <= 0f) return 0
        fun channel(shift: Int): Int {
            val cT = ((top ushr shift) and 0xFF) / 255f
            val cB = ((bottom ushr shift) and 0xFF) / 255f
            val cOut = (cT * aTf + cB * aBf * (1f - aTf)) / aOut
            return (cOut * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        val a = (aOut * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (a shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /**
     * The bake itself. [src] is the content pixels (w×h, non-premultiplied
     * ARGB); [refSidePx] converts the style's height fractions to px (the
     * content height). Returns the larger centred canvas with glow, shadow
     * and content composited. Identity guarantee: a default style returns
     * the content UNCHANGED (same IntArray values, pad 0) — locked by
     * OverlayStyleBakeTest.
     */
    fun bake(
        src: IntArray,
        width: Int,
        height: Int,
        style: OverlayStyle,
        refSidePx: Int,
    ): BakedPixels {
        require(src.size == width * height) { "pixel buffer must match w*h" }
        if (style.isDefault || width <= 0 || height <= 0) {
            return BakedPixels(src.copyOf(), width, height, 0)
        }
        val pad = padPx(style, refSidePx)
        val outW = width + 2 * pad
        val outH = height + 2 * pad
        val out = IntArray(outW * outH)

        // ---- content alpha plane (0..1), placed on the output canvas ----
        fun placedAlpha(): FloatArray {
            val plane = FloatArray(outW * outH)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val a = (src[y * width + x] ushr 24) and 0xFF
                    if (a != 0) {
                        plane[(y + pad) * outW + (x + pad)] = a / 255f
                    }
                }
            }
            return plane
        }

        // ---- glow (back-most) ----
        style.glow?.let { g: OverlayGlowStyle ->
            val strength = g.strength.coerceIn(0f, 1f)
            if (strength > 0f) {
                val sigma = g.radius.coerceIn(0f, MAX_PAD_FRACTION) * refSidePx / SIGMA_DIV
                val halo = blurAlpha(placedAlpha(), outW, outH, sigma)
                blendLayer(out, halo, g.colorArgb, strength)
            }
        }

        // ---- shadow ----
        style.shadow?.let { s: OverlayShadowStyle ->
            val opacity = s.opacity.coerceIn(0f, 1f)
            if (opacity > 0f) {
                val plane = placedAlpha()
                if (s.offsetX != 0f || s.offsetY != 0f) {
                    val dx = (s.offsetX * refSidePx).roundToInt()
                    val dy = (s.offsetY * refSidePx).roundToInt()
                    val shifted = FloatArray(plane.size)
                    for (y in 0 until outH) {
                        val sy = y - dy
                        if (sy < 0 || sy >= outH) continue
                        for (x in 0 until outW) {
                            val sx = x - dx
                            if (sx < 0 || sx >= outW) continue
                            shifted[y * outW + x] = plane[sy * outW + sx]
                        }
                    }
                    plane.copyFrom(shifted)
                }
                val sigma = s.blur.coerceIn(0f, MAX_PAD_FRACTION) * refSidePx / SIGMA_DIV
                val blurred = blurAlpha(plane, outW, outH, sigma)
                blendLayer(out, blurred, s.colorArgb, opacity)
            }
        }

        // ---- content (front-most) ----
        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = src[y * width + x]
                if ((c ushr 24) and 0xFF == 0) continue
                val index = (y + pad) * outW + (x + pad)
                out[index] = over(c, out[index])
            }
        }
        return BakedPixels(out, outW, outH, pad)
    }

    /** Tints an alpha plane with [colorArgb] × [gain] and composites it under. */
    private fun blendLayer(out: IntArray, alphaPlane: FloatArray, colorArgb: Int, gain: Float) {
        val r = (colorArgb ushr 16) and 0xFF
        val g = (colorArgb ushr 8) and 0xFF
        val b = colorArgb and 0xFF
        for (i in out.indices) {
            val a = alphaPlane[i] * gain
            if (a <= 0.001f) continue
            val aInt = (a * 255f + 0.5f).toInt().coerceIn(0, 255)
            if (aInt == 0) continue
            out[i] = over((aInt shl 24) or (r shl 16) or (g shl 8) or b, out[i])
        }
    }

    private fun FloatArray.copyFrom(other: FloatArray) {
        require(size == other.size)
        other.copyInto(this)
    }
}

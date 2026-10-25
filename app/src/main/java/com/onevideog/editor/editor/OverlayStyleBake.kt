package com.onevideog.editor.editor

import com.onevideog.editor.data.OverlayStyle
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * v0.9.0 (Fase L1, decisão D3) — THE ONE style bake: a pure-JVM, deterministic
 * CPU pass that renders an [OverlayStyle] (shadow + glow) AROUND source
 * pixels. Operates on plain [IntArray]s (straight ARGB) — no android.graphics,
 * no GPU, no shader (Opção A rule: zero new shaders in the video path) — so
 * the JVM suite tests the EXACT code the device runs.
 *
 * Geometry contract (parity by construction):
 *  - The result bitmap surrounds the source content with SYMMETRIC margins
 *    ([padX]/[padY]), so the content centre stays the bitmap centre — the
 *    export anchors and the preview placement both treat the bitmap centre
 *    as the overlay centre, unchanged.
 *  - [boxScale] = bakedWidth/sourceWidth (>= 1). BOTH pipelines multiply the
 *    overlay's `scale` fraction by it before the existing placement math
 *    ([OverlayLayout.previewSize] / [OverlayLayout.overlayExportScale]), so
 *    the CONTENT box on the frame is exactly the unstyled box and the style
 *    only adds pixels around it.
 *  - Margins derive from the style fractions times the SOURCE height — a
 *    pure function of (sourceW, sourceH, style), so both pipelines compute
 *    the same [boxScale] without sharing state.
 *
 * Compositing order (bottom to top): glow, shadow, source.
 *
 * Blur: separable Gaussian (1 horizontal pass + 1 vertical pass, kernel
 * radius = ceil(3σ), edge-clamped) over the source ALPHA; the tint carries
 * the style colour with the blurred alpha scaled by opacity/strength.
 * σ = 0 skips the blur (hard shadow). The same kernel serves both layers.
 */
object OverlayStyleBake {

    /**
     * Margin cap per side, as a fraction of the source height. Bounds the
     * baked bitmap (and its memory) even for extreme sanitized styles.
     */
    const val MAX_PAD_FRACTION = 0.35f

    /** One bake result: pixels, geometry and the box-growth factor. */
    data class BakeResult(
        val width: Int,
        val height: Int,
        val pixels: IntArray,
        /** bakedWidth / sourceWidth (>= 1) — the placement box factor. */
        val boxScale: Float,
    ) {
        override fun equals(other: Any?): Boolean = other is BakeResult &&
            width == other.width && height == other.height &&
            boxScale == other.boxScale && pixels.contentEquals(other.pixels)

        override fun hashCode(): Int =
            31 * (31 * width + height) + pixels.contentHashCode()
    }

    // ------------------------------------------------------------- geometry

    /** Horizontal symmetric margin (px) the style needs around the content. */
    fun padX(style: OverlayStyle, srcH: Int): Int {
        if (srcH <= 0) return 0
        val shadow = style.shadow
        val glow = style.glow
        var extent = 0f
        if (shadow != null) {
            extent = max(extent, shadow.blur * 3f + kotlin.math.abs(shadow.offsetX))
        }
        if (glow != null) extent = max(extent, glow.radius * 3f)
        return padPx(extent, srcH)
    }

    /** Vertical symmetric margin (px) the style needs around the content. */
    fun padY(style: OverlayStyle, srcH: Int): Int {
        if (srcH <= 0) return 0
        val shadow = style.shadow
        val glow = style.glow
        var extent = 0f
        if (shadow != null) {
            extent = max(extent, shadow.blur * 3f + kotlin.math.abs(shadow.offsetY))
        }
        if (glow != null) extent = max(extent, glow.radius * 3f)
        return padPx(extent, srcH)
    }

    /**
     * THE box factor both placement paths multiply the overlay scale with
     * (pure — no pixels needed, so the preview can size the box before/while
     * the bake renders and the export passes the SAME factor into
     * [OverlayLayout.overlayExportScale]).
     */
    fun boxScale(style: OverlayStyle, srcW: Int, srcH: Int): Float {
        if (style.isDefault || srcW <= 0 || srcH <= 0) return 1f
        val bakedW = srcW + 2 * padX(style, srcH)
        return bakedW.toFloat() / srcW
    }

    // ----------------------------------------------------------------- bake

    /**
     * Bakes [style] around [srcW]×[srcH] straight-ARGB [src] pixels.
     * Deterministic: same inputs -> same outputs (unit-golden contract).
     */
    fun bake(src: IntArray, srcW: Int, srcH: Int, style: OverlayStyle): BakeResult {
        require(src.size >= srcW * srcH) { "src too small for $srcW x $srcH" }
        if (style.isDefault || srcW <= 0 || srcH <= 0) {
            return BakeResult(srcW, srcH, src.copyOf(), 1f)
        }
        val padX = padX(style, srcH)
        val padY = padY(style, srcH)
        val outW = srcW + 2 * padX
        val outH = srcH + 2 * padY

        // Canvas: straight-ARGB floats (r, g, b, a per pixel).
        val canvas = FloatArray(outW * outH * 4)

        // The source alpha EMBEDDED in the padded canvas — the blur runs on
        // the padded dims so the glow/shadow tails have room to spread past
        // the content edge (a blur on the raw content-sized array would be
        // edge-clamped and visually cut at the boundary).
        val embedded = FloatArray(outW * outH)
        for (y in 0 until srcH) {
            val row = (y + padY) * outW + padX
            for (x in 0 until srcW) {
                embedded[row + x] = (src[y * srcW + x] ushr 24) / 255f
            }
        }

        // 1) glow (bottom layer): blurred tinted alpha around the shape.
        style.glow?.let { glow ->
            val blurred = if (glow.radius <= 0f) embedded.copyOf()
            else gaussianBlur(embedded, outW, outH, glow.radius * srcH)
            val tint = argbToRgbaColor(glow.colorArgb, glow.strength)
            pasteTinted(canvas, outW, outH, blurred, outW, outH, 0, 0, tint)
        }

        // 2) shadow (over glow): blurred alpha, offset, tinted.
        style.shadow?.let { shadow ->
            val blurred = if (shadow.blur <= 0f) embedded.copyOf()
            else gaussianBlur(embedded, outW, outH, shadow.blur * srcH)
            val tint = argbToRgbaColor(shadow.colorArgb, shadow.opacity)
            val dx = Math.round(shadow.offsetX * srcH)
            val dy = Math.round(shadow.offsetY * srcH)
            pasteTinted(canvas, outW, outH, blurred, outW, outH, dx, dy, tint)
        }

        // 3) the source content on top.
        pasteSourceOver(canvas, outW, src, srcW, srcH, padX, padY)

        val pixels = IntArray(outW * outH)
        for (i in pixels.indices) {
            val o = i * 4
            pixels[i] = rgbaToArgb(canvas[o], canvas[o + 1], canvas[o + 2], canvas[o + 3])
        }
        return BakeResult(
            width = outW,
            height = outH,
            pixels = pixels,
            boxScale = outW.toFloat() / srcW,
        )
    }

    // ------------------------------------------------------- separable blur

    /**
     * Separable Gaussian over the alpha channel: one horizontal pass then one
     * vertical pass with the SAME 1-D kernel (radius = ceil(3σ), normalized,
     * edge-clamped). Exact-ish within float error and fully deterministic.
     * Returns a new array; the input is untouched.
     */
    fun gaussianBlur(alpha: FloatArray, w: Int, h: Int, sigmaPx: Float): FloatArray {
        require(alpha.size >= w * h)
        if (sigmaPx <= 0f || w <= 0 || h <= 0) return alpha.copyOf()
        val radius = ceil(3f * sigmaPx).toInt().coerceAtLeast(1)
        val kernel = FloatArray(2 * radius + 1)
        var sum = 0f
        for (j in -radius..radius) {
            val v = exp(-(j * j).toDouble() / (2.0 * sigmaPx * sigmaPx)).toFloat()
            kernel[j + radius] = v
            sum += v
        }
        for (i in kernel.indices) kernel[i] /= sum

        val tmp = FloatArray(w * h)
        // Horizontal pass.
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var acc = 0f
                for (j in -radius..radius) {
                    val sx = (x + j).coerceIn(0, w - 1)
                    acc += alpha[row + sx] * kernel[j + radius]
                }
                tmp[row + x] = acc
            }
        }
        // Vertical pass.
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0f
                for (j in -radius..radius) {
                    val sy = (y + j).coerceIn(0, h - 1)
                    acc += tmp[sy * w + x] * kernel[j + radius]
                }
                out[y * w + x] = acc
            }
        }
        return out
    }

    // ----------------------------------------------------------- internals

    private fun padPx(extentFraction: Float, srcH: Int): Int {
        val capped = extentFraction.coerceIn(0f, MAX_PAD_FRACTION)
        return (ceil(capped * srcH).toInt()).coerceAtLeast(0)
    }

    /** Opaque-ish tint colour as (r, g, b, alphaScale) straight floats. */
    private fun argbToRgbaColor(argb: Int, alphaScale: Float): FloatArray = floatArrayOf(
        ((argb shr 16) and 0xFF) / 255f,
        ((argb shr 8) and 0xFF) / 255f,
        (argb and 0xFF) / 255f,
        alphaScale.coerceIn(0f, 1f),
    )

    /**
     * Pastes a tinted layer (colour + per-pixel blurred alpha) at the offset
     * with source-over onto the canvas. Offsets may be negative (shadow
     * shifted up/left); clipped paste, no allocation.
     */
    private fun pasteTinted(
        canvas: FloatArray,
        outW: Int,
        outH: Int,
        layerAlpha: FloatArray,
        srcW: Int,
        srcH: Int,
        offsetX: Int,
        offsetY: Int,
        tint: FloatArray,
    ) {
        val r = tint[0]; val g = tint[1]; val b = tint[2]; val aScale = tint[3]
        for (y in 0 until srcH) {
            val dy = y + offsetY
            if (dy < 0 || dy >= outH) continue
            for (x in 0 until srcW) {
                val dx = x + offsetX
                if (dx < 0 || dx >= outW) continue
                val a = (layerAlpha[y * srcW + x] * aScale).coerceIn(0f, 1f)
                if (a <= 0f) continue
                sourceOver(canvas, (dy * outW + dx) * 4, r, g, b, a)
            }
        }
    }

    /** Pastes the straight-ARGB source at the offset with source-over. */
    private fun pasteSourceOver(
        canvas: FloatArray,
        outW: Int,
        src: IntArray,
        srcW: Int,
        srcH: Int,
        offsetX: Int,
        offsetY: Int,
    ) {
        for (y in 0 until srcH) {
            val dy = y + offsetY
            for (x in 0 until srcW) {
                val dx = x + offsetX
                val p = src[y * srcW + x]
                val a = ((p ushr 24) and 0xFF) / 255f
                val o = (dy * outW + dx) * 4
                if (a >= 1f) {
                    canvas[o] = ((p shr 16) and 0xFF) / 255f
                    canvas[o + 1] = ((p shr 8) and 0xFF) / 255f
                    canvas[o + 2] = (p and 0xFF) / 255f
                    canvas[o + 3] = 1f
                } else if (a > 0f) {
                    sourceOver(
                        canvas, o,
                        ((p shr 16) and 0xFF) / 255f,
                        ((p shr 8) and 0xFF) / 255f,
                        (p and 0xFF) / 255f,
                        a,
                    )
                }
            }
        }
    }

    /** One source-over blend of (r,g,b,a) onto canvas[o..o+3] (straight). */
    private fun sourceOver(canvas: FloatArray, o: Int, r: Float, g: Float, b: Float, a: Float) {
        val dstA = canvas[o + 3]
        val outA = a + dstA * (1f - a)
        if (outA <= 0f) {
            canvas[o] = 0f; canvas[o + 1] = 0f; canvas[o + 2] = 0f; canvas[o + 3] = 0f
            return
        }
        canvas[o] = (r * a + canvas[o] * dstA * (1f - a)) / outA
        canvas[o + 1] = (g * a + canvas[o + 1] * dstA * (1f - a)) / outA
        canvas[o + 2] = (b * a + canvas[o + 2] * dstA * (1f - a)) / outA
        canvas[o + 3] = outA
    }

    private fun rgbaToArgb(r: Float, g: Float, b: Float, a: Float): Int {
        val ai = (a.coerceIn(0f, 1f) * 255f).roundToInt()
        val ri = (r.coerceIn(0f, 1f) * 255f).roundToInt()
        val gi = (g.coerceIn(0f, 1f) * 255f).roundToInt()
        val bi = (b.coerceIn(0f, 1f) * 255f).roundToInt()
        return (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}

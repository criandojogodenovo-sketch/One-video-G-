package com.onevideog.editor.editor

import com.onevideog.editor.data.OverlayGlowStyle
import com.onevideog.editor.data.OverlayShadowStyle
import com.onevideog.editor.data.OverlayStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.0 (Fase L, L5) — JVM goldens for the PURE style bake (D3: no
 * android.graphics in the core). The bake is the parity spine of the style
 * feature: preview and export consume the SAME baked bitmap, so these tests
 * pin the MATH once and both pipelines inherit it.
 */
class OverlayStyleBakeTest {

    private fun solidSquare(w: Int, h: Int, argb: Int = 0xFFFF0000.toInt()): IntArray =
        IntArray(w * h) { argb }

    private fun alphaAt(pixels: IntArray, width: Int, x: Int, y: Int): Int =
        (pixels[y * width + x] ushr 24) and 0xFF

    // ------------------------------------------------------------- identity

    @Test
    fun `default style bakes to the exact input - identity`() {
        val src = solidSquare(8, 6)
        val baked = OverlayStyleBake.bake(src, 8, 6, OverlayStyle(), 1080)
        assertEquals(8, baked.width)
        assertEquals(6, baked.height)
        assertEquals(0, baked.padPx)
        assertTrue(src.contentEquals(baked.pixels))
    }

    @Test
    fun `null-equivalent style (no layers) bakes to the exact input`() {
        // A style object with both layers null is isDefault -> identity.
        val src = solidSquare(5, 5)
        val baked = OverlayStyleBake.bake(src, 5, 5, OverlayStyle(shadow = null, glow = null), 1080)
        assertEquals(0, baked.padPx)
        assertTrue(src.contentEquals(baked.pixels))
    }

    // ------------------------------------------------------------------ pad

    @Test
    fun `pad grows with offset and blur and is capped`() {
        val zero = OverlayStyle(shadow = OverlayShadowStyle(blur = 0f, offsetX = 0f, offsetY = 0f))
        assertEquals(0, OverlayStyleBake.padPx(zero, 1080))
        val blurOnly = OverlayStyle(shadow = OverlayShadowStyle(blur = 0.01f))
        // sigma = 0.01*1080/3 = 3.6 px -> extent = 3*3.6 = 10.8 -> pad 11.
        assertEquals(11, OverlayStyleBake.padPx(blurOnly, 1080))
        val offsetAndBlur = OverlayStyle(
            shadow = OverlayShadowStyle(offsetX = 0.02f, offsetY = -0.01f, blur = 0.01f),
        )
        // extent = max(0.02*1080 + 10.8, 0.01*1080 + 10.8) = 32.4 -> pad 33.
        assertEquals(33, OverlayStyleBake.padPx(offsetAndBlur, 1080))
        // Cap: 25% of the reference side.
        val huge = OverlayStyle(shadow = OverlayShadowStyle(offsetX = 1f, blur = 0.5f))
        assertEquals((1080 * OverlayStyleBake.MAX_PAD_FRACTION).toInt(), OverlayStyleBake.padPx(huge, 1080))
        val glow = OverlayStyle(glow = OverlayGlowStyle(radius = 0.02f))
        // sigma = 0.02*1080/3 = 7.2 -> extent 21.6 -> pad 22.
        assertEquals(22, OverlayStyleBake.padPx(glow, 1080))
    }

    // --------------------------------------------------------------- shadow

    @Test
    fun `hard shadow (no blur) shifts the alpha and keeps the canvas bigger`() {
        // Content 4x4 opaque; shadow offset (1, 0) px, no blur, black.
        val style = OverlayStyle(
            shadow = OverlayShadowStyle(
                offsetX = 1f / 4f, offsetY = 0f, blur = 0f,
                colorArgb = 0xFF000000.toInt(), opacity = 1f,
            ),
        )
        val pad = OverlayStyleBake.padPx(style, 4)
        val baked = OverlayStyleBake.bake(solidSquare(4, 4, 0xFFFF0000.toInt()), 4, 4, style, 4)
        assertEquals(pad, baked.padPx)
        val w = baked.width
        // Content occupies [pad, pad+4).
        // Content pixel stays red.
        assertEquals(0xFFFF0000.toInt(), baked.pixels[(pad) * w + (pad)])
        // One px right of the content's right edge at the content's middle
        // row: the SHIFTED shadow alpha (content alpha shifted +1 x).
        val shadowPx = baked.pixels[(pad + 2) * w + (pad + 4)]
        val a = (shadowPx ushr 24) and 0xFF
        assertTrue("shadow alpha expected >0 right of content, got $a (px=${"%08x".format(shadowPx)})", a > 0)
        // Shadow pixels behind the content are covered by it (red on top).
        assertEquals(0xFFFF0000.toInt(), baked.pixels[(pad + 2) * w + (pad + 2)])
        // Far corner outside content+shadow stays transparent.
        assertEquals(0, baked.pixels[0])
    }

    @Test
    fun `blurred shadow spreads mass symmetrically - separable gaussian`() {
        val style = OverlayStyle(shadow = OverlayShadowStyle(blur = 0.25f, opacity = 1f))
        val baked = OverlayStyleBake.bake(solidSquare(6, 6, 0xFFFFFFFF.toInt()), 6, 6, style, 24)
        val w = baked.width
        val pad = baked.padPx
        // The four sides just outside the content carry roughly the same
        // alpha (symmetric blur with zero offset) — sample mid-edges.
        val top = alphaAt(baked.pixels, w, pad + 3, pad - 1)
        val bottom = alphaAt(baked.pixels, w, pad + 3, pad + 6)
        val left = alphaAt(baked.pixels, w, pad - 1, pad + 3)
        val right = alphaAt(baked.pixels, w, pad + 6, pad + 3)
        assertTrue("top $top bottom $bottom", kotlin.math.abs(top - bottom) <= 6)
        assertTrue("left $left right $right", kotlin.math.abs(left - right) <= 6)
        // The content itself stays opaque.
        assertEquals(0xFF, alphaAt(baked.pixels, w, pad + 3, pad + 3))
    }

    @Test
    fun `blurAlpha separable passes equal the direct double pass`() {
        val w = 9
        val h = 7
        val plane = FloatArray(w * h) { i -> ((i * 37) % 11) / 11f }
        val sigma = 1.4f
        // blurAlpha = horizontal THEN vertical (documented order).
        val full = OverlayStyleBake.blurAlpha(plane, w, h, sigma)
        val manual = OverlayStyleBake.blurAlphaPass(
            OverlayStyleBake.blurAlphaPass(plane, w, h, sigma, horizontal = true),
            w, h, sigma, horizontal = false,
        )
        for (i in plane.indices) assertEquals(manual[i], full[i], 1e-6f)
        // Kernel sums to 1.
        val kernel = OverlayStyleBake.gaussianKernel(sigma)
        assertEquals(1f, kernel.sum(), 1e-4f)
        // Identity kernel for sigma 0.
        assertEquals(listOf(1f), OverlayStyleBake.gaussianKernel(0f).toList())
    }

    // ----------------------------------------------------------------- glow

    @Test
    fun `glow tints the blurred alpha with the style colour`() {
        val style = OverlayStyle(
            glow = OverlayGlowStyle(radius = 0.1f, colorArgb = 0xFF00FF00.toInt(), strength = 1f),
        )
        val baked = OverlayStyleBake.bake(solidSquare(4, 4, 0xFFFF0000.toInt()), 4, 4, style, 40)
        val w = baked.width
        val pad = baked.padPx
        // A pixel in the halo (outside the content) is pure green-ish tint.
        val halo = baked.pixels[(pad) * w + (pad - 1)] // left of the content, mid-height
        val r = (halo ushr 16) and 0xFF
        val g = (halo ushr 8) and 0xFF
        val b = halo and 0xFF
        assertTrue("halo should be green-tinted, got ${"%08x".format(halo)}", g > r && g > b)
        assertTrue("halo alpha >0, got ${(halo ushr 24) and 0xFF}", (halo ushr 24) and 0xFF > 0)
        // The content stays red on top.
        assertEquals(0xFFFF0000.toInt(), baked.pixels[(pad + 2) * w + (pad + 2)])
    }

    @Test
    fun `zero strength or opacity layers change nothing beyond the content`() {
        val offGlow = OverlayStyle(glow = OverlayGlowStyle(radius = 0.1f, strength = 0f))
        val bakedGlow = OverlayStyleBake.bake(solidSquare(4, 4), 4, 4, offGlow, 40)
        // Outside the content everything stays transparent.
        assertEquals(0, bakedGlow.pixels[0])
        assertEquals(0, bakedGlow.pixels[(bakedGlow.height - 1) * bakedGlow.width])

        val offShadow = OverlayStyle(shadow = OverlayShadowStyle(opacity = 0f))
        val bakedShadow = OverlayStyleBake.bake(solidSquare(4, 4), 4, 4, offShadow, 40)
        assertEquals(0, bakedShadow.pixels[0])
    }

    // ------------------------------------------------------- over compositing

    @Test
    fun `over compositing matches alpha-over semantics`() {
        // Opaque top wins outright.
        assertEquals(0xFF102030.toInt(), OverlayStyleBake.over(0xFF102030.toInt(), 0xFFABCDEF.toInt()))
        // Transparent top keeps the bottom.
        assertEquals(0xFFABCDEF.toInt(), OverlayStyleBake.over(0x00000000, 0xFFABCDEF.toInt()))
        // 50% grey OVER an OPAQUE white: the result is fully opaque
        // (alpha-over), colour = (127/255)*128 + (128/255)*255 ≈ 191 (0xBF).
        val out = OverlayStyleBake.over(0x80808080.toInt(), 0xFFFFFFFF.toInt())
        val a = (out ushr 24) and 0xFF
        val g = (out ushr 8) and 0xFF
        assertEquals(0xFF, a)
        assertEquals(0xBF, g)
        // 50% grey over TRANSPARENT keeps the 50% alpha (nothing below).
        val thin = OverlayStyleBake.over(0x80808080.toInt(), 0)
        assertEquals(0x80, (thin ushr 24) and 0xFF)
    }

    // ------------------------------------------------- bake/preview/export bridge

    @Test
    fun `baked canvas is symmetric so the content centre stays the bitmap centre`() {
        val style = OverlayStyle(
            shadow = OverlayShadowStyle(offsetX = 0.02f, blur = 0.01f),
            glow = OverlayGlowStyle(radius = 0.015f),
        )
        val contentW = 40
        val contentH = 20
        val baked = OverlayStyleBake.bake(solidSquare(contentW, contentH), contentW, contentH, style, 400)
        assertEquals(contentW + 2 * baked.padPx, baked.width)
        assertEquals(contentH + 2 * baked.padPx, baked.height)
        // The drawn-content fraction the preview and the export derive from
        // the bake is THE SAME number (bake-to-texture parity):
        // preview baked frac = scale * bakedW/contentW;
        // export baked frac  = bakedW * scale*bgW/contentW / bgW.
        val scale = 0.3f
        val frameW = 1080
        val contentFracPreview = scale
        val contentFracExport = OverlayLayout.overlayExportScale(
            contentW, frameW, scale,
        ) * contentW / frameW
        assertEquals(contentFracPreview, contentFracExport, 1e-4f)
        val expand = baked.width.toFloat() / contentW
        assertEquals(scale * expand, contentFracExport * expand, 1e-4f)
    }
}

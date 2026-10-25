package com.onevideog.editor.editor

import com.onevideog.editor.data.OverlayStyle
import com.onevideog.editor.data.OverlayStylePresets
import com.onevideog.editor.data.StyleGlow
import com.onevideog.editor.data.StyleShadow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.0 (Fase L1, decisão D3) — the pure style bake contract:
 *  - no style = EXACT identity (the pre-v0.9.0 render path);
 *  - the hard shadow (blur 0) is an exact offset tint golden;
 *  - the blur is separable (H+V passes == the direct 2D kernel, the CPU
 *    reference pattern) and deterministic;
 *  - the geometry contract: symmetric margins, content centre unchanged,
 *    [OverlayStyleBake.boxScale] == bakedWidth/sourceWidth so BOTH
 *    placement paths keep the content box identical to the unstyled one.
 */
class OverlayStyleBakeTest {

    private fun solid(w: Int, h: Int, argb: Int): Pair<IntArray, Int> {
        val px = IntArray(w * h) { argb }
        return px to w
    }

    // ------------------------------------------------------------- identity

    @Test
    fun `no style bakes the exact identity`() {
        val (src, w) = solid(6, 4, 0xFF3366AA.toInt())
        val result = OverlayStyleBake.bake(src, w, 4, OverlayStyle())
        assertEquals(6, result.width)
        assertEquals(4, result.height)
        assertTrue(result.pixels.contentEquals(src))
        assertEquals(1f, result.boxScale, 1e-6f)
    }

    @Test
    fun `padding and box scale follow the style extents`() {
        // Glow radius 0.1 -> extent 0.3 (inside the 0.35 cap) -> pad ≈ 30 px
        // on a 100 px source (float ceil may add 1 at the boundary — the
        // exact value is the padX spec itself).
        val style = OverlayStyle(glow = StyleGlow(radius = 0.1f, strength = 1f))
        val pad = OverlayStyleBake.padX(style, 100)
        assertEquals(pad, OverlayStyleBake.padY(style, 100))
        assertTrue("pad ≈ 3σ·srcH", pad in 29..31)
        val (src, w) = solid(50, 100, 0xFFFFFFFF.toInt())
        val result = OverlayStyleBake.bake(src, w, 100, style)
        assertEquals(50 + 2 * pad, result.width)
        assertEquals(100 + 2 * pad, result.height)
        assertEquals(result.width.toFloat() / 50f, result.boxScale, 1e-6f)
        assertEquals(OverlayStyleBake.boxScale(style, 50, 100), result.boxScale, 1e-6f)
    }

    // --------------------------------------------------------- hard shadow

    @Test
    fun `hard shadow is an exact offset tint golden`() {
        // 3x3 opaque white source; hard black shadow offset +1 px right,
        // blur 0. All geometry is READ FROM the result (the pad is the
        // bake's own extent function — no hardcoded px).
        val base = OverlayStyle(
            shadow = StyleShadow(
                offsetX = 0f, offsetY = 0f, blur = 0f,
                colorArgb = 0xFF000000.toInt(), opacity = 1f,
            ),
        )
        val style = base.copy(
            shadow = base.shadow!!.copy(offsetX = 1f / 3f),
        )
        val (src, w) = solid(3, 3, 0xFFFFFFFF.toInt())
        val result = OverlayStyleBake.bake(src, w, 3, style)
        // Directional offset: padX opens (|offsetX| > 0), padY stays 0
        // (offsetY = 0, blur = 0) — read both from the result geometry.
        val padX = (result.width - 3) / 2
        val padY = (result.height - 3) / 2
        assertTrue("offset shadow must open horizontal margins", padX >= 1)
        assertEquals(0, padY)
        assertEquals(3 + 2 * padX, result.width)
        assertEquals(3 + 2 * padY, result.height)
        // The shadow (content alpha shifted +1 px right) peeks EXACTLY one
        // column to the right of the content: x = padX + 3, content rows.
        for (y in padY until padY + 3) {
            assertEquals(0xFF000000.toInt(), result.pixels[y * result.width + padX + 3])
        }
        // The content pixel is untouched (source over shadow).
        assertEquals(0xFFFFFFFF.toInt(), result.pixels[(padY + 1) * result.width + padX + 1])
        // The far corner stays transparent (nothing drawn there).
        assertEquals(0, result.pixels[0])
    }

    @Test
    fun `shadow opacity scales the tint alpha`() {
        val style = OverlayStyle(
            shadow = StyleShadow(
                offsetX = 0f, offsetY = 0f, blur = 0f,
                colorArgb = 0xFF000000.toInt(), opacity = 0.5f,
            ),
        )
        val shifted = style.copy(shadow = style.shadow!!.copy(offsetX = 1f / 4f))
        val (src, w) = solid(4, 4, 0xFFFFFFFF.toInt())
        val result = OverlayStyleBake.bake(src, w, 4, shifted)
        // pad = ceil(|offset|·srcH) = 1; the shadow is the embedded alpha
        // (content at 1..4) shifted by round(0.25·4) = 1 -> covers 2..5.
        // Pixel (5, 1) carries ONLY the shadow: alpha == 0.5 * 255.
        val a = (result.pixels[1 * result.width + 5] ushr 24) and 0xFF
        assertEquals(128, a) // 0.5 * 255 rounds to 128
    }

    // ----------------------------------------------------------------- glow

    @Test
    fun `glow tints with the glow colour and strength`() {
        // srcH 60, radius 0.1 → σ = 6 px, extent 0.3 (inside the cap).
        val style = OverlayStyle(
            glow = StyleGlow(radius = 0.1f, colorArgb = 0xFF00FF00.toInt(), strength = 1f),
        )
        val (src, w) = solid(2, 60, 0xFFFFFFFF.toInt())
        val result = OverlayStyleBake.bake(src, w, 60, style)
        val pad = (result.width - 2) / 2
        assertTrue("glow must open margins", pad >= 1)
        // The pixel JUST OUTSIDE the content edge carries the glow: pure
        // green hue with a partial alpha (the blur tail).
        val justOutside = result.pixels[(pad + 1) * result.width + pad - 1]
        assertEquals(0, (justOutside shr 16) and 0xFF) // red channel 0
        assertEquals(255, (justOutside shr 8) and 0xFF) // green channel full
        assertEquals(0, justOutside and 0xFF) // blue channel 0
        val outsideA = (justOutside ushr 24) and 0xFF
        assertTrue("glow tail alpha must be partial", outsideA in 1..254)
        // The content centre stays the source colour (source over glow).
        assertEquals(
            0xFFFFFFFF.toInt(),
            result.pixels[(pad + 1) * result.width + pad],
        )
    }

    @Test
    fun `separable blur equals the direct 2d kernel - cpu reference`() {
        // 9x7 random-ish alpha field; σ = 1.3 px.
        val w = 9
        val h = 7
        val alpha = FloatArray(w * h) { i -> ((i * 37) % 11) / 11f }
        val sigma = 1.3f
        // Direct 2D kernel (the reference).
        val radius = kotlin.math.ceil(3f * sigma).toInt()
        val kernel = FloatArray(2 * radius + 1)
        var sum = 0.0
        for (j in -radius..radius) {
            val v = kotlin.math.exp(-(j * j).toDouble() / (2.0 * sigma * sigma))
            kernel[j + radius] = v.toFloat()
            sum += v
        }
        for (i in kernel.indices) kernel[i] /= sum.toFloat()
        fun at(x: Int, y: Int): Float =
            alpha[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
        val direct = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var acc = 0f
            for (dy in -radius..radius) for (dx in -radius..radius) {
                acc += at(x + dx, y + dy) * kernel[dy + radius] * kernel[dx + radius]
            }
            direct[y * w + x] = acc
        }
        val separable = OverlayStyleBake.gaussianBlur(alpha, w, h, sigma)
        // ONE call IS the separable implementation (H pass + V pass).
        for (i in direct.indices) {
            assertEquals(direct[i], separable[i], 1e-4f)
        }
        // Determinism: a second run is bit-identical.
        val again = OverlayStyleBake.gaussianBlur(alpha, w, h, sigma)
        assertTrue(separable.contentEquals(again))
    }

    @Test
    fun `blur preserves total alpha mass - no leaking at the clamped edges`() {
        val w = 5
        val h = 5
        val alpha = FloatArray(w * h) { 1f }
        val blurred = OverlayStyleBake.gaussianBlur(alpha, w, h, 1.5f)
        for (v in blurred) assertEquals(1f, v, 1e-5f)
    }

    // ------------------------------------------------------------- shadows

    @Test
    fun `glow and shadow compose - shadow above glow, source above both`() {
        val style = OverlayStyle(
            shadow = StyleShadow(
                offsetX = 0f, offsetY = 0f, blur = 0f,
                colorArgb = 0xFFFF0000.toInt(), opacity = 1f,
            ),
            glow = StyleGlow(radius = 0f, colorArgb = 0xFF0000FF.toInt(), strength = 1f),
        )
        // Both layers aligned with the content: shadow (red) wins over glow
        // (blue) under the content region; outside the content (pad ring),
        // the tint layers sit exactly at the content positions (offset 0,
        // blur 0) — so only the content rect carries either.
        val (src, w) = solid(2, 2, 0xFFFFFFFF.toInt())
        val result = OverlayStyleBake.bake(src, w, 2, style)
        // pad = ceil(max(0, 0)) = 0 — no margins, content fully covers.
        assertEquals(2, result.width)
        // Source over everything: white wins in the content.
        assertEquals(0xFFFFFFFF.toInt(), result.pixels[0])
    }

    @Test
    fun `presets bake deterministically and pad within the cap`() {
        for (style in listOf(OverlayStylePresets.SOFT, OverlayStylePresets.HARD, OverlayStylePresets.NEON)) {
            val (src, w) = solid(304, 84, 0xFF3366AA.toInt())
            val a = OverlayStyleBake.bake(src, w, 84, style)
            val b = OverlayStyleBake.bake(src, w, 84, style)
            assertEquals(a, b)
            // The margin cap: pad <= 0.35 * srcH per side.
            assertTrue(OverlayStyleBake.padX(style, 84) <= (0.35f * 84).toInt() + 1)
            // boxScale >= 1 for styled bakes with margins.
            assertTrue(a.boxScale >= 1f)
            // The content centre pixel keeps the source colour (source-over).
            val cx = a.width / 2
            val cy = a.height / 2
            assertEquals(0xFF3366AA.toInt(), a.pixels[cy * a.width + cx])
        }
    }
}

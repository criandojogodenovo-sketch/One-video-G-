package com.onevideog.editor.ui.editor

import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v0.5.3 — the Compose overlay layer's canonical TEXT layout must be the
 * EXACT block the export renders ([OverlayBitmapRenderer.renderTextBlock]):
 * same metrics source ([OverlayBitmapRenderer.textMetrics]), same wrap
 * ([OverlayBitmapRenderer.wrapTextLines]), same block size arithmetic
 * ([OverlayBitmapRenderer.blockSizePx]). The measurer is a fake monospace
 * (10 px per char, spaces included) so every expected number is exact —
 * the TextSegmentPlanner v0.5.0 test pattern.
 */
class OverlayTextRenderTest {

    /** 10 px per character — monospace, kerning-free. */
    private val measure = { text: String -> text.length * 10f }

    private fun textOverlay(
        text: String,
        sizeFrac: Float = 0.1f,
        bgArgb: Int = 0x00000000,
        bgPaddingFrac: Float = 0.012f,
        bgRadiusFrac: Float = 0.008f,
    ) = OverlayItem(
        id = "ov-test",
        kind = OverlayKind.TEXT,
        text = text,
        sizeFrac = sizeFrac,
        bgArgb = bgArgb,
        bgPaddingFrac = bgPaddingFrac,
        bgRadiusFrac = bgRadiusFrac,
    )

    // ---- single line: exact canonical block --------------------------------

    @Test
    fun `single line block matches the export bitmap size`() {
        val layout = OverlayTextRender.layout(textOverlay("Sol"), measure)
        // sizeFrac 0.1 -> fontPx = 1080 * 0.1 = 108; no background ->
        // padX = padY = 2. "Sol" measures 30 px.
        assertEquals(108f, layout.fontPx, 1e-4f)
        assertEquals(listOf("Sol"), layout.lines)
        // Block: width 30 + 2*2 = 34; height 108 + 2*2 = 112.
        assertEquals(34, layout.blockW)
        assertEquals(112, layout.blockH)
        // The placement aspect the export scales the bitmap with.
        assertEquals(34f / 112f, layout.aspect, 1e-6f)
    }

    // ---- multi-line wrap: same lines, same block height ---------------------

    @Test
    fun `multi-line wrap reproduces the export line assignment and block height`() {
        // 250 five-char words at 10 px/CHAR: word+space = 60 px, so
        // wrapMaxWidth (1728 * 0.98 = 1693.44 px) fits 28 words per line
        // (28*6-1 = 167 chars = 1670 px; 29 words = 1730 px > wrap) ->
        // 8 lines of 28 + 1 line of 26.
        val text = List(250) { "wwwww" }.joinToString(" ")
        val layout = OverlayTextRender.layout(textOverlay(text), measure)
        val line28 = List(28) { "wwwww" }.joinToString(" ")
        val line26 = List(26) { "wwwww" }.joinToString(" ")
        assertEquals(List(8) { line28 } + line26, layout.lines)
        // Block height: 9 lines * 108 + 8 * (108 * 0.22) + 2 * 2.
        assertEquals(1166, layout.blockH)
        // Widest line 1670 + 2*2 = 1674.
        assertEquals(1674, layout.blockW)
        assertEquals(250 * 6 - 1, layout.charCount)
    }

    // ---- background pill: pad + radius fractions ----------------------------

    @Test
    fun `background pill pads with bg fractions and padX is 0_9 of padY`() {
        val overlay = textOverlay(
            "Sol",
            bgArgb = 0xCC101010.toInt(),
            bgPaddingFrac = 0.02f,
            bgRadiusFrac = 0.01f,
        )
        val layout = OverlayTextRender.layout(overlay, measure)
        // bgPad = 1080 * 0.02 = 21.6 -> padY = 21.6, padX = 19.44.
        assertEquals(21.6f, layout.padYPx, 1e-4f)
        assertEquals(19.44f, layout.padXPx, 1e-4f)
        // radius = 1080 * 0.01 = 10.8 canonical px.
        assertEquals(10.8f, layout.radiusPx, 1e-4f)
        // Block: width 30 + 2*19.44 = 68; height 108 + 2*21.6 = 151.
        assertEquals(68, layout.blockW)
        assertEquals(151, layout.blockH)
    }

    // ---- no background: 2 px inner pad, zero radius -------------------------

    @Test
    fun `no background keeps the 2 px inner pad`() {
        val layout = OverlayTextRender.layout(textOverlay("Sol"), measure)
        assertEquals(2f, layout.padXPx, 1e-4f)
        assertEquals(2f, layout.padYPx, 1e-4f)
        // The radius is computed from bgRadiusFrac UNCONDITIONALLY (exactly
        // like renderTextBlock's bgRadiusPx parameter) — it is only USED
        // when the background is opaque. Default bgRadiusFrac = 0.008 ->
        // 1080 * 0.008 = 8.64.
        assertEquals(8.64f, layout.radiusPx, 1e-4f)
    }

    // ---- sizeFrac clamp (same contract as textMetrics/renderTextBlock) ------

    @Test
    fun `sizeFrac is clamped to 0_02 and 0_3 of the canonical height`() {
        val big = OverlayTextRender.layout(textOverlay("Sol", sizeFrac = 0.5f), measure)
        assertEquals(1080f * 0.3f, big.fontPx, 1e-4f)
        val small = OverlayTextRender.layout(textOverlay("Sol", sizeFrac = 0.001f), measure)
        assertEquals(1080f * 0.02f, small.fontPx, 1e-4f)
    }

    // ---- empty text: one empty line, minimum block --------------------------

    @Test
    fun `empty text renders a single empty line with the minimum block`() {
        val layout = OverlayTextRender.layout(textOverlay(""), measure)
        assertEquals(listOf(""), layout.lines)
        // Minimum block: width coerceAtLeast(4); height 108 + 4 = 112.
        assertEquals(4, layout.blockW)
        assertEquals(112, layout.blockH)
    }
}

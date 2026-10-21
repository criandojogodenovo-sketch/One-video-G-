package com.onevideog.editor.editor

import com.onevideog.editor.data.TextAnimUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.5.0 (Fase G2) — pure text segmentation: the segments assembled back
 * must reproduce the single block bitmap layout. The measurer is a fake
 * monospace (10 px per char, spaces included) so every expected number is
 * exact; production injects Paint.measureText with the SAME metrics
 * ([OverlayBitmapRenderer.textMetrics]) and the SAME wrap
 * ([OverlayBitmapRenderer.wrapTextLines]) the block renderer uses.
 */
class TextSegmentPlannerTest {

    /** 10 px per character — monospace, kerning-free. */
    private val measurer = TextSegmentPlanner.Measurer { it.length * 10f }

    private val metrics = OverlayBitmapRenderer.TextMetrics(
        fontPx = 100f,
        padX = 2f,
        padY = 2f,
        lineSpacing = 22f,
        wrapMaxWidth = 800f,
        hasBg = false,
    )

    private fun plan(text: String, unit: TextAnimUnit = TextAnimUnit.WORD) =
        TextSegmentPlanner.plan(text, unit, metrics, measurer)

    // ---- single word: centred, full width ----------------------------------

    @Test
    fun `single word is centred and spans the block`() {
        val segments = plan("Sol")
        assertEquals(1, segments.size)
        val s = segments[0]
        // Block: width 30 + 2*2 = 34, height 100 + 2*2 = 104.
        assertEquals(0.5f, s.centerXFrac, 1e-4f)
        assertEquals(0.5f, s.centerYFrac, 1e-4f)
        assertEquals(1f, s.widthFrac, 1e-4f)
    }

    // ---- two words: exact positions + gap invariant -------------------------

    @Test
    fun `two words land at their line positions with an exact space gap`() {
        val segments = plan("Sol mar")
        assertEquals(2, segments.size)
        val (sol, mar) = segments
        // Block width: 70 + 4 = 74. Line starts at (74-70)/2 = 2.
        // Sol centre: 2 + 15 = 17; mar: 2 + 40 + 15 = 57.
        assertEquals(17f / 74f, sol.centerXFrac, 1e-4f)
        assertEquals(57f / 74f, mar.centerXFrac, 1e-4f)
        // Segment bitmap widths: 30+4=34 and 30+4=34.
        assertEquals(34f / 74f, sol.widthFrac, 1e-4f)
        // Assembly invariant: right edge of Sol + space width == left edge
        // of mar (spaces are gaps carried by prefix measures, not segments).
        val solRight = sol.centerXFrac * 74f + 15f
        val marLeft = mar.centerXFrac * 74f - 15f
        assertEquals(10f, marLeft - solRight, 1e-3f) // measure(" ") = 10
    }

    @Test
    fun `assembly of segment left and right edges matches the line bounds`() {
        val text = "um dois tres"
        val segments = plan(text)
        val blockW = TextSegmentPlanner.blockFrame(text, metrics, measurer).widthPx
        val lineW = measurer.width(text)
        val lineStart = blockW / 2f - lineW / 2f
        val firstLeft = segments.first().centerXFrac * blockW -
            measurer.width(segments.first().text) / 2f
        val lastRight = segments.last().centerXFrac * blockW +
            measurer.width(segments.last().text) / 2f
        assertEquals(lineStart, firstLeft, 1e-3f)
        assertEquals(lineStart + lineW, lastRight, 1e-3f)
    }

    // ---- multi-line ----------------------------------------------------------

    @Test
    fun `multi-line text stacks segments with the block line step`() {
        val segments = plan("Aa\nBb")
        assertEquals(2, segments.size)
        // Block: lines ["Aa","Bb"], width 20+4=24, height 2*100+22+4=226.
        val blockH = 226f
        // Line 0 centre: segH/2 = 52; line 1: 122 + 52 = 174.
        assertEquals(52f / blockH, segments[0].centerYFrac, 1e-4f)
        assertEquals(174f / blockH, segments[1].centerYFrac, 1e-4f)
        // The vertical step between line centres is fontPx + lineSpacing.
        val step = (segments[1].centerYFrac - segments[0].centerYFrac) * blockH
        assertEquals(122f, step, 1e-3f)
    }

    @Test
    fun `word wrap produces the same lines the block renderer wraps`() {
        // 80 chars fit per line at wrapMaxWidth 800 (10 px/char): 30 words
        // (~119 chars) wrap into 2+ lines — every segment sits on one of
        // exactly that many vertical positions.
        val text = List(30) { "w${it}x" }.joinToString(" ")
        val lines = OverlayBitmapRenderer.wrapTextLines(
            text, metrics.wrapMaxWidth, measurer::width,
        )
        val segments = plan(text)
        assertTrue("expected wrapped lines", lines.size >= 2)
        assertEquals(lines.size, segments.map { it.centerYFrac }.distinct().size)
    }

    // ---- LETTER unit: code points, accents, emoji ---------------------------

    @Test
    fun `letter unit splits by code point - surrogate pairs stay one segment`() {
        val segments = plan("a🎉b", TextAnimUnit.LETTER)
        assertEquals(3, segments.size)
        assertEquals("a", segments[0].text)
        assertEquals("\uD83C\uDF89", segments[1].text) // 🎉 one code point
        assertEquals("b", segments[2].text)
    }

    @Test
    fun `letter unit keeps combining accents attached to their base`() {
        val segments = plan("e\u0301a", TextAnimUnit.LETTER) // é (e + U+0301) + a
        assertEquals(2, segments.size)
        assertEquals("e\u0301", segments[0].text)
        assertEquals("a", segments[1].text)
    }

    @Test
    fun `precomposed accented words stay one word segment`() {
        val segments = plan("café")
        assertEquals(1, segments.size)
        assertEquals("café", segments[0].text)
    }

    @Test
    fun `letter segments of one line assemble without gaps or overlaps`() {
        val text = "ola"
        val segments = plan(text, TextAnimUnit.LETTER)
        assertEquals(3, segments.size)
        val blockW = TextSegmentPlanner.blockFrame(text, metrics, measurer).widthPx
        var expectedLeft = blockW / 2f - measurer.width(text) / 2f
        for (s in segments) {
            val w = measurer.width(s.text)
            assertEquals(expectedLeft, s.centerXFrac * blockW - w / 2f, 1e-3f)
            expectedLeft += w
        }
    }

    // ---- edge cases -----------------------------------------------------------

    @Test
    fun `blank text produces no segments`() {
        assertTrue(plan("   ").isEmpty())
        assertTrue(plan("").isEmpty())
    }

    @Test
    fun `multi-space runs collapse into word boundaries without segments`() {
        val segments = plan("a  b") // double space = one split, two segments
        assertEquals(2, segments.size)
        assertEquals("a", segments[0].text)
        assertEquals("b", segments[1].text)
    }
}

package com.onevideog.editor.editor

import com.onevideog.editor.data.TextAnimUnit

/**
 * Pure text segmentation planner (v0.5.0, Fase G2).
 *
 * Splits a text overlay's text into WORD or LETTER segments and computes,
 * in the CANONICAL BLOCK FRAME (the exact bitmap [OverlayBitmapRenderer]
 * renders for the whole text), where each segment's own bitmap must be
 * centred so that the assembled segments are pixel-aligned with the
 * non-animated block:
 *
 *  - line wrapping uses the SAME [OverlayBitmapRenderer.wrapTextLines] with
 *    the SAME measurer the block render uses (Paint.measureText in
 *    production — injected here so the planner is JVM-testable);
 *  - block size uses the SAME [OverlayBitmapRenderer.blockSizePx]
 *    arithmetic (int rounding included);
 *  - horizontal positions come from prefix measures of the LINE string
 *    (`measure(line.take(i))`), which reproduces Paint's per-glyph advance
 *    exactly (kerning included) because the block renders each line with
 *    `Paint.Align.CENTER` at the bitmap centre;
 *  - vertical positions replicate the block's baseline stack
 *    (`padY + BASELINE_FACTOR*fontPx + i*(fontPx + lineSpacing)`).
 *
 * Space characters are not segments: their advance is part of the line
 * prefix measures, so gaps stay exact.
 */
object TextSegmentPlanner {

    /**
     * Measures a string's advance width in canonical px. Production
     * implementation: `paint.measureText` with the overlay's typeface/size
     * (the same paint [OverlayBitmapRenderer.renderTextBlock] uses).
     */
    fun interface Measurer {
        fun width(text: String): Float
    }

    /**
     * One segment: its text plus where its bitmap sits inside the block
     * bitmap frame (all fractions relative to the BLOCK bitmap size).
     *
     * The segment's own bitmap is the block renderer applied to just this
     * text (same style, same metrics) — its cache key is
     * [OverlayBitmapRenderer.contentKey] of the derived segment
     * [com.onevideog.editor.data.OverlayItem], which hashes text + style.
     */
    data class SegmentGeometry(
        val text: String,
        /** Segment bitmap centre, 0..1 across the block bitmap width. */
        val centerXFrac: Float,
        /** Segment bitmap centre, 0..1 down the block bitmap height. */
        val centerYFrac: Float,
        /** Segment bitmap width / block bitmap width (drives its scale). */
        val widthFrac: Float,
    )

    /** Block frame the fractions refer to (canonical px). */
    data class BlockFrame(val widthPx: Int, val heightPx: Int)

    /**
     * Plans segments for [text] with [metrics]. Returns an empty list when
     * the text is blank (caller keeps the un-expanded overlay).
     */
    fun plan(
        text: String,
        unit: TextAnimUnit,
        metrics: OverlayBitmapRenderer.TextMetrics,
        measurer: Measurer,
    ): List<SegmentGeometry> {
        if (text.isBlank()) return emptyList()
        val measure = measurer::width
        val lines = OverlayBitmapRenderer.wrapTextLines(
            text, metrics.wrapMaxWidth, measure,
        )
        val (blockW, blockH) = OverlayBitmapRenderer.blockSizePx(lines, metrics, measure)
        if (blockW <= 0 || blockH <= 0) return emptyList()

        val segments = mutableListOf<SegmentGeometry>()
        lines.forEachIndexed { lineIndex, line ->
            val lineWidth = measure(line)
            // The block centres each line at bitmap centre (Align.CENTER).
            val lineStartX = blockW / 2f - lineWidth / 2f
            // Segment bitmap height for a single line of this style.
            val segH = (metrics.fontPx + metrics.padY * 2).toInt().coerceAtLeast(4)
            // Vertical: replicate the block's baseline stack. A single-line
            // segment bitmap centres its glyphs when its centre sits at
            // i*(fontPx+lineSpacing) + segH/2 (derived from
            // baseline_i - BASELINE_FACTOR*fontPx + segH/2).
            val centerY = lineIndex * (metrics.fontPx + metrics.lineSpacing) +
                segH / 2f

            for (token in tokens(line, unit)) {
                // Prefix measures reproduce Paint advances + kerning exactly.
                val startPx = measure(line.substring(0, token.first))
                val endPx = measure(line.substring(0, token.last + 1))
                val unitWidth = endPx - startPx
                val segW = (unitWidth + metrics.padX * 2).toInt().coerceAtLeast(4)
                val centerX = lineStartX + startPx + unitWidth / 2f
                segments += SegmentGeometry(
                    text = line.substring(token.first, token.last + 1),
                    centerXFrac = centerX / blockW,
                    centerYFrac = centerY / blockH,
                    widthFrac = segW.toFloat() / blockW,
                )
            }
        }
        return segments
    }

    /**
     * Block frame (canonical px) for a text overlay — the planner's
     * normalisation target, identical to the rendered block bitmap size.
     */
    fun blockFrame(
        text: String,
        metrics: OverlayBitmapRenderer.TextMetrics,
        measurer: Measurer,
    ): BlockFrame {
        val measure = measurer::width
        val lines = OverlayBitmapRenderer.wrapTextLines(
            text, metrics.wrapMaxWidth, measure,
        )
        val (w, h) = OverlayBitmapRenderer.blockSizePx(lines, metrics, measure)
        return BlockFrame(w, h)
    }

    /** Segment count for [text] (used by the UI limit warning, G5/G6). */
    fun segmentCount(
        text: String,
        unit: TextAnimUnit,
        metrics: OverlayBitmapRenderer.TextMetrics,
        measurer: Measurer,
    ): Int = plan(text, unit, metrics, measurer).size

    /**
     * Character ranges (start..endInclusive) of the units inside [line].
     * WORD: maximal non-space runs. LETTER: single code POINTS (surrogate
     * pairs / emoji stay one segment); combining marks attach to the
     * previous segment so accented clusters never split.
     */
    internal fun tokens(line: String, unit: TextAnimUnit): List<IntRange> {
        if (line.isEmpty()) return emptyList()
        return when (unit) {
            TextAnimUnit.WORD -> {
                val ranges = mutableListOf<IntRange>()
                var start = -1
                for (i in line.indices) {
                    val isSpace = line[i] == ' '
                    if (!isSpace && start == -1) start = i
                    if (isSpace && start != -1) {
                        ranges += start until i
                        start = -1
                    }
                }
                if (start != -1) ranges += start..line.lastIndex
                ranges
            }
            TextAnimUnit.LETTER -> {
                val ranges = mutableListOf<IntRange>()
                var i = 0
                while (i < line.length) {
                    var end = i
                    // One code point (handles surrogate-pair emoji).
                    val next = line.offsetByCodePoints(i, 1)
                    end = next - 1
                    // Combining marks glue to the previous segment.
                    while (end + 1 < line.length &&
                        Character.getType(
                            Character.codePointAt(line, end + 1),
                        ) == Character.NON_SPACING_MARK.toInt()
                    ) {
                        end = line.offsetByCodePoints(end + 1, 1) - 1
                    }
                    ranges += i..end
                    i = end + 1
                }
                ranges
            }
        }
    }
}

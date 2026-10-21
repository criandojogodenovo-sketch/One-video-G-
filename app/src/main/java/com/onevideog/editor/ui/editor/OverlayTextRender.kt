package com.onevideog.editor.ui.editor

import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.editor.OverlayBitmapRenderer

/**
 * v0.5.3 — pure canonical TEXT layout for the Compose preview overlay layer.
 *
 * The device bug report that produced the Compose-layer rewrite showed
 * active overlays with ZERO draw evidence: the previous preview rendered
 * overlay pixels from bitmaps (Paint-rendered text, cache-only image peeks)
 * inside a draw-phase Canvas, which failed silently on the affected device.
 * The layer now renders text with Compose `Text` primitives — but the BLOCK
 * GEOMETRY must stay identical to the export, which still composites the
 * [OverlayBitmapRenderer.renderTextBlock] bitmap.
 *
 * Parity contract (docs/PREVIEW_EXPORT_PARITY.md item 13): this object
 * computes the SAME canonical block the export bitmap uses, from the SAME
 * shared helpers the block renderer uses:
 *  - metrics: [OverlayBitmapRenderer.textMetrics] (fontPx = 1080 × sizeFrac,
 *    wrap at 1728 × 0.98, pad, line spacing 0.22 × fontPx);
 *  - line wrapping: [OverlayBitmapRenderer.wrapTextLines] (greedy word wrap);
 *  - block size: [OverlayBitmapRenderer.blockSizePx] (int rounding included).
 *
 * The measurer is injected: production passes `Paint.measureText` with the
 * overlay's typeface/size (the same paint the block renderer uses), tests
 * pass a fake monospace — the TextSegmentPlanner v0.5.0 pattern. Pure, no
 * Android rendering calls — JVM-testable.
 */
internal object OverlayTextRender {

    /**
     * Canonical block layout of a text overlay — everything the Compose
     * layer needs to render the block with `Text` primitives at exactly the
     * size the export bitmap occupies on the content area.
     */
    data class Layout(
        /** Pre-wrapped lines (the export bitmap draws exactly these). */
        val lines: List<String>,
        /** Font size in canonical px (1080-frame). */
        val fontPx: Float,
        /** Extra space between lines, canonical px (0.22 × fontPx). */
        val lineSpacingPx: Float,
        /** Horizontal inner padding, canonical px (bg pad × 0.9, or 2). */
        val padXPx: Float,
        /** Vertical inner padding, canonical px (bg pad, or 2). */
        val padYPx: Float,
        /** Background corner radius, canonical px (0 when no background). */
        val radiusPx: Float,
        val colorArgb: Int,
        val bgArgb: Int,
        /** Block width, canonical px — == the export bitmap width. */
        val blockW: Int,
        /** Block height, canonical px — == the export bitmap height. */
        val blockH: Int,
        /** Character count of the source text (OVERLAY_DRAW evidence). */
        val charCount: Int,
    ) {
        /** Block aspect (w/h) — the placement aspect the export renders. */
        val aspect: Float get() = if (blockH <= 0) 1f else blockW.toFloat() / blockH
    }

    /**
     * Computes the canonical layout for [overlay] with [measure] as the
     * text-width measurer (production: Paint.measureText with the overlay's
     * typeface and [OverlayBitmapRenderer.textMetrics] font size).
     */
    fun layout(overlay: OverlayItem, measure: (String) -> Float): Layout {
        val metrics = OverlayBitmapRenderer.textMetrics(overlay)
        val lines = OverlayBitmapRenderer.wrapTextLines(
            overlay.text, metrics.wrapMaxWidth, measure,
        )
        val (blockW, blockH) = OverlayBitmapRenderer.blockSizePx(
            lines, metrics, measure,
        )
        return Layout(
            lines = lines,
            fontPx = metrics.fontPx,
            lineSpacingPx = metrics.lineSpacing,
            padXPx = metrics.padX,
            padYPx = metrics.padY,
            radiusPx = OverlayBitmapRenderer.CANONICAL_H *
                overlay.bgRadiusFrac.coerceIn(0f, 0.2f),
            colorArgb = overlay.colorArgb,
            bgArgb = overlay.bgArgb,
            blockW = blockW,
            blockH = blockH,
            charCount = overlay.text.length,
        )
    }
}

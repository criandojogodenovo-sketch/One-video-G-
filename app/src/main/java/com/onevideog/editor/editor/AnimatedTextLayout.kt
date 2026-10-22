package com.onevideog.editor.editor

import kotlin.math.roundToInt

/**
 * v0.5.8 — PURE per-frame layout of the animated-text export composite
 * ([AnimatedTextOverlay]). Extracted verbatim from the composite's
 * `getBitmap` loop so the geometry is ONE testable function shared by the
 * export pipeline and pinned against the preview placement math
 * ([ExpandedOverlays.composeStatic] + [KeyframeEvaluator], the formulas
 * `computePlacements` applies) by AnimatedTextOverlayParityTest.
 *
 * Coordinate systems (see OverlayLayout docs):
 *  - BLOCK px: the canonical block bitmap frame ([TextSegmentPlanner
 *    .blockFrame]) the composite canvas is sized to. Segment centers/widths
 *    come from [TextSegmentPlanner.plan] fractions of this frame.
 *  - CONTENT fractions: x is a width fraction of the content area, y a
 *    HEIGHT fraction. The block's height as a content-height fraction is
 *    [blockHeightFrac] (`parentScale × contentAspect / blockAspectW`), so a
 *    content-height y delta converts to block px as `relY / blockHeightFrac
 *    × blockHeightPx` and a content-width x delta as `relX / parentScale ×
 *    blockWidthPx`.
 *
 * The RELATIVE entrance values ([TextAnimationPlanner] semantics — scale /
 * opacity factors, y delta) are evaluated per frame with the SAME
 * [KeyframeEvaluator] the preview uses; this function only converts them
 * into block pixels. Pure — JVM-testable, no Android classes.
 */
object AnimatedTextLayout {

    /** Floor for the parent scale / derived fractions (avoids divide-by-zero). */
    const val MIN_PARENT_SCALE = 1e-3f

    /** One segment's composite state for ONE frame, in BLOCK px. */
    data class SegmentFrame(
        /** Segment bitmap centre, block px from the block frame's left. */
        val centerXpx: Float,
        /** Segment bitmap centre, block px from the block frame's top. */
        val centerYpx: Float,
        /** Drawn segment bitmap width, block px (includes rel scale). */
        val widthPx: Float,
        /** Drawn segment bitmap height, block px (bitmap aspect preserved). */
        val heightPx: Float,
        /** Composited alpha 0..255 (rel opacity × 255). */
        val alphaInt: Int,
    )

    /**
     * The composite block's height as a content-HEIGHT fraction: the block
     * width fraction is [parentScale]; height = width × (blockW/blockH) /
     * contentAspect.
     */
    fun blockHeightFrac(parentScale: Float, contentAspect: Float, blockAspectW: Float): Float =
        (parentScale * contentAspect / blockAspectW).coerceAtLeast(MIN_PARENT_SCALE)

    /**
     * Layout of ONE segment for ONE frame. Returns null when the drawn
     * width is non-positive (the caller skips the segment — same rule the
     * composite always applied).
     *
     * @param centerXFrac / centerYFrac / widthFrac the segment's planned
     *   block-frame fractions ([TextSegmentPlanner.SegmentGeometry]).
     * @param bitmapWidth / bitmapHeight the segment bitmap's pixel size
     *   ([OverlayBitmapRenderer.textBitmap] of the segment text).
     * @param relX / relY / relScale / relOpacity the evaluated RELATIVE
     *   entrance transform at the frame's project time.
     * @param parentScale the PARENT's evaluated scale at the same time
     *   (block width fraction of the content).
     * @param blockHeightFrac [blockHeightFrac] for the same frame.
     * @param blockWidthPx / blockHeightPx the composite canvas size (the
     *   canonical block frame).
     */
    fun segmentFrame(
        centerXFrac: Float,
        centerYFrac: Float,
        widthFrac: Float,
        bitmapWidth: Int,
        bitmapHeight: Int,
        relX: Float,
        relY: Float,
        relScale: Float,
        relOpacity: Float,
        parentScale: Float,
        blockHeightFrac: Float,
        blockWidthPx: Int,
        blockHeightPx: Int,
    ): SegmentFrame? {
        val safeParentScale = parentScale.coerceAtLeast(MIN_PARENT_SCALE)
        val w = widthFrac * blockWidthPx * relScale
        if (w <= 0f) return null
        val h = if (bitmapWidth <= 0) {
            w
        } else {
            w * bitmapHeight.toFloat() / bitmapWidth
        }
        // Entrance deltas in content fractions -> block px: x over the
        // block's width fraction (parentScale), y over blockHeightFrac.
        val cx = centerXFrac * blockWidthPx +
            relX / safeParentScale * blockWidthPx
        val cy = centerYFrac * blockHeightPx +
            relY / blockHeightFrac * blockHeightPx
        val alpha = (relOpacity.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        return SegmentFrame(cx, cy, w, h, alpha)
    }
}

package com.onevideog.editor.editor

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * v0.5.9 — THE ONE animated-text layout function, referenced to a SINGLE
 * coordinate system: the PROJECT frame (`frameW × frameH`, aspect
 * [frameAspect] = frameW/frameH). v0.5.8 extracted the export composite's
 * per-frame geometry verbatim ([AnimatedTextLayout]); v0.5.9 (user
 * prescription "preview e export usarem a MESMA função de layout, com uma
 * única referência — o frame do projeto") inverts the direction: the
 * function now returns placements in FRAME fractions and BOTH pipelines
 * consume it:
 *
 *  - EXPORT: [AnimatedTextOverlay.getBitmap] converts the placement into
 *    block-local px with [blockRect] and draws the composite texture there
 *    (the block bitmap is placed on the frame by OverlaySettings);
 *  - PREVIEW: `computePlacements` (OverlayCanvas) converts the SAME
 *    placement into content px — the fitted content box IS the project
 *    frame (same aspect), so the conversion is one multiply per axis.
 *
 * NO source-video aspect appears anywhere in this object: the only aspect
 * inputs are [frameAspect] (the project/output frame) and the block/segment
 * BITMAP aspects (canonical px, identical in both pipelines because the
 * text measurer is the same — [OverlayBitmapRenderer.textMeasurer]).
 *
 * Inputs (fractions):
 *  - parent: x over frameW, y over frameH, scale = block width as a
 *    frame-width fraction (the model space documented in [OverlayLayout]);
 *  - segment plan ([TextSegmentPlanner.SegmentGeometry]): 0..1 across/down
 *    the canonical block bitmap frame;
 *  - relative entrance ([TextAnimationPlanner]): x delta over frameW, y
 *    delta over frameH, multiplicative scale factor — evaluated per frame
 *    with the SAME [KeyframeEvaluator] both pipelines use.
 *
 * Pure — JVM-testable; AnimatedTextOverlayParityTest pins preview px ==
 * export block px == this function at a grid of times.
 */
object AnimatedTextLayout {

    /** Floor for the parent scale / derived fractions (avoids divide-by-zero). */
    const val MIN_PARENT_SCALE = 1e-3f

    /**
     * One segment's placement for ONE frame, in PROJECT-FRAME fractions.
     * x over frameW; y over frameH; width over frameW; height over frameH.
     */
    data class FramePlacement(
        val centerXFrac: Float,
        val centerYFrac: Float,
        val widthFrac: Float,
        val heightFrac: Float,
    )

    /** One segment drawn inside the composite block canvas, in BLOCK px. */
    data class BlockRect(
        val centerXpx: Float,
        val centerYpx: Float,
        val widthPx: Float,
        val heightPx: Float,
    )

    /**
     * THE layout function. Returns null when the drawn width is
     * non-positive (the caller skips the segment — the rule the composite
     * always applied).
     *
     * Geometry (frame fractions):
     *  - width  = parentScale × widthFrac × relScale (over frameW);
     *  - height = width × frameAspect / segmentAspect — the ONLY aspect
     *    conversion, from the FRAME aspect and the segment BITMAP aspect
     *    (never from a source-video aspect);
     *  - x = parentX + (centerXFrac − 0.5) × parentScale + relX;
     *  - y = parentY + (centerYFrac − 0.5) × parentScale × frameAspect /
     *    blockAspectW + relY (the block's height as a frame-HEIGHT fraction
     *    is parentScale × frameAspect / blockAspectW).
     */
    fun segmentPlacement(
        parentX: Float,
        parentY: Float,
        parentScale: Float,
        centerXFrac: Float,
        centerYFrac: Float,
        widthFrac: Float,
        blockAspectW: Float,
        segmentAspect: Float,
        relX: Float,
        relY: Float,
        relScale: Float,
        frameAspect: Float,
    ): FramePlacement? {
        val scale = parentScale.coerceAtLeast(MIN_PARENT_SCALE)
        val widthFracOut = scale * widthFrac * relScale
        if (widthFracOut <= 0f) return null
        val safeBlockAspect = if (abs(blockAspectW) < 1e-6f) 1f else blockAspectW
        val safeSegmentAspect = if (abs(segmentAspect) < 1e-6f) 1f else segmentAspect
        val heightFracOut = widthFracOut * frameAspect / safeSegmentAspect
        val cxFrac = parentX + (centerXFrac - 0.5f) * scale + relX
        val cyFrac = parentY +
            (centerYFrac - 0.5f) * scale * frameAspect / safeBlockAspect + relY
        return FramePlacement(
            centerXFrac = cxFrac,
            centerYFrac = cyFrac,
            widthFrac = widthFracOut,
            heightFrac = heightFracOut,
        )
    }

    /** Entrance alpha (0..255) for a relative opacity — the bitmap paint alpha. */
    fun relAlphaInt(relOpacity: Float): Int =
        (relOpacity.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)

    /**
     * The block's height as a frame-HEIGHT fraction: the block width
     * fraction is [parentScale]; height = width × (blockW/blockH) ×
     * (frameH/frameW) = parentScale × frameAspect / blockAspectW.
     */
    fun blockHeightFrac(parentScale: Float, frameAspect: Float, blockAspectW: Float): Float =
        (parentScale * frameAspect / blockAspectW).coerceAtLeast(MIN_PARENT_SCALE)

    /**
     * EXPORT adapter: frame-fraction placement -> block-local px for the
     * composite canvas. The block bitmap's left/top edge sits at
     * `(parentX − parentScale/2, parentY − blockHeightFrac/2)` in frame
     * fractions and the block spans `parentScale` of frame width, so a
     * frame-width fraction is `blockWidthPx / parentScale` block px and a
     * frame-height fraction `blockHeightPx / blockHeightFrac` block px.
     * Exactly the inverse of the block-px -> frame-frac conversion the
     * parity test round-trips.
     */
    fun blockRect(
        placement: FramePlacement,
        parentX: Float,
        parentY: Float,
        parentScale: Float,
        blockHeightFrac: Float,
        blockWidthPx: Int,
        blockHeightPx: Int,
    ): BlockRect {
        val scale = parentScale.coerceAtLeast(MIN_PARENT_SCALE)
        val leftFrac = parentX - scale / 2f
        val topFrac = parentY - blockHeightFrac / 2f
        return BlockRect(
            centerXpx = (placement.centerXFrac - leftFrac) * blockWidthPx / scale,
            centerYpx = (placement.centerYFrac - topFrac) * blockHeightPx / blockHeightFrac,
            widthPx = placement.widthFrac * blockWidthPx / scale,
            heightPx = placement.heightFrac * blockHeightPx / blockHeightFrac,
        )
    }
}

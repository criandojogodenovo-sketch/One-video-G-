package com.onevideog.editor.editor

import com.onevideog.editor.data.OverlayTransform
import kotlin.math.abs

/**
 * Shared overlay placement math — the contract between the preview canvas and
 * the Media3 export pipeline.
 *
 * Coordinate systems:
 *  - Model space ("normalised content space"): the overlay centre is
 *    (x, y) with 0..1 across/height-down of the video CONTENT area (the
 *    fitted video rectangle, i.e. the exported frame after Presentation
 *    letterboxing is applied to the source). `scale` is the overlay width as
 *    a fraction of the content width. Rotation is clockwise-positive.
 *  - Export space: Media3 `OverlaySettings` NDC (-1..1, +y up, counter-
 *    clockwise rotation around the overlay centre).
 *
 * The conversions below are the ONLY bridge between the two; the parity unit
 * tests round-trip them so a regression breaks the build, not the user's
 * export.
 */
object OverlayLayout {

    /**
     * v0.5.4: minimum preview content space (px) that can host overlay
     * gestures safely (either axis). Below it the model is corruptible:
     * gesture deltas divide by the content size (a ~20 px drag becomes a
     * 0.38 normalized jump on a 53 px-wide stage) and the 24 dp gizmo zones
     * cover the whole box (a plain tap classifies as DELETE). Device audit:
     * docs/AUDIT_v0.5.3_problems.md §1 — the stage could collapse to
     * 53×30 px and the gesture layer kept running there.
     */
    const val MIN_USABLE_CONTENT_PX = 64f

    /**
     * True when [widthPx]×[heightPx] is a usable overlay-gesture space.
     * Pure — unit-tested; the preview gesture layer refuses to start ANY
     * interaction (move/scale/rotate/DELETE and tap classification included)
     * while this returns false, and logs OVERLAY_GESTURE_REJECTED instead.
     */
    fun isUsableContentSpace(widthPx: Float, heightPx: Float): Boolean =
        widthPx >= MIN_USABLE_CONTENT_PX && heightPx >= MIN_USABLE_CONTENT_PX

    data class FittedRect(
        val offsetX: Float,
        val offsetY: Float,
        val width: Float,
        val height: Float,
    )

    /**
     * Letterbox/pillarbox fit of a [contentAspect] (w/h) rectangle centred
     * inside a container. Used by the preview viewport so the on-screen video
     * area is exactly the area overlays are positioned against — matching the
     * exported frame.
     */
    fun fitRect(containerWidth: Float, containerHeight: Float, contentAspect: Float): FittedRect {
        if (containerWidth <= 0f || containerHeight <= 0f) {
            return FittedRect(0f, 0f, containerWidth, containerHeight)
        }
        val containerAspect = containerWidth / containerHeight
        val (w, h) = if (contentAspect >= containerAspect) {
            containerWidth to containerWidth / contentAspect
        } else {
            containerHeight * contentAspect to containerHeight
        }
        return FittedRect(
            offsetX = (containerWidth - w) / 2f,
            offsetY = (containerHeight - h) / 2f,
            width = w,
            height = h,
        )
    }

    // ------------------------------------------------------------- NDC bridge

    /** Model (nx, ny) -> export `backgroundFrameAnchor` NDC. */
    fun toBackgroundNdc(nx: Float, ny: Float): Pair<Float, Float> =
        (nx * 2f - 1f) to (1f - ny * 2f)

    /** Export `backgroundFrameAnchor` NDC -> model (nx, ny). Used by tests. */
    fun fromBackgroundNdc(ndcX: Float, ndcY: Float): Pair<Float, Float> =
        ((ndcX + 1f) / 2f) to ((1f - ndcY) / 2f)

    /**
     * v0.8.1 (BUG A fix — device report, realme RMX3624): THE OverlaySettings
     * scale the export must use so a bitmap renders EXACTLY like the preview.
     *
     * Verified against the Media3 1.4.1 sources AND its own pixel tests
     * (OverlayShaderProgramPixelTest, goldens measured: bitmap 304x84 on a
     * 1080x720 frame draws 304x84 at scale (1,1), 84x304 at rotation 90,
     * 1080x84 at scale (1080/304, 1)): the overlay is drawn at
     * `bitmap_px * (scale.x, scale.y)` — rotated cleanly by rotationDegrees
     * (OverlayMatrixProvider wraps the rotation in an overlay-aspect
     * conjugation) and centred on backgroundFrameAnchor. The overlay quad
     * does NOT span the whole frame — the model the previous
     * `overlayScaleSettings` formula assumed. That formula (sx = widthFrac,
     * sy = widthFrac*frameAspect/bitmapAspect) collapsed the drawn height to
     * bitmapH*widthFrac*frameAspect/bitmapAspect — for the reported text
     * block (1203x192 px, frame 1080x1920, scale 0.629) just 10.8 px instead
     * of 108.4 px: the device's "thin diagonal line" once rotated.
     *
     * Under the verified semantics the fix is ONE uniform scale:
     *   s = widthFrac * backgroundWidthPx / bitmapWidthPx
     * so drawn width = bitmapW*s = widthFrac*backgroundW and drawn height =
     * bitmapH*s = drawnW/bitmapAspect — the same box the preview computes
     * with [previewSize]. Rotation/position parity is unchanged
     * ([toExportRotation] / [toBackgroundNdc]). The background px is the
     * frame the OverlayEffect composites onto, delivered to each overlay by
     * `TextureOverlay.configure(videoSize)` (called by
     * OverlayShaderProgram.configure — verified in the 1.4.1 sources).
     *
     * Pure — unit-tested; the parity suite round-trips it through the
     * faithful Media3 matrix port, so preview and export cannot drift.
     */
    fun overlayExportScale(
        bitmapWidthPx: Int,
        backgroundWidthPx: Int,
        widthFrac: Float,
    ): Float {
        val bitmapW = bitmapWidthPx.coerceAtLeast(1)
        val bgW = if (backgroundWidthPx > 0) backgroundWidthPx else bitmapW
        return widthFrac * bgW / bitmapW
    }

    /** Model rotation (clockwise-positive) -> export rotation (CCW). */
    fun toExportRotation(rotationDeg: Float): Float = -rotationDeg

    /** Export rotation (CCW) -> model rotation (clockwise-positive). */
    fun fromExportRotation(rotationDeg: Float): Float = -rotationDeg

    // ----------------------------------------------------------- preview side

    /** Overlay size in content pixels for the preview canvas. */
    fun previewSize(
        contentWidth: Float,
        contentHeight: Float,
        bitmapAspect: Float,
        widthFrac: Float,
    ): Pair<Float, Float> {
        val w = widthFrac * contentWidth
        val safeAspect = if (abs(bitmapAspect) < 1e-6f) 1f else bitmapAspect
        return w to w / safeAspect
    }

    /**
     * Width fraction for a text overlay so that a bitmap rendered with the
     * text at [sizeFrac] of the canonical height appears at the same size
     * relative to the content.
     */
    fun textWidthFrac(sizeFrac: Float, bitmapAspect: Float, contentAspect: Float): Float {
        val safeAspect = if (abs(bitmapAspect) < 1e-6f) 1f else bitmapAspect
        val safeContentAspect = if (abs(contentAspect) < 1e-6f) 1f else contentAspect
        return sizeFrac * safeAspect / safeContentAspect
    }

    /** Clamps an evaluated transform into the valid model range. */
    fun clampTransform(t: OverlayTransform): OverlayTransform = t.withClampedValues()
}

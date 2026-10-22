package com.onevideog.editor.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind
import kotlin.math.roundToInt

/**
 * v0.5.7 (missão "texto animado = um único desenho por overlay"): ONE export
 * texture per animated text overlay. Until v0.5.6 the export built one
 * [KeyframedOverlay] per word/letter segment (up to [ExpandedOverlays.MAX_SEGMENTS]
 * GL textures + GL draws per overlay); this class composites ALL segments onto
 * ONE block-frame bitmap per frame, so the GL pipeline draws ONE texture while
 * the rendered result stays segment-for-segment identical:
 *
 *  - the composite bitmap lives in the SAME block frame the static text block
 *    uses ([TextSegmentPlanner.blockFrame]) — segments sit at their planned
 *    block-frame positions ([TextSegmentPlanner.SegmentGeometry] fractions);
 *  - per frame, each segment's RELATIVE entrance transform (deltas/factors
 *    from [TextAnimationPlanner]) is evaluated at the presentation time with
 *    the SAME [KeyframeEvaluator] the rest of the app uses, then applied
 *    inside the canvas (y delta + scale factor + opacity);
 *  - the OVERLAY SETTINGS carry the PARENT's evaluated transform (position,
 *    scale, rotation, opacity from its authored keyframes) — one rigid
 *    transform for the whole block. Rigid-transform parity with the
 *    per-segment model is exact: `R(p−c_block)+c_block` equals the per-segment
 *    `T(composed)∘R(θ)` placement for every segment (proof in the v0.5.7
 *    report). Known micro-delta: the entrance y delta rides the block frame,
 *    so on a ROTATED parent the slide direction rotates with the text
 *    (previously it slid in screen space) — recorded in the PARITY doc.
 *
 * Time base: identical to [KeyframedOverlay] — item-local presentation
 * microseconds, windows in item-local ms, keyframes evaluated at project time
 * (`clipStartMs + localMs`). Outside its window the overlay returns the shared
 * transparent bitmap (returning null is not safe in Media3 1.4.1).
 */
@UnstableApi
class AnimatedTextOverlay(
    private val parent: OverlayItem,
    private val segments: List<SegmentTexture>,
    private val blockWidthPx: Int,
    private val blockHeightPx: Int,
    /** Block bitmap aspect (w/h) — the composite frame's aspect. */
    private val blockAspectW: Float,
    /** Project-time window converted to this item's local ms. */
    private val windowStartLocalMs: Long,
    private val windowEndLocalMs: Long,
    /** Project timeline position of this item's start (for keyframe eval). */
    private val clipStartMs: Long,
    /** Aspect (w/h) of the frame the overlay is composited onto. */
    private val frameAspect: Float,
    /** Content aspect (w/h) — converts content-height fractions for the delta. */
    private val contentAspect: Float,
) : BitmapOverlay() {

    /** One segment: its pixels, block-frame placement and RELATIVE entrance. */
    data class SegmentTexture(
        val bitmap: Bitmap,
        val centerXFrac: Float,
        val centerYFrac: Float,
        val widthFrac: Float,
        /** Synthetic overlay whose keyframes are the RELATIVE entrance ones. */
        val relative: OverlayItem,
    )

    private val scratch: Bitmap by lazy {
        Bitmap.createBitmap(
            blockWidthPx.coerceAtLeast(1),
            blockHeightPx.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
    }
    private val canvas: Canvas by lazy { Canvas(scratch) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val localMs = presentationTimeUs / 1000
        if (localMs < windowStartLocalMs || localMs >= windowEndLocalMs) {
            return OverlayRenderer.transparentBitmap
        }
        val projectMs = clipStartMs + Math.round(localMs.toFloat())
        val parentEval = KeyframeEvaluator.evaluate(parent, projectMs)
        val parentScale = parentEval.scale.coerceAtLeast(MIN_PARENT_SCALE)
        // The block's height as a content-HEIGHT fraction: width fraction is
        // parentScale; height = width * (blockW/blockH) / contentAspect.
        val blockHeightFrac =
            (parentScale * contentAspect / blockAspectW).coerceAtLeast(MIN_PARENT_SCALE)
        canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        for (segment in segments) {
            val rel = KeyframeEvaluator.evaluate(segment.relative, projectMs)
            val w = segment.widthFrac * blockWidthPx * rel.scale
            if (w <= 0f) continue
            val h = if (segment.bitmap.width <= 0) {
                w
            } else {
                w * segment.bitmap.height.toFloat() / segment.bitmap.width
            }
            // Entrance deltas in content fractions -> block px: x over the
            // block's width fraction (parentScale), y over blockHeightFrac.
            val cx = segment.centerXFrac * blockWidthPx +
                rel.x / parentScale * blockWidthPx
            val cy = segment.centerYFrac * blockHeightPx +
                rel.y / blockHeightFrac * blockHeightPx
            paint.alpha = (rel.opacity.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            canvas.drawBitmap(
                segment.bitmap,
                null,
                RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f),
                paint,
            )
        }
        return scratch
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val localMs = presentationTimeUs / 1000
        if (localMs !in windowStartLocalMs until windowEndLocalMs) {
            // Fully transparent: alpha 0 keeps the (transparent) texture harmless.
            return hiddenSettings
        }
        // The PARENT's transform — one rigid placement for the whole block,
        // same math and clamps as KeyframedOverlay's per-overlay settings.
        val projectMs = clipStartMs + Math.round(localMs.toFloat())
        val transform = KeyframeEvaluator.evaluate(parent, projectMs)
        val (ndcX, ndcY) = OverlayLayout.toBackgroundNdc(transform.x, transform.y)
        val (sx, sy) = OverlayLayout.overlayScaleSettings(frameAspect, blockAspectW, transform.scale)
        return OverlaySettings.Builder()
            .setBackgroundFrameAnchor(
                ndcX.coerceIn(-1f, 1f),
                ndcY.coerceIn(-1f, 1f),
            )
            .setOverlayFrameAnchor(0f, 0f)
            .setScale(sx, sy)
            .setRotationDegrees(OverlayLayout.toExportRotation(transform.rotationDeg))
            .setAlphaScale(transform.opacity.coerceIn(0f, 1f))
            .build()
    }

    private val hiddenSettings: OverlaySettings by lazy {
        OverlaySettings.Builder().setAlphaScale(0f).build()
    }

    private companion object {
        const val MIN_PARENT_SCALE = 1e-3f
    }
}

/** Segments factory — keeps OverlayKind/animation plumbing out of the class. */
@UnstableApi
fun animatedTextSegments(parent: OverlayItem, spec: ExpandedOverlays.AnimSpec): List<AnimatedTextOverlay.SegmentTexture> {
    return spec.geoms.mapIndexed { index, geom ->
        val synthetic = OverlayItem(
            id = "rel",
            kind = OverlayKind.TEXT,
            transform = TextAnimationPlanner.identityRelative(),
            keyframes = spec.schedules.getOrNull(index)?.keyframes ?: emptyList(),
            interpolation = parent.interpolation,
        )
        AnimatedTextOverlay.SegmentTexture(
            // The SAME bitmap the per-segment pipeline renders (same style,
            // same metrics, same cache key family) — only its placement moved
            // from GL settings into the composite canvas.
            bitmap = OverlayBitmapRenderer.textBitmap(parent.copy(text = geom.text)),
            centerXFrac = geom.centerXFrac,
            centerYFrac = geom.centerYFrac,
            widthFrac = geom.widthFrac,
            relative = synthetic,
        )
    }
}

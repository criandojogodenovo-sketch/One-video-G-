package com.onevideog.editor.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings
import com.onevideog.editor.diag.DiagSanitizer
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind
import com.onevideog.editor.diag.DiagnosticsLogger

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
 *
 * v0.5.8: the per-frame composite geometry moved VERBATIM to the pure,
 * preview-parity-tested [AnimatedTextLayout] (one shared implementation for
 * the segment placement math), and every frame while the entrance is running
 * logs an ANIM_TEXT_FRAME diagnostics event (progress, parent/relative scale,
 * final block px per segment — the parity evidence the device report needs).
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

    /**
     * Last PROJECT time any segment's relative entrance keyframe defines —
     * the ANIM_TEXT_FRAME log is limited to the entrance (plus a short
     * grace); at rest the composite is static and per-frame logs would only
     * flood the diagnostics ring.
     */
    private val lastEntranceEndMs: Long by lazy {
        var maxEnd = Long.MIN_VALUE
        for (seg in segments) {
            for (kf in seg.relative.keyframes) {
                if (kf.timeMs > maxEnd) maxEnd = kf.timeMs
            }
        }
        if (maxEnd == Long.MIN_VALUE) clipStartMs + windowEndLocalMs else maxEnd
    }

    /** ANIM_TEXT_FRAME throttle: at most one event per [FRAME_LOG_STEP_MS]. */
    private var lastLoggedProjectMs = Long.MIN_VALUE

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
        val parentScale = parentEval.scale.coerceAtLeast(AnimatedTextLayout.MIN_PARENT_SCALE)
        val blockHeightFrac =
            AnimatedTextLayout.blockHeightFrac(parentScale, contentAspect, blockAspectW)
        // v0.5.8: ANIM_TEXT_FRAME evidence — one event per 100 ms while the
        // entrance runs (the composite is static after it, so logging the
        // whole window would only flood the diagnostics ring).
        val logFrame = projectMs <= lastEntranceEndMs + FRAME_LOG_GRACE_MS &&
            projectMs - lastLoggedProjectMs >= FRAME_LOG_STEP_MS
        if (logFrame) lastLoggedProjectMs = projectMs
        val segLog = if (logFrame) StringBuilder() else null
        canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        for (segment in segments) {
            val rel = KeyframeEvaluator.evaluate(segment.relative, projectMs)
            // v0.5.8: the SAME pure layout the parity test pins against the
            // preview placement math (AnimatedTextOverlayParityTest).
            val frame = AnimatedTextLayout.segmentFrame(
                centerXFrac = segment.centerXFrac,
                centerYFrac = segment.centerYFrac,
                widthFrac = segment.widthFrac,
                bitmapWidth = segment.bitmap.width,
                bitmapHeight = segment.bitmap.height,
                relX = rel.x,
                relY = rel.y,
                relScale = rel.scale,
                relOpacity = rel.opacity,
                parentScale = parentScale,
                blockHeightFrac = blockHeightFrac,
                blockWidthPx = blockWidthPx,
                blockHeightPx = blockHeightPx,
            ) ?: continue
            paint.alpha = frame.alphaInt
            canvas.drawBitmap(
                segment.bitmap,
                null,
                RectF(
                    frame.centerXpx - frame.widthPx / 2f,
                    frame.centerYpx - frame.heightPx / 2f,
                    frame.centerXpx + frame.widthPx / 2f,
                    frame.centerYpx + frame.heightPx / 2f,
                ),
                paint,
            )
            segLog?.let { log ->
                log.append(" op=").append("%.3f".format(rel.opacity))
                log.append(" sc=").append("%.3f".format(rel.scale))
                log.append(" wh=").append("%.1f".format(frame.widthPx))
                    .append('x').append("%.1f".format(frame.heightPx))
                log.append(" @").append("%.1f".format(frame.centerXpx))
                    .append(',').append("%.1f".format(frame.centerYpx)).append(';')
            }
        }
        if (logFrame && segLog != null) {
            DiagnosticsLogger.i(
                "export", "ANIM_TEXT_FRAME", null,
                "animated text composite frame",
                meta = listOf(
                    "ovId" to DiagSanitizer.fingerprint(parent.id),
                    "tUs" to presentationTimeUs.toString(),
                    "projectMs" to projectMs.toString(),
                    "parentScale" to "%.4f".format(parentScale),
                    "blockFracW" to "%.4f".format(parentScale),
                    "blockFracH" to "%.4f".format(blockHeightFrac),
                    "blockPx" to "${blockWidthPx}x$blockHeightPx",
                    "segments" to segLog.toString(),
                ),
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
        /** ANIM_TEXT_FRAME cadence while the entrance runs. */
        const val FRAME_LOG_STEP_MS = 100L

        /** Keep logging this long past the last entrance keyframe. */
        const val FRAME_LOG_GRACE_MS = 250L
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

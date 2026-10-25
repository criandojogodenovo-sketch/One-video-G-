package com.onevideog.editor.editor

import android.graphics.Bitmap
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.diag.DiagContext
import com.onevideog.editor.diag.DiagSanitizer
import com.onevideog.editor.diag.DiagnosticsLogger

/**
 * A Media3 overlay driven by the project model: visibility window, transform
 * and keyframe animation are evaluated per frame with the SAME pure math the
 * preview uses ([KeyframeEvaluator] + [OverlayLayout]).
 *
 * Time base: item-local presentation microseconds (Media3 resets timestamps
 * to 0 at the clip window start; SpeedChangeEffect scales them so that the
 * output timeline equals the project timeline locally — overlay windows are
 * therefore plain project-local spans, without a speed factor).
 *
 * Outside its window the overlay returns a shared transparent bitmap
 * (returning null from getBitmap is not safe in Media3 1.4.1).
 */
@UnstableApi
class KeyframedOverlay(
    private val overlay: OverlayItem,
    private val contentBitmap: Bitmap?,
    /** Project-time window converted to this item's local ms. */
    private val windowStartLocalMs: Long,
    private val windowEndLocalMs: Long,
    /** Project timeline position of this item's start (for keyframe eval). */
    private val clipStartMs: Long,
    /** Aspect (w/h) of the frame the overlay is composited onto. */
    private val frameAspect: Float,
    /**
     * v0.9.0 (L2): the project camera — the overlay rides it with
     * depth-proportional strength (pure [CameraMath], the SAME object the
     * preview's computePlacements uses; parity by construction).
     */
    private val camera: com.onevideog.editor.data.CameraState =
        com.onevideog.editor.data.CameraState(),
    /**
     * v0.9.0 (L1): box-growth factor of the baked style bitmap (>= 1; 1 when
     * unstyled) — multiply the transform scale BEFORE the v0.8.1 uniform
     * scale so the CONTENT box matches the preview exactly.
     */
    private val styleBoxScale: Float = 1f,
) : BitmapOverlay() {

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val localMs = presentationTimeUs / 1000
        return if (contentBitmap != null && localMs >= windowStartLocalMs && localMs < windowEndLocalMs) {
            contentBitmap
        } else {
            OverlayRenderer.transparentBitmap
        }
    }

    /**
     * v0.8.1 (BUG A fix): the px width of the frame this overlay composites
     * onto, delivered by the Media3 pipeline — OverlayShaderProgram.configure
     * calls [configure] with the background size before the first frame
     * (verified in the 1.4.1 sources). [overlayScaleSettings]-era code
     * guessed the geometry from aspects only and collapsed rotated text;
     * the scale is now derived from these REAL px ([OverlayLayout
     * .overlayExportScale]). Fallback when configure never fired: the
     * bitmap's own width (drawn at natural px — visible, never collapsed),
     * with a one-shot diagnostics warn.
     */
    private var backgroundWidthPx: Int = 0
    private var warnedNoBackground = false

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        backgroundWidthPx = videoSize.width
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val localMs = presentationTimeUs / 1000
        if (contentBitmap == null || localMs !in windowStartLocalMs until windowEndLocalMs) {
            // Fully transparent: alpha 0 keeps the (transparent) texture harmless.
            return hiddenSettings
        }
        val projectMs = clipStartMs + Math.round(localMs.toFloat())
        val transform = KeyframeEvaluator.evaluate(overlay, projectMs)
        // v0.9.0 (L2): the depth camera FIRST — pure shared math (the mapped
        // centre clamps to the content edge exactly like the anchor coercion
        // below, so the preview and the export agree by construction). The
        // mapped values then flow through the EXACT v0.8.1-hardened paths.
        val camPose = CameraMath.evaluate(camera, projectMs)
        val mapped = CameraMath.applyToTransform(camPose, overlay.zDepth, transform)
        val (ndcX, ndcY) = OverlayLayout.toBackgroundNdc(mapped.x, mapped.y)
        val bitmap = contentBitmap
        val s = OverlayLayout.overlayExportScale(
            bitmap?.width ?: 1,
            backgroundWidthPx,
            // v0.9.0 (L1): style box factor — the baked bitmap's margins must
            // NOT shrink the content: the box grows by styleBoxScale, the
            // content stays the unstyled fraction of the frame.
            mapped.scale * styleBoxScale,
        )
        if (backgroundWidthPx <= 0 && !warnedNoBackground) {
            warnedNoBackground = true
            DiagnosticsLogger.w(
                "export", "OVERLAY_BG_SIZE_FALLBACK", DiagContext.currentOpId(),
                "configure(videoSize) never fired — overlay scaled to its own px width",
                meta = listOf(
                    "ovId" to DiagSanitizer.fingerprint(overlay.id),
                    "frameAspect" to frameAspect.toString(),
                ),
            )
        }
        return OverlaySettings.Builder()
            .setBackgroundFrameAnchor(
                ndcX.coerceIn(-1f, 1f),
                ndcY.coerceIn(-1f, 1f),
            )
            .setOverlayFrameAnchor(0f, 0f)
            // v0.8.1: UNIFORM scale under the verified Media3 1.4.1 semantics
            // (drawn px = bitmap px * scale) — keeps the bitmap aspect at any
            // rotation, matching the preview's box exactly.
            .setScale(s, s)
            .setRotationDegrees(OverlayLayout.toExportRotation(transform.rotationDeg))
            .setAlphaScale(transform.opacity.coerceIn(0f, 1f))
            .build()
    }

    private val hiddenSettings: OverlaySettings by lazy {
        OverlaySettings.Builder().setAlphaScale(0f).build()
    }
}

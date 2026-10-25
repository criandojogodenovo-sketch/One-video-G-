package com.onevideog.editor.editor

import android.graphics.Bitmap
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings
import com.onevideog.editor.data.CameraState
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.diag.DiagContext
import com.onevideog.editor.diag.DiagSanitizer
import com.onevideog.editor.diag.DiagnosticsLogger

/**
 * A Media3 overlay driven by the project model: visibility window, transform
 * and keyframe animation are evaluated per frame with the SAME pure math the
 * preview uses ([KeyframeEvaluator] + [OverlayLayout] + v0.9.0 [CameraMath]).
 *
 * Time base: item-local presentation microseconds (Media3 resets timestamps
 * to 0 at the clip window start; SpeedChangeEffect scales them so that the
 * output timeline equals the project timeline locally — overlay windows are
 * therefore plain project-local spans, without a speed factor).
 *
 * Outside its window the overlay returns a shared transparent bitmap
 * (returning null from getBitmap is not safe in Media3 1.4.1).
 *
 * v0.9.0 (Fase L, L2): the project's depth camera is consumed through the
 * SAME [CameraMath.affineForDepth] the preview's computePlacements uses —
 * the affine rides the EXISTING hardened inputs (backgroundFrameAnchor NDC,
 * uniform scale, rotation): no new shader, no second render path. A default
 * camera or a zDepth-0 overlay produces the EXACT v0.8.1 numbers
 * (CameraMath identity — locked by CameraMathTest + the extended parity
 * suite).
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
     * v0.9.0 (L2): the project depth camera (default = identity static —
     * every v0.8.x caller and every v5 document stays byte-identical).
     */
    private val camera: CameraState = CameraState(),
    /**
     * v0.9.0 (L1): the px width the export scale formula must treat as the
     * bitmap basis — the CONTENT width for baked (styled) bitmaps (the baked
     * canvas is wider by the symmetric style pad; the CONTENT keeps the
     * authored width fraction). 0 = use the bitmap's own width (plain path).
     */
    private val exportScaleBasisPx: Int = 0,
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
        // v0.9.0 (L2): the depth-camera affine — the SAME pure math the
        // preview consumes, feeding the SAME hardened bridge below.
        val affine = CameraMath.affineForDepth(camera, projectMs, overlay.zDepth)
        val x = CameraMath.applyShift(transform.x, affine.shiftXFrac)
        val y = CameraMath.applyShift(transform.y, affine.shiftYFrac)
        val (ndcX, ndcY) = OverlayLayout.toBackgroundNdc(x, y)
        val bitmap = contentBitmap
        val s = OverlayLayout.overlayExportScale(
            if (exportScaleBasisPx > 0) exportScaleBasisPx else bitmap?.width ?: 1,
            backgroundWidthPx,
            transform.scale,
        ) * affine.scaleMul
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
            // rotation, matching the preview's box exactly. v0.9.0 (L2): the
            // camera's scale multiplier rides the SAME uniform scale.
            .setScale(s, s)
            .setRotationDegrees(OverlayLayout.toExportRotation(transform.rotationDeg))
            .setAlphaScale(transform.opacity.coerceIn(0f, 1f))
            .build()
    }

    private val hiddenSettings: OverlaySettings by lazy {
        OverlaySettings.Builder().setAlphaScale(0f).build()
    }
}

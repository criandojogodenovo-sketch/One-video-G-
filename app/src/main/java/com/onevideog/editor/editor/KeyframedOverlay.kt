package com.onevideog.editor.editor

import android.graphics.Bitmap
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings
import com.onevideog.editor.data.OverlayItem

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
) : BitmapOverlay() {

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val localMs = presentationTimeUs / 1000
        return if (contentBitmap != null && localMs >= windowStartLocalMs && localMs < windowEndLocalMs) {
            contentBitmap
        } else {
            OverlayRenderer.transparentBitmap
        }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val localMs = presentationTimeUs / 1000
        if (contentBitmap == null || localMs !in windowStartLocalMs until windowEndLocalMs) {
            // Fully transparent: alpha 0 keeps the (transparent) texture harmless.
            return hiddenSettings
        }
        val projectMs = clipStartMs + Math.round(localMs.toFloat())
        val transform = KeyframeEvaluator.evaluate(overlay, projectMs)
        val (ndcX, ndcY) = OverlayLayout.toBackgroundNdc(transform.x, transform.y)
        val bitmapAspect = OverlayBitmapRenderer.bitmapAspect(contentBitmap)
        val (sx, sy) = OverlayLayout.overlayScaleSettings(frameAspect, bitmapAspect, transform.scale)
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
}

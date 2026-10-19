package com.onevideog.editor.editor

import android.content.Context
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
import com.google.common.collect.ImmutableList
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.ProjectAspect

/**
 * THE single source of truth for per-clip video effects. Both pipelines call
 * [effectsFor]:
 *  - Preview: ExoPlayer `setVideoEffects(...)` (verified against Media3 1.4.1
 *    sources — effects register with the video sink and apply at the next
 *    input stream; the list must be set before `prepare` and re-applied after
 *    any change, with a seek/re-prepare to force a new input stream).
 *  - Export: `EditedMediaItem.Builder.setEffects(...)` (CompositionFactory).
 *
 * Because both paths receive the SAME Effect instances built from the SAME
 * project math ([ClipEffectPlan] -> [KeyframedOverlay] -> [OverlayLayout]),
 * overlay timing, transforms, opacity, rotation direction, z-order and
 * colour adjustments cannot drift between what is previewed and exported.
 *
 * Time base (verified against Media3 1.4.1): ClippingMediaPeriod rebases
 * sample timestamps to 0 at the clip window start, and SpeedChangeEffect
 * scales them so effect time == project-local time — identical in preview
 * and export. Negative pre-roll timestamps (keyframe before trimStart) are
 * handled by the overlay's window check.
 */
@UnstableApi
object ClipEffectsFactory {

    /** Preview GL output target height (keeps GPU memory bounded on 4 GB devices). */
    private const val PREVIEW_TARGET_H = 720

    /**
     * Builds the effect list for [clip] (which starts at [clipStartMs] on the
     * project timeline).
     *
     * @param forPreview when true, appends the composition-level
     * [Presentation] the export applies, so the preview frame geometry
     * (aspect + letterbox) matches the exported frame exactly.
     */
    fun effectsFor(
        context: Context,
        project: OvgProject,
        clip: com.onevideog.editor.data.Clip,
        clipStartMs: Long,
        forPreview: Boolean,
    ): List<Effect> {
        val effects = mutableListOf<Effect>()
        if (clip.speed != 1f) {
            effects.add(SpeedChangeEffect(clip.speed))
        }
        if (!clip.color.isDefault) {
            effects.add(
                ColorAdjustmentEffect(clip.color.brightness, clip.color.contrast, clip.color.saturation),
            )
        }
        val textures = overlayTextures(context, project, clip, clipStartMs)
        if (textures.isNotEmpty()) {
            effects.add(OverlayEffect(textures))
        }
        if (forPreview) {
            previewPresentation(project)?.let { effects.add(it) }
        }
        return effects
    }

    /**
     * Presentation effect matching the export composition geometry for
     * preview use. SOURCE aspect keeps the source frame (the export's
     * `Presentation.createForHeight` preserves aspect); fixed aspects get
     * `LAYOUT_SCALE_TO_FIT` at a bounded preview resolution.
     */
    fun previewPresentation(project: OvgProject): Presentation? {
        val aspect = project.aspectEnum
        if (aspect == ProjectAspect.SOURCE) return null
        val ratio = aspect.ratio() ?: return null
        val height = PREVIEW_TARGET_H
        val width = kotlin.math.round(height * ratio).toInt().coerceAtLeast(2)
        return Presentation.createForWidthAndHeight(
            width,
            height,
            Presentation.LAYOUT_SCALE_TO_FIT,
        )
    }

    /**
     * Texture overlays for [clip], FIFO order by zOrder (Media3 draws the
     * LAST overlay on top, so the list must be ascending by zOrder — see
     * [OverlayLayout] docs and OverlayEffect's contract).
     */
    fun overlayTextures(
        context: Context,
        project: OvgProject,
        clip: com.onevideog.editor.data.Clip,
        clipStartMs: Long,
    ): ImmutableList<androidx.media3.effect.TextureOverlay> {
        val windows = ClipEffectPlan.overlayWindowsForClip(project, clip, clipStartMs)
        if (windows.isEmpty()) return ImmutableList.of()
        val builder = ImmutableList.builder<androidx.media3.effect.TextureOverlay>()
        for (window in windows) {
            val bitmap = when (window.overlay.kind) {
                com.onevideog.editor.data.OverlayKind.TEXT ->
                    OverlayBitmapRenderer.textBitmap(window.overlay)
                com.onevideog.editor.data.OverlayKind.IMAGE ->
                    OverlayBitmapRenderer.imageBitmap(context, window.overlay)
            }
            builder.add(
                KeyframedOverlay(
                    overlay = window.overlay,
                    contentBitmap = bitmap,
                    windowStartLocalMs = window.windowStartLocalMs,
                    windowEndLocalMs = window.windowEndLocalMs,
                    clipStartMs = clipStartMs,
                    frameAspect = window.frameAspect,
                ),
            )
        }
        return builder.build()
    }
}

/**
 * Pure decision core (no Android classes in the signatures that tests use):
 * which overlays intersect a clip, in which item-local windows, and at which
 * frame aspect. Shared by preview and export — timeline/effect calculations
 * live ONLY here.
 */
object ClipEffectPlan {

    data class OverlayWindow(
        val overlay: OverlayItem,
        val windowStartLocalMs: Long,
        val windowEndLocalMs: Long,
        val frameAspect: Float,
    )

    /**
     * Overlays intersecting the clip's project-time span
     * [clipStartMs, clipStartMs + effectiveDuration), converted to item-local
     * windows (0-based at the clip start; equal in project-local units after
     * SpeedChangeEffect — verified contract, see class docs).
     */
    fun overlayWindowsForClip(
        project: OvgProject,
        clip: com.onevideog.editor.data.Clip,
        clipStartMs: Long,
    ): List<OverlayWindow> {
        val clipEndMs = clipStartMs + clip.effectiveDurationMs
        val frameAspect = clip.sourceAspect
            ?: project.aspectEnum.ratio()
            ?: 16f / 9f
        return project.overlays
            .filter { it.endMs > clipStartMs && it.startMs < clipEndMs && it.endMs > it.startMs }
            .sortedBy { it.zOrder }
            .map { overlay ->
                OverlayWindow(
                    overlay = overlay,
                    windowStartLocalMs = (maxOf(overlay.startMs, clipStartMs) - clipStartMs)
                        .coerceAtLeast(0),
                    windowEndLocalMs = (minOf(overlay.endMs, clipEndMs) - clipStartMs)
                        .coerceAtLeast(0),
                    frameAspect = frameAspect,
                )
            }
            .filter { it.windowEndLocalMs > it.windowStartLocalMs }
    }
}

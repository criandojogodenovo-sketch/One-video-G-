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
import com.onevideog.editor.diag.DiagContext
import com.onevideog.editor.diag.DiagSanitizer
import com.onevideog.editor.diag.DiagnosticsLogger
import com.onevideog.editor.diag.ImageImportStages
import android.os.SystemClock

/**
 * THE single source of truth for per-clip video effects. Both pipelines call
 * [effectsFor]:
 *  - Preview: ExoPlayer `setVideoEffects(...)` (verified against Media3 1.4.1
 *    sources — effects register with the video sink and apply at the next
 *    input stream; the list must be set before `prepare` and re-applied after
 *    any change, with a seek/re-prepare to force a new input stream).
 *  - Export: `EditedMediaItem.Builder.setEffects(...)` (CompositionFactory).
 *
 * v0.3.1 overlay rendering contract (device-reported fix — overlays were
 * invisible in the preview outside selection while the export showed them):
 *  - PREVIEW: the GL list is video-only (speed + colour + Presentation).
 *    Overlay PIXELS are drawn by the Compose layer (PreviewStage's
 *    [com.onevideog.editor.ui.editor.OverlayLayer]) on top of the PlayerView,
 *    ALWAYS visible for every active overlay. This makes preview overlay
 *    visibility independent of the device GL stack (the ExoPlayer GL
 *    pipeline did not composite the textures on the affected device while
 *    the Transformer export path did) and removes the double-render risk.
 *  - EXPORT: keeps the [OverlayEffect] textures ([KeyframedOverlay]) —
 *    confirmed working on device.
 *
 * Both renderers evaluate the SAME model space — normalized positions
 * (x, y) in 0..1 of the content area, scale as a width fraction of the
 * content, clockwise-positive rotation — through the shared pure math
 * ([ClipEffectPlan] -> [KeyframeEvaluator] -> [OverlayLayout]; the export
 * NDC bridge lives in [OverlayLayout]), so timing, transforms, opacity,
 * rotation direction, z-order and colour adjustments cannot drift between
 * what is previewed and exported.
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
     * (aspect + letterbox) matches the exported frame exactly, and NO
     * [OverlayEffect] is attached (preview overlay pixels are drawn by
     * the Compose layer — see the class docs).
     */
    fun effectsFor(
        context: Context,
        project: OvgProject,
        clip: com.onevideog.editor.data.Clip,
        clipStartMs: Long,
        forPreview: Boolean,
    ): List<Effect> {
        val startedAt = SystemClock.elapsedRealtime()
        val effects = mutableListOf<Effect>()
        if (clip.speed != 1f) {
            effects.add(SpeedChangeEffect(clip.speed))
        }
        if (!clip.color.isDefault) {
            effects.add(
                ColorAdjustmentEffect(clip.color.brightness, clip.color.contrast, clip.color.saturation),
            )
        }
        // v0.3.1: preview overlays are rendered by the Compose layer, so the
        // preview GL list must NOT contain the OverlayEffect (a second draw
        // would double-expose the overlays where the GL pipeline does work).
        val textures = if (forPreview) {
            ImmutableList.of()
        } else {
            overlayTextures(context, project, clip, clipStartMs)
        }
        if (textures.isNotEmpty()) {
            effects.add(OverlayEffect(textures))
        }
        if (forPreview) {
            previewPresentation(project)?.let { effects.add(it) }
        }
        // Stage J evidence: how long the effect build took and how many
        // overlay textures were attached (0 on the preview by design —
        // Compose owns preview overlay pixels; image decodes for the
        // preview happen in the Compose layer's pre-warm instead).
        DiagnosticsLogger.i(
            "preview", "EFFECT_ATTACH", DiagContext.currentOpId(),
            "effect list built for clip",
            meta = listOf(
                "effects" to effects.size.toString(),
                "textures" to textures.size.toString(),
                "elapsedMs" to (SystemClock.elapsedRealtime() - startedAt).toString(),
                "forPreview" to forPreview.toString(),
                "thread" to Thread.currentThread().name,
            ),
        )
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
        // v0.5.0 (G4): ONE expansion shared with the preview — text overlays
        // with an entrance animation become one KeyframedOverlay per segment
        // (same windows, same z-order rule, same composed keyframes).
        val expanded = ExpandedOverlays.build(project)
        val windows = ClipEffectPlan.overlayWindowsForClip(
            expanded.overlays, clip, clipStartMs,
        )
        if (windows.isEmpty()) return ImmutableList.of()
        val builder = ImmutableList.builder<androidx.media3.effect.TextureOverlay>()
        for (window in windows) {
            val bitmap = when (window.overlay.kind) {
                com.onevideog.editor.data.OverlayKind.TEXT ->
                    OverlayBitmapRenderer.textBitmap(window.overlay)
                com.onevideog.editor.data.OverlayKind.IMAGE -> {
                    val bmp = OverlayBitmapRenderer.imageBitmap(context, window.overlay)
                    if (bmp == null) {
                        // Stage L: recoverable render failure — the overlay
                        // will be invisible this session; the decode stage
                        // event already carries the exact exception/reason.
                        DiagnosticsLogger.w(
                            "preview", ImageImportStages.RENDER_FAIL, DiagContext.currentOpId(),
                            "image overlay has no bitmap (overlay hidden)",
                            meta = listOf("ovId" to DiagSanitizer.fingerprint(window.overlay.id)),
                        )
                    }
                    bmp
                }
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
     *
     * v0.3.0 rules enforced here (shared by preview and export — one
     * implementation, no drift):
     *  - hidden layers ([OverlayItem.visible] == false) render nothing;
     *  - windows are clipped to the clip span, so elements placed past the
     *    video end (or before a later clip's start) simply produce no window
     *    for this clip instead of breaking it — an out-of-range element can
     *    never block the tracks it does not intersect.
     */
    fun overlayWindowsForClip(
        project: OvgProject,
        clip: com.onevideog.editor.data.Clip,
        clipStartMs: Long,
    ): List<OverlayWindow> = overlayWindowsForClip(
        overlays = project.overlays,
        clipEndMs = clipStartMs + clip.effectiveDurationMs,
        frameAspect = clip.sourceAspect
            ?: project.aspectEnum.ratio()
            ?: 16f / 9f,
        clipStartMs = clipStartMs,
    )

    /**
     * Overlays variant (v0.5.0): the caller may pass an already-expanded
     * list ([ExpandedOverlays.build]) — the pure window rule is identical
     * for plain overlays and segments. Segments keep the parent's window
     * and z-order; the stable sort preserves the segment reading order
     * inside a z-order (Media3 draws the LAST texture on top).
     */
    fun overlayWindowsForClip(
        overlays: List<OverlayItem>,
        clipEndMs: Long,
        frameAspect: Float,
        clipStartMs: Long = 0,
    ): List<OverlayWindow> {
        return overlays
            .filter {
                it.visible &&
                    it.endMs > clipStartMs && it.startMs < clipEndMs && it.endMs > it.startMs
            }
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

    /**
     * Signature of everything the PREVIEW effect list for [clip] VISUALLY
     * depends on (v0.2.5, revised v0.3.1): clip identity/trim/speed/colour
     * and the project aspect.
     *
     * The preview uses this as its effects-rebuild revision key. Consequences:
     *  - a playhead move alone NEVER changes the signature (it is not an
     *    input) -> a playhead update can never rebuild the effects chain;
     *  - v0.3.1: OVERLAYS ARE NOT AN INPUT ANYMORE. The preview GL list no
     *    longer contains the OverlayEffect (overlay pixels are drawn by the
     *    Compose layer, which recomposes from project state directly), so an
     *    overlay edit must NOT re-prepare the decoder — the previous rule
     *    ("editing an intersecting overlay changes the signature") existed
     *    only because Media3 needed a new input stream to apply overlay
     *    texture changes, and that requirement is gone. Every overlay edit
     *    used to pay one decoder init + frame flash; now it pays a redraw.
     *  - clip identity/trim/speed/colour/aspect changes DO change it.
     * Pure — unit-tested.
     */
    fun effectiveSignature(
        project: OvgProject,
        clip: com.onevideog.editor.data.Clip?,
        clipStartMs: Long,
    ): Int {
        clip ?: return 0
        var h = clip.id.hashCode()
        h = 31 * h + clip.trimStartMs.hashCode()
        h = 31 * h + clip.trimEndMs.hashCode()
        h = 31 * h + clip.speed.hashCode()
        h = 31 * h + clip.color.hashCode()
        h = 31 * h + project.aspect.hashCode()
        return h
    }
}

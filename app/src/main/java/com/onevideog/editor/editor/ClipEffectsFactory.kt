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
import java.util.Locale

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

    /**
     * v0.6.0 (I7): maximum concurrent 2D effects per clip. Enforced in the
     * ViewModel (adding a 4th is ignored) and trimmed here at build time;
     * the UI warns. Effects run full-frame GL shaders — more than 3 would
     * meaningfully degrade low-end playback.
     */
    const val MAX_EFFECTS_PER_CLIP = 3

    /**
     * v0.7.0 (J4): THE canonical per-clip pipeline order — a documented
     * contract (PREVIEW_EXPORT_PARITY) locked by ClipEffectsFactoryTest.
     * [effectsFor] builds exactly this order: constant speed, then the v0.9.0
     * 2.5D camera, then basic colour, then the v0.7.0 tonal CURVES, then the
     * v0.6.0 2D effects, then the transition windows, then (export only) the
     * overlay textures. The camera sits right after speed: it reframes the
     * footage, and every later effect (colour/curves/blur/vignette/transitions)
     * then applies to the camera-framed frame — IDENTICALLY in both pipelines
     * (the preview list is the same list).
     */
    val PIPELINE_ORDER = listOf(
        "speed", "camera", "color", "curves", "effects2d", "transitions", "overlays",
    )

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
        /**
         * v0.5.9 (PONTO E): the OUTPUT frame px (composition Presentation
         * result) for the ANIM_TEXT_FRAME boxPx evidence. Only the export
         * path uses it; preview callers keep the 0 defaults.
         */
        outputWidthPx: Int = 0,
        outputHeightPx: Int = 0,
    ): List<Effect> = effectsForSegment(
        context = context,
        project = project,
        clip = clip,
        clipStartMs = clipStartMs,
        forPreview = forPreview,
        speedOverride = null,
        segmentOutputStartMs = 0L,
        segmentOutputEndMs = SpeedRampPlanner.outputDurationMs(clip),
        outputWidthPx = outputWidthPx,
        outputHeightPx = outputHeightPx,
    )

    /**
     * v0.7.0 (K3): the effect list for ONE EXPORT SEGMENT of a ramped clip.
     * [speedOverride] replaces [Clip.speed] as the SpeedChangeEffect factor
     * (the segment's constant speed); the transition windows and the overlay
     * windows — which live on the clip's OUTPUT timeline — are rebased into
     * the segment timebase [segmentOutputStartMs, segmentOutputEndMs).
     * With speedOverride = null and the full output span this is EXACTLY the
     * whole-clip list ([effectsFor] delegates here — one implementation).
     */
    fun effectsForSegment(
        context: Context,
        project: OvgProject,
        clip: com.onevideog.editor.data.Clip,
        clipStartMs: Long,
        forPreview: Boolean,
        speedOverride: Float?,
        segmentOutputStartMs: Long,
        segmentOutputEndMs: Long,
        outputWidthPx: Int = 0,
        outputHeightPx: Int = 0,
    ): List<Effect> {
        val startedAt = SystemClock.elapsedRealtime()
        val effects = mutableListOf<Effect>()
        // 1) speed (PIPELINE_ORDER[0]) — the segment's constant speed when
        // the export splits a ramped clip (speedOverride), else clip.speed.
        val speed = speedOverride ?: clip.speed
        if (speed != 1f) {
            effects.add(SpeedChangeEffect(speed))
        }
        // 2) v0.9.0 (Fase L2, PIPELINE_ORDER[1]): the project 2.5D camera on
        // the VIDEO plane — a Media3 1.4.1 MatrixTransformation (stock shader,
        // frame size kept, out-of-NDC pixels clipped = camera crop; spike
        // L2b gate passed, see VideoPlaneCameraParityTest). The SAME instance
        // family runs in the preview (setVideoEffects) and the export
        // (EditedMediaItem effects) — parity by construction. Identity
        // cameras add NOTHING (zero cost, pre-v0.9.0 behaviour exactly).
        if (!CameraMath.isIdentity(project.camera)) {
            effects.add(
                CameraTransformEffect(
                    camera = project.camera,
                    surfaceStartProjectMs = clipStartMs + segmentOutputStartMs,
                ),
            )
        }
        // 3) basic colour (PIPELINE_ORDER[2]).
        if (!clip.color.isDefault) {
            effects.add(
                ColorAdjustmentEffect(clip.color.brightness, clip.color.contrast, clip.color.saturation),
            )
        }
        // 4) v0.7.0 (Fase J, J4): advanced tonal curves AFTER basic colour and
        // BEFORE the 2D effects — the user's ordering contract. Same call for
        // preview and export: the shader and the four LUT textures are
        // identical in both paths (preview/export parity by construction).
        // curves == null / all-identity builds NOTHING (no GPU cost).
        effects.addAll(ColorCurvesEffects.effectsFor(clip.curves))
        // 5) v0.6.0 (Fase I, I5): per-clip 2D effects AFTER speed/colour/curves
        // and BEFORE overlays — the user's ordering contract for the pipeline.
        // Same call for preview and export: the shaders are identical in
        // both paths (preview/export parity by construction).
        effects.addAll(ClipGlEffects.effectsFor(clip.effects))
        // 6) v0.6.0 (Fase H): transition windows on THIS clip — the out window
        // on the tail (previous clip) and the in window on the head (next
        // clip). Same list, same shaders, both pipelines (Media3 1.4.1 has
        // no Transition API — see TransitionEffects docs). v0.7.0: windows
        // are rebased into the segment timebase for ramped clips (K3).
        effects.addAll(
            TransitionEffects.effectsForClipSegment(
                project, clip, segmentOutputStartMs, segmentOutputEndMs,
            ),
        )
        // 7) v0.3.1: preview overlays are rendered by the Compose layer, so the
        // preview GL list must NOT contain the OverlayEffect (a second draw
        // would double-expose the overlays where the GL pipeline does work).
        val textures = if (forPreview) {
            ImmutableList.of()
        } else {
            // v0.7.0 (K3): the clip's overlay windows live on the clip's
            // OUTPUT timeline; intersect them with the segment span and shift
            // them into the segment timebase (empty intersection -> no
            // texture instance for this segment — no duplicated GPU memory).
            val segmentWindows = ClipEffectPlan.overlayWindowsForClip(
                project = project,
                clip = clip,
                clipStartMs = clipStartMs,
            ).mapNotNull { window ->
                val s = maxOf(window.windowStartLocalMs, segmentOutputStartMs)
                val e = minOf(window.windowEndLocalMs, segmentOutputEndMs)
                if (e <= s) {
                    null
                } else {
                    window.copy(
                        windowStartLocalMs = s - segmentOutputStartMs,
                        windowEndLocalMs = e - segmentOutputStartMs,
                    )
                }
            }
            if (segmentWindows.isEmpty()) {
                ImmutableList.of()
            } else {
                texturesForOverlays(
                    context,
                    segmentWindows,
                    clipStartMs + segmentOutputStartMs,
                    ExpandedOverlays.projectContentAspect(project),
                    outputWidthPx,
                    outputHeightPx,
                    project.camera,
                )
            }
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
                "segmentMs" to "$segmentOutputStartMs..$segmentOutputEndMs",
                "thread" to Thread.currentThread().name,
            ),
        )
        return effects
    }

    /**
     * v0.9.1 (BUG A — graceful degradation, device-specific): the
     * PREVIEW-ONLY effect list for the case where this device's GL pipeline
     * deterministically fails to PROCESS the effects chain
     * (ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED persisting after the
     * bounded recovery and the manual reload). Timing and frame geometry
     * are preserved — SpeedChangeEffect keeps the pace the playhead math
     * maps against, Presentation keeps the export aspect/letterbox — while
     * every heavy/optional GL effect is dropped (camera, colour, curves,
     * blur/vignette/sharpen, transitions). The EXPORT list is untouched:
     * nothing calls this on the export path, so the export stays correct
     * (the honest preview banner states exactly that — see PARITY.md).
     */
    fun degradedPreviewEffects(
        project: OvgProject,
        clip: com.onevideog.editor.data.Clip,
    ): List<Effect> {
        val startedAt = SystemClock.elapsedRealtime()
        val effects = mutableListOf<Effect>()
        if (clip.speed != 1f) {
            effects.add(SpeedChangeEffect(clip.speed))
        }
        previewPresentation(project)?.let { effects.add(it) }
        DiagnosticsLogger.w(
            "preview", "EFFECT_ATTACH", DiagContext.currentOpId(),
            "degraded preview effect list built (GL effects suspended on this device)",
            meta = listOf(
                "effects" to effects.size.toString(),
                "degraded" to "true",
                "elapsedMs" to (SystemClock.elapsedRealtime() - startedAt).toString(),
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
     * v0.5.1: window -> textures, the ONE builder for every export surface
     * (per-clip video items — whole clips AND ramped segments — AND the
     * empty-project black base item). Same bitmaps, same z-order, same
     * keyframe math — no per-surface duplication. [surfaceStartProjectMs] is
     * the project time of the item's local t=0 (the video clip's timeline
     * start — or the segment's —, or 0 for the black base). [contentAspect]
     * drives the animated-text composite (v0.5.7).
     */
    fun texturesForOverlays(
        context: Context,
        windows: List<ClipEffectPlan.OverlayWindow>,
        surfaceStartProjectMs: Long,
        contentAspect: Float,
        /** v0.5.9: output frame px for the composite's boxPx log (0 = n/a). */
        outputWidthPx: Int = 0,
        outputHeightPx: Int = 0,
        /** v0.9.0 (L2): the project camera — the overlays ride it per depth. */
        camera: com.onevideog.editor.data.CameraState = com.onevideog.editor.data.CameraState(),
    ): ImmutableList<androidx.media3.effect.TextureOverlay> {
        if (windows.isEmpty()) return ImmutableList.of()
        val builder = ImmutableList.builder<androidx.media3.effect.TextureOverlay>()
        for (window in windows) {
            val overlay = window.overlay
            val animSpec = if (overlay.kind == com.onevideog.editor.data.OverlayKind.TEXT) {
                ExpandedOverlays.animationSpec(overlay, contentAspect)
            } else {
                null
            }
            if (animSpec != null) {
                // v0.5.8: one ANIM_TEXT_BUILD event per composite — the
                // planning evidence the frame-level ANIM_TEXT_FRAME logs
                // (AnimatedTextOverlay.getBitmap) correlate against.
                // v0.5.9: framePx names the output basis; Locale.US decimals.
                DiagnosticsLogger.i(
                    "export", "ANIM_TEXT_BUILD", DiagContext.currentOpId(),
                    "animated text composite texture built",
                    meta = listOf(
                        "ovId" to DiagSanitizer.fingerprint(overlay.id),
                        "unit" to animSpec.unit.name,
                        "segments" to animSpec.geoms.size.toString(),
                        "blockPx" to "${animSpec.frame.widthPx}x${animSpec.frame.heightPx}",
                        "blockAspectW" to "%.4f".format(Locale.US, animSpec.blockAspectW),
                        "frameAspect" to "%.4f".format(Locale.US, window.frameAspect),
                        "contentAspect" to "%.4f".format(Locale.US, contentAspect),
                        "framePx" to if (outputWidthPx > 0 && outputHeightPx > 0) {
                            "${outputWidthPx}x$outputHeightPx"
                        } else {
                            "n/a"
                        },
                        "surfaceStartMs" to surfaceStartProjectMs.toString(),
                        "windowLocalMs" to
                            "${window.windowStartLocalMs}..${window.windowEndLocalMs}",
                    ),
                )
                builder.add(
                    AnimatedTextOverlay(
                        parent = overlay,
                        segments = animatedTextSegments(overlay, animSpec),
                        blockWidthPx = animSpec.frame.widthPx,
                        blockHeightPx = animSpec.frame.heightPx,
                        blockAspectW = animSpec.blockAspectW,
                        windowStartLocalMs = window.windowStartLocalMs,
                        windowEndLocalMs = window.windowEndLocalMs,
                        clipStartMs = surfaceStartProjectMs,
                        frameAspect = window.frameAspect,
                        contentAspect = contentAspect,
                        outputWidthPx = outputWidthPx,
                        outputHeightPx = outputHeightPx,
                        camera = camera,
                    ),
                )
                continue
            }
            val bitmap = when (overlay.kind) {
                com.onevideog.editor.data.OverlayKind.TEXT ->
                    OverlayBitmapRenderer.textBitmap(overlay)
                com.onevideog.editor.data.OverlayKind.IMAGE -> {
                    val bmp = OverlayBitmapRenderer.imageBitmap(context, overlay)
                    if (bmp == null) {
                        // Stage L: recoverable render failure — the overlay
                        // will be invisible this session; the decode stage
                        // event already carries the exact exception/reason.
                        DiagnosticsLogger.w(
                            "preview", ImageImportStages.RENDER_FAIL, DiagContext.currentOpId(),
                            "image overlay has no bitmap (overlay hidden)",
                            meta = listOf("ovId" to DiagSanitizer.fingerprint(overlay.id)),
                        )
                    }
                    bmp
                }
            }
            // v0.9.0 (L1, decisão D1): styled overlays render from the ONE
            // baked bitmap BOTH pipelines consume (same cache instance as the
            // preview). boxScale travels with the bitmap so the placement
            // math keeps the CONTENT box identical to the unstyled case.
            val styled = OverlayBitmapRenderer.styledBitmap(context, overlay)
            if (styled != null) {
                builder.add(
                    KeyframedOverlay(
                        overlay = overlay,
                        contentBitmap = styled.bitmap,
                        windowStartLocalMs = window.windowStartLocalMs,
                        windowEndLocalMs = window.windowEndLocalMs,
                        clipStartMs = surfaceStartProjectMs,
                        frameAspect = window.frameAspect,
                        camera = camera,
                        styleBoxScale = styled.boxScale,
                    ),
                )
                continue
            }
            builder.add(
                KeyframedOverlay(
                    overlay = overlay,
                    contentBitmap = bitmap,
                    windowStartLocalMs = window.windowStartLocalMs,
                    windowEndLocalMs = window.windowEndLocalMs,
                    clipStartMs = surfaceStartProjectMs,
                    frameAspect = window.frameAspect,
                    camera = camera,
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
        // v0.7.0: the clip's project span uses the RAMP-AWARE footprint
        // (SpeedRampPlanner.outputDurationMs) — overlay windows live on the
        // project timeline, which is what speed ramps change. Clips without
        // ramps get exactly the pre-v0.7.0 value.
        clipEndMs = clipStartMs + SpeedRampPlanner.outputDurationMs(clip),
        // v0.5.8: frameAspect is the aspect of the frame the overlays are
        // composited onto — the OUTPUT frame. For a fixed-aspect project that
        // IS the project ratio (CompositionFactory builds the composition
        // Presentation at ExportConfig.outputSize, LAYOUT_SCALE_TO_FIT), and
        // the preview positions overlays against the same ratio
        // (PreviewStage.contentAspect, ExpandedOverlays.projectContentAspect
        // — project ratio first). The previous source-first order fed the
        // overlay y-scale the SOURCE aspect instead, so every overlay was
        // drawn vertically squashed/stretched by sourceAspect/projectRatio
        // whenever the two differed (device symptom: exported animated text
        // "small with wide letter gaps" — the glyph height shrank while the
        // planned horizontal positions stayed). SOURCE projects keep the
        // clip's aspect (ratio() is null there — unchanged behaviour).
        frameAspect = project.aspectEnum.ratio()
            ?: clip.sourceAspect
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
            // v0.9.0 (D2): depth defines the draw order when it differs —
            // deeper planes are drawn FIRST (Media3 draws the LAST texture on
            // top); the existing zOrder is the tie-break and stays the only
            // thing bring-to-front/send-to-back manipulate. Segments inherit
            // the parent's zDepth, so the stable sort keeps the segment
            // reading order inside a depth/z-order pair.
            .sortedWith(compareBy({ it.zDepth }, { it.zOrder }))
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
     * depends on (v0.2.5, revised v0.3.1, revised v0.6.0, revised v0.7.0):
     * clip identity/trim/speed/colour, the project aspect, the clip's 2D
     * effects (I5), the project transitions (H) and — v0.7.0 — the clip's
     * tonal curves (J4) and speed ramps (K): they change the GL effect list
     * (curves are GL inputs; ramps change the segment plan and the speed
     * mapping), so they MUST re-prepare the decoder, same class as effects.
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
     *  - v0.6.0: clip.effects or project.transitions changes DO change it
     *    (the GL list itself changes; this is the same class of edit as
     *    colour — one decoder re-prepare per effects edit is accepted and
     *    documented; transition/effect edits are rare, explicit user actions).
     *  - v0.7.0: clip.curves / clip.speedRamps changes DO change it (same
     *    class as effects — locked by ClipEffectsFactoryTest).
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
        h = 31 * h + clip.curves.hashCode()
        h = 31 * h + clip.speedRamps.hashCode()
        h = 31 * h + clip.effects.hashCode()
        h = 31 * h + project.transitions.hashCode()
        h = 31 * h + project.aspect.hashCode()
        // v0.9.0 (Fase L2): the camera IS a GL input (the MatrixTransformation
        // lives in the per-clip list) — same rebuild class as effects/curves.
        // Overlays (zDepth/styles) stay OUTSIDE (v0.3.1 contract untouched):
        // they render via the Compose layer in the preview.
        h = 31 * h + project.camera.hashCode()
        return h
    }
}

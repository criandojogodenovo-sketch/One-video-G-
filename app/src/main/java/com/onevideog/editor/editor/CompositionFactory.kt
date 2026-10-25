package com.onevideog.editor.editor

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.google.common.collect.ImmutableList
import com.onevideog.editor.data.AudioClipItem
import com.onevideog.editor.data.Clip
import com.onevideog.editor.data.ExportResolutionEnum
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.ProjectAspect
import com.onevideog.editor.diag.DiagSanitizer
import com.onevideog.editor.diag.DiagnosticsLogger
import java.io.File

/**
 * Builds the authoritative export [Composition] from the project model.
 *
 * The same project math used by the preview ([ProjectMath],
 * [KeyframeEvaluator], [OverlayLayout], [AudioTimeline]) drives every part of
 * the composition, so the preview is by construction the export plan.
 *
 * Structure:
 *  - sequence 0: the video clips (trim, speed, volume, colour, overlays)
 *  - one parallel sequence per independent audio clip (silence padding +
 *    trimmed, volume-processed audio; looping clips repeat to fill)
 *  - composition effect: Presentation (resolution + aspect ratio)
 *
 * Overlays are per-clip Media3 1.4.1 does not support composition-level
 * OverlayEffect (Composition.Builder.setEffects documents "only works with
 * the Presentation effect"), so each clip carries the overlays intersecting
 * it, converted to item-local time (item timelines are 0-based after
 * clipping; with SpeedChangeEffect the output time equals project-local
 * time, see [KeyframedOverlay]).
 */
@UnstableApi
object CompositionFactory {

    data class Plan(
        val composition: Composition,
        val plannedDurationMs: Long,
        /** Transient files (silence padding) to delete after the export. */
        val tempFiles: List<File>,
    )

    /**
     * One planned video item of the sequence (v0.7.0, K3): a WHOLE clip when
     * it has no speed ramps (ramped = false — the v0.6.0 one-item-per-clip
     * path, unchanged), or ONE constant-speed SEGMENT of a ramped clip
     * (ramped = true — each segment becomes its own EditedMediaItem with
     * SpeedChangeEffect([speed]) and the clipping window trimmed to the
     * segment's source range). Pure — JVM-tested by CompositionFactoryTest.
     */
    data class PlannedItem(
        val clipId: String,
        /** Project-timeline start of the CLIP this item belongs to. */
        val clipStartMs: Long,
        val segmentIndex: Int,
        val segmentCount: Int,
        /** In-file source window of this item (ClippingConfiguration). */
        val sourceStartMs: Long,
        val sourceEndMs: Long,
        /** The SpeedChangeEffect factor of this item. */
        val speed: Float,
        /** Project-timeline start / length of THIS item's output. */
        val outputStartMs: Long,
        val outputDurationMs: Long,
        /** True when the item is one segment of a ramped clip. */
        val ramped: Boolean,
    )

    /**
     * THE single video-sequence plan (v0.7.0): one entry per export item, in
     * sequence order, with ramp-aware output positions. [build] consumes
     * this plan — preview ([ProjectMath]/[TimelineLogic]) reads the same
     * numbers from the same planner, so the timeline is the export plan.
     */
    fun planVideoSequence(project: OvgProject): List<PlannedItem> {
        val items = mutableListOf<PlannedItem>()
        var clipStart = 0L
        for (clip in project.clips) {
            val segments = SpeedRampPlanner.planSegments(clip)
            // v0.6.0 path preserved: a clip WITHOUT a real ramp (single
            // segment at clip.speed) exports as ONE item exactly as before.
            val ramped = segments.size > 1 || segments[0].speed != clip.speed
            var segOut = 0L
            for ((i, seg) in segments.withIndex()) {
                items += PlannedItem(
                    clipId = clip.id,
                    clipStartMs = clipStart,
                    segmentIndex = i,
                    segmentCount = segments.size,
                    sourceStartMs = seg.sourceStartMs,
                    sourceEndMs = seg.sourceEndMs,
                    speed = seg.speed,
                    outputStartMs = clipStart + segOut,
                    outputDurationMs = seg.outputDurationMs,
                    ramped = ramped,
                )
                segOut += seg.outputDurationMs
            }
            clipStart += SpeedRampPlanner.outputDurationMs(clip)
        }
        return items
    }

    fun build(
        context: Context,
        project: OvgProject,
        resolution: ExportResolutionEnum,
        workDir: File,
    ): Plan {
        val videoDurationMs = ProjectMath.videoDurationMs(project.clips)
        // Planned duration = what the OUTPUT FILE will actually contain.
        // v0.3.0: projectDurationMs IS that number by construction — with
        // video present it equals the video sequence length (audio sequences
        // are clamped via [buildAudioSequence]/[AudioTimeline.exportPlan] and
        // overlay windows only render inside clips); audio/overlay-only
        // projects keep the element-driven duration. Using ONE function here
        // and in ExportManager.verifyPlayable removes the v0.2.5 split where
        // the export plan said 54.9 s while the verify/report path said 65 s
        // (the v0.3.0 device-reported temporal bug).
        val plannedDurationMs = ProjectMath.projectDurationMs(project)

        // ---- video sequence (v0.7.0: from THE plan — planVideoSequence) ----
        // v0.5.9 (PONTO E): the OUTPUT frame px the composition Presentation
        // produces — the exact basis for the ANIM_TEXT_* boxPx evidence.
        // Fixed aspects: the real even-rounded ExportConfig size; SOURCE:
        // targetHeight x aspect (Presentation.createForHeight keeps aspect).
        val aspect0 = project.aspectEnum
        val fixedOutput = if (aspect0 == ProjectAspect.SOURCE) {
            null
        } else {
            ExportConfig.outputSize(resolution, aspect0)
        }
        val clipsById = project.clips.associateBy { it.id }
        val videoItems = ImmutableList.builder<EditedMediaItem>()
        for (item in planVideoSequence(project)) {
            val clip = clipsById[item.clipId] ?: continue
            if (!item.ramped) {
                // v0.6.0 whole-clip path (unchanged).
                videoItems.add(
                    buildClipItem(context, project, clip, item.clipStartMs, fixedOutput, resolution.targetHeight),
                )
            } else {
                // v0.7.0 (K3): one constant-speed item per ramp segment.
                videoItems.add(
                    buildSegmentItem(context, project, clip, item, fixedOutput, resolution.targetHeight),
                )
            }
        }
        // K7 evidence: a ramp that produced the maximum segment count was
        // truncated (its tail is absorbed into the last segment).
        for (clip in project.clips) {
            if (SpeedRampPlanner.wasTruncated(clip)) {
                DiagnosticsLogger.w(
                    "export", "SPEED_RAMP_LIMIT", null,
                    "speed ramp exceeded the segment limit — truncated to MAX_SEGMENTS",
                    meta = listOf(
                        "clip" to DiagSanitizer.fingerprint(clip.id),
                        "max" to SpeedRampPlanner.MAX_SEGMENTS.toString(),
                    ),
                )
            }
        }

        // v0.5.1 (empty project): with NO base video the composition still
        // needs a video track — an empty sequence makes the Transformer
        // fail/produce nothing. A generated BLACK frame image item spans
        // the whole planned duration (>= 5 s by [ProjectMath]) and carries
        // the overlay textures exactly like a video clip would. The frame
        // aspect matches the preview's clip-less contentAspect (SOURCE ->
        // 16:9, fixed aspect -> the project ratio), so normalized overlay
        // positions are identical in preview and export (parity).
        if (project.clips.isEmpty() && plannedDurationMs > 0) {
            val base = buildBlackBaseItem(context, project, plannedDurationMs, workDir, fixedOutput, resolution.targetHeight)
            if (base == null) {
                // Without the base item the video sequence is EMPTY and the
                // Transformer fails with an opaque composition error — record
                // the real reason (PNG write failed) for the diagnostics.
                DiagnosticsLogger.e(
                    "export", "BLACK_BASE_MISSING", null,
                    "empty-project export: black base item could not be created",
                )
            } else {
                videoItems.add(base)
            }
        }

        val sequences = ImmutableList.builder<EditedMediaItemSequence>()
        sequences.add(EditedMediaItemSequence(videoItems.build()))

        // ---- independent audio sequences ----
        // v0.5.1: with no base video, audio is clamped to the PROJECT
        // duration (>= 5 s) instead of a zero-length video timeline, so
        // audio-only projects export exactly what the preview plays.
        val audioFillMs = if (project.clips.isEmpty()) plannedDurationMs else videoDurationMs
        val tempFiles = mutableListOf<File>()
        for (audioClip in project.audioClips) {
            val sequence = buildAudioSequence(audioClip, audioFillMs, workDir, tempFiles)
            if (sequence != null) sequences.add(sequence)
        }

        // ---- composition-level Presentation (resolution/aspect) ----
        val compositionEffects = mutableListOf<androidx.media3.common.Effect>()
        val aspect = project.aspectEnum
        if (aspect == ProjectAspect.SOURCE) {
            compositionEffects.add(Presentation.createForHeight(resolution.targetHeight))
        } else {
            val size = ExportConfig.outputSize(resolution, aspect)
            compositionEffects.add(
                Presentation.createForWidthAndHeight(
                    size.width,
                    size.height,
                    Presentation.LAYOUT_SCALE_TO_FIT,
                ),
            )
        }

        val composition = Composition.Builder(sequences.build())
            .setEffects(Effects(emptyList(), compositionEffects))
            .build()

        return Plan(composition, plannedDurationMs, tempFiles)
    }

    // ------------------------------------------------------------------ video

    /**
     * v0.5.1: the empty-project base "clip" — a solid black image spanning
     * [durationMs], carrying the overlay textures for the whole project
     * window through the SAME [ClipEffectsFactory.texturesForWindows] the
     * per-clip path uses (one overlay implementation, preview/export parity).
     * The frame aspect equals the preview's clip-less contentAspect.
     */
    private fun buildBlackBaseItem(
        context: Context,
        project: OvgProject,
        durationMs: Long,
        workDir: File,
        /** v0.5.9: fixed-aspect output size (null = SOURCE — derived below). */
        fixedOutput: ExportConfig.OutputSize?,
        targetHeight: Int,
    ): EditedMediaItem? {
        val aspect = when (val a = project.aspectEnum) {
            ProjectAspect.SOURCE -> 16f / 9f
            else -> a.ratio() ?: 16f / 9f
        }
        val blackPng = blackFrameFile(workDir, aspect) ?: return null
        // v0.5.7: windows over the PARENT overlays + one texture per animated
        // text overlay (texturesForOverlays) — same single-draw rule as the
        // per-clip path.
        val windows = ClipEffectPlan.overlayWindowsForClip(
            overlays = project.overlays,
            clipEndMs = durationMs,
            frameAspect = aspect,
            clipStartMs = 0L,
        )
        // v0.5.9 (PONTO E): the frame px basis for the composite log.
        val outW = fixedOutput?.width ?: Math.round(targetHeight * aspect)
        val outH = fixedOutput?.height ?: targetHeight
        val overlayTextures = ClipEffectsFactory.texturesForOverlays(
            context,
            windows,
            0L,
            ExpandedOverlays.projectContentAspect(project),
            outW,
            outH,
            // v0.9.0 (L2): the overlays on the black base ride the project
            // camera exactly like the per-clip path (coherent parallax in
            // video-less projects).
            project.camera,
        )
        val videoEffects = if (overlayTextures.isEmpty()) {
            emptyList<androidx.media3.common.Effect>()
        } else {
            listOf<androidx.media3.common.Effect>(
                androidx.media3.effect.OverlayEffect(overlayTextures),
            )
        }
        // Media3 1.4.1 (ImageAssetLoader constructor — verified against the
        // 1.4.1 sources):
        //   checkState(editedMediaItem.durationUs != C.TIME_UNSET);
        //   checkState(editedMediaItem.frameRate != C.RATE_UNSET_INT);
        // BOTH must be set on the image EditedMediaItem or the asset loader
        // throws IllegalStateException the moment the Transformer starts
        // (the v0.5.1 empty-project export failed exactly here — duration was
        // set, frame rate was not). setFrameRate documents 30 as the
        // recommended value for inputs without an implicit frame rate
        // (images); it is IGNORED for video inputs, so this cannot touch the
        // with-video export path — the change is scoped to the no-video base.
        // The duration is set on BOTH levels:
        //  - EditedMediaItem.setDurationUs — what ImageAssetLoader reports to
        //    the composition (the sequence item duration);
        //  - MediaItem.setImageDurationMs — the 1.4.1-correct MediaItem-level
        //    duration for image playback paths.
        val durationUs = durationMs * 1_000L
        return EditedMediaItem.Builder(
            MediaItem.Builder()
                .setUri(Uri.fromFile(blackPng))
                .setMimeType(MimeTypes.IMAGE_PNG)
                .setImageDurationMs(durationMs)
                .build(),
        )
            .setDurationUs(durationUs)
            .setFrameRate(30)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()
    }

    /**
     * Writes (or reuses) the black PNG used as the no-video export base.
     * Size: short side 720 px at [aspect] — above every export target height
     * after the composition Presentation scales it, tiny in memory. The
     * content never varies with duration, so one file per workDir.
     */
    private fun blackFrameFile(workDir: File, aspect: Float): File? = runCatching {
        val file = File(workDir, "black_base.png")
        if (!file.isFile || file.length() == 0L) {
            val shortSide = 720
            val (w, h) = if (aspect >= 1f) {
                Math.round(shortSide * aspect).coerceAtLeast(2) to shortSide
            } else {
                shortSide to Math.round(shortSide / aspect).coerceAtLeast(2)
            }
            val bitmap = android.graphics.Bitmap
                .createBitmap(w, h, android.graphics.Bitmap.Config.RGB_565)
            bitmap.eraseColor(android.graphics.Color.BLACK)
            file.outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        file
    }.getOrNull()

    private fun buildClipItem(
        context: Context,
        project: OvgProject,
        clip: Clip,
        clipStartMs: Long,
        /** v0.5.9: fixed-aspect output size (null = SOURCE — derived below). */
        fixedOutput: ExportConfig.OutputSize?,
        targetHeight: Int,
    ): EditedMediaItem {
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(clip.uri))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.trimStartMs)
                    .setEndPositionMs(clip.trimEndMs)
                    .build(),
            )
            .build()

        val audioProcessors = mutableListOf<androidx.media3.common.audio.AudioProcessor>()
        audioProcessors.add(VolumeAudioProcessor(clip.volume))
        if (clip.speed != 1f) {
            audioProcessors.add(SonicAudioProcessor().apply { setSpeed(clip.speed) })
        }

        // ONE shared effect pipeline: the same builder the preview player
        // feeds into ExoPlayer.setVideoEffects (forPreview=false — the
        // composition-level Presentation is added below at composition scope).
        // v0.5.9 (PONTO E): the frame px basis for the ANIM_TEXT_* log —
        // fixed aspect: the real even-rounded output size; SOURCE: the clip's
        // aspect at targetHeight (Presentation.createForHeight keeps aspect).
        val outW = fixedOutput?.width
            ?: Math.round(targetHeight * (clip.sourceAspect ?: 16f / 9f))
        val outH = fixedOutput?.height ?: targetHeight
        val videoEffects = ClipEffectsFactory.effectsFor(
            context = context,
            project = project,
            clip = clip,
            clipStartMs = clipStartMs,
            forPreview = false,
            outputWidthPx = outW,
            outputHeightPx = outH,
        )

        return EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(audioProcessors, videoEffects))
            .build()
    }

    /**
     * v0.7.0 (K3): ONE constant-speed segment of a ramped clip as its own
     * EditedMediaItem — clipping window = the segment's source range,
     * SpeedChangeEffect = the segment speed (video AND audio: the clip's own
     * audio follows the video speed without pitch adjustment — the K5
     * documented limitation), and the shared effect pipeline with the
     * transition/overlay windows rebased into the segment timebase (see
     * [ClipEffectsFactory.effectsForSegment]).
     */
    private fun buildSegmentItem(
        context: Context,
        project: OvgProject,
        clip: Clip,
        item: PlannedItem,
        fixedOutput: ExportConfig.OutputSize?,
        targetHeight: Int,
    ): EditedMediaItem {
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(clip.uri))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(item.sourceStartMs)
                    .setEndPositionMs(item.sourceEndMs)
                    .build(),
            )
            .build()

        val audioProcessors = mutableListOf<androidx.media3.common.audio.AudioProcessor>()
        audioProcessors.add(VolumeAudioProcessor(clip.volume))
        if (item.speed != 1f) {
            audioProcessors.add(SonicAudioProcessor().apply { setSpeed(item.speed) })
        }

        val outW = fixedOutput?.width
            ?: Math.round(targetHeight * (clip.sourceAspect ?: 16f / 9f))
        val outH = fixedOutput?.height ?: targetHeight
        val segStartLocal = item.outputStartMs - item.clipStartMs
        val videoEffects = ClipEffectsFactory.effectsForSegment(
            context = context,
            project = project,
            clip = clip,
            clipStartMs = item.clipStartMs,
            forPreview = false,
            speedOverride = item.speed,
            segmentOutputStartMs = segStartLocal,
            segmentOutputEndMs = segStartLocal + item.outputDurationMs,
            outputWidthPx = outW,
            outputHeightPx = outH,
        )

        return EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(audioProcessors, videoEffects))
            .build()
    }

    // ------------------------------------------------------------------ audio

    /**
     * Builds the audio sequence for [clip], clamped EXACTLY to
     * [videoDurationMs] ([AudioTimeline.exportPlan]) so every parallel
     * sequence in the Composition has the same duration — unequal durations
     * make the 1.4.1 composition undefined. Legacy looping music keeps the
     * sequence-level loop (fills the composition duration itself).
     *
     * v0.8.0 (Q2): every audible audio item now carries ONE
     * [GainAutomationAudioProcessor] (profile from [GainAutomationAudioProcessor.profileOf])
     * instead of the constant VolumeAudioProcessor — the SAME processor
     * class the preview sink uses, reading the SAME AudioGainMath, so the
     * exported gain (fades, keyframes, gate, volume) equals the previewed
     * gain by construction. Identity profiles configure as NOT_SET —
     * zero cost, the v0.7.0 behaviour exactly. The processor instance is
     * per EditedMediaItem (never shared); silence padding items carry no
     * processor.
     */
    private fun buildAudioSequence(
        clip: AudioClipItem,
        videoDurationMs: Long,
        workDir: File,
        tempFiles: MutableList<File>,
    ): EditedMediaItemSequence? {
        val audioItem = { startMs: Long, endMs: Long ->
            EditedMediaItem.Builder(
                MediaItem.Builder()
                    .setUri(Uri.parse(clip.uri))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(startMs.coerceAtLeast(0))
                            .setEndPositionMs(endMs.coerceAtMost(clip.sourceDurationMs))
                            .build(),
                    )
                    .build(),
            )
                .setEffects(
                    Effects(
                        listOf(
                            GainAutomationAudioProcessor(
                                GainAutomationAudioProcessor.profileOf(clip),
                                alwaysActive = false,
                            ),
                        ),
                        emptyList(),
                    ),
                )
                .build()
        }

        // Legacy music behaviour: one looping sequence starting at 0.
        if (clip.loop && clip.startMs <= 0) {
            val end = minOf(clip.trimEndMs, clip.sourceDurationMs)
            if (end - clip.trimStartMs <= 0) return null
            return EditedMediaItemSequence(listOf(audioItem(clip.trimStartMs, end)), true)
        }

        val plan = AudioTimeline.exportPlan(clip, videoDurationMs)
        if (plan.segments.isEmpty()) return null

        val items = mutableListOf<EditedMediaItem>()
        if (plan.leadSilenceMs >= 20) {
            silenceItem(workDir, plan.leadSilenceMs, tempFiles)?.let { items.add(it) }
        }
        for (segment in plan.segments) {
            items.add(audioItem(segment.sourceStartMs, segment.sourceEndMs))
        }
        if (plan.tailSilenceMs >= 20) {
            silenceItem(workDir, plan.tailSilenceMs, tempFiles)?.let { items.add(it) }
        }
        if (items.isEmpty()) return null
        return EditedMediaItemSequence(items)
    }

    private fun silenceItem(
        workDir: File,
        gapMs: Long,
        tempFiles: MutableList<File>,
    ): EditedMediaItem? {
        val silence = SilenceWav.writeOrReuse(workDir, gapMs) ?: return null
        tempFiles += silence
        return EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(silence)))
            .setRemoveVideo(true)
            .build()
    }
}

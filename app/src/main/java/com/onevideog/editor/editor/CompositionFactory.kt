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

    fun build(
        context: Context,
        project: OvgProject,
        resolution: ExportResolutionEnum,
        workDir: File,
    ): Plan {
        val videoDurationMs = ProjectMath.videoDurationMs(project.clips)
        // Planned duration = what the OUTPUT FILE will actually contain
        // (v0.2.5 rule): the video sequence length. Audio sequences are
        // clamped to the video length ([buildAudioSequence]), and overlay
        // windows only render inside clips — so using the editor timeline
        // (which overlays may extend) here made `verifyPlayable` reject a
        // VALID export whenever an overlay/audio ended later than the video
        // (device-reported "durationMs changed after importing audio").
        // Audio-only projects (no clips) keep the element-driven duration.
        val plannedDurationMs = if (project.clips.isEmpty()) {
            ProjectMath.projectDurationMs(project)
        } else {
            videoDurationMs
        }

        // ---- video sequence ----
        val videoItems = ImmutableList.builder<EditedMediaItem>()
        var clipStart = 0L
        for (clip in project.clips) {
            videoItems.add(buildClipItem(context, project, clip, clipStart))
            clipStart += clip.effectiveDurationMs
        }

        val sequences = ImmutableList.builder<EditedMediaItemSequence>()
        sequences.add(EditedMediaItemSequence(videoItems.build()))

        // ---- independent audio sequences ----
        val tempFiles = mutableListOf<File>()
        for (audioClip in project.audioClips) {
            val sequence = buildAudioSequence(audioClip, videoDurationMs, workDir, tempFiles)
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

    private fun buildClipItem(
        context: Context,
        project: OvgProject,
        clip: Clip,
        clipStartMs: Long,
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
        val videoEffects = ClipEffectsFactory.effectsFor(
            context = context,
            project = project,
            clip = clip,
            clipStartMs = clipStartMs,
            forPreview = false,
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
                        listOf(VolumeAudioProcessor(clip.volume)),
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

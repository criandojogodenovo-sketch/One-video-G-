package com.onevideog.editor.editor

import com.onevideog.editor.data.AudioClipItem
import com.onevideog.editor.data.Clip
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.OverlayItem
import kotlin.math.roundToLong

/**
 * The single authoritative source of project timing.
 *
 * Timeline UI, clip labels, the preview playhead and the export composition
 * must all read the duration from here — never from a private re-computation
 * — so that what the user sees is exactly what is exported.
 */
object ProjectMath {

    /** Duration of the concatenated video sequence (timeline ms). */
    fun videoDurationMs(clips: List<Clip>): Long = clips.sumOf { it.effectiveDurationMs }

    /** End time of the latest overlay window (0 when there are none). */
    fun overlayEndMs(overlays: List<OverlayItem>): Long =
        overlays.maxOfOrNull { it.endMs } ?: 0L

    /** End time of the latest audio clip (0 when there are none). */
    fun audioEndMs(audioClips: List<AudioClipItem>): Long =
        audioClips.maxOfOrNull { it.endMs } ?: 0L

    /**
     * Authoritative project duration — THE documented project rule (v0.2.5):
     *
     *  - When the project has video clips, the duration is the VIDEO
     *    timeline, extended to cover any overlay window that ends later.
     *    AUDIO NEVER EXTENDS THE PROJECT DURATION: the Media3 1.4.1
     *    composition is video-length (audio sequences are clamped to the
     *    video duration — [AudioTimeline.exportPlan]), so an audio clip
     *    longer than the video must not stretch the timeline, the playhead
     *    range or the export plan. This is the device-reported
     *    "durationMs changed after importing audio" bug (v0.2.4 report):
     *    with a 3-minute song on a 30-second video the editor showed a
     *    3-minute timeline while the exported file stayed 30 s — and
     *    `verifyPlayable` then rejected the VALID file against the wrong
     *    planned duration.
     *  - When the project has NO video clips (audio/overlay-only project),
     *    the duration is the longest element, so such projects stay usable.
     *
     * Preview and export both read the duration from here — what the user
     * sees is exactly what is exported.
     */
    fun projectDurationMs(project: OvgProject): Long {
        val video = videoDurationMs(project.clips)
        return if (project.clips.isEmpty()) {
            maxOf(overlayEndMs(project.overlays), audioEndMs(project.audioClips))
        } else {
            maxOf(video, overlayEndMs(project.overlays))
        }
    }

    /** Start position of clip [index] on the video timeline. */
    fun clipStartMs(clips: List<Clip>, index: Int): Long =
        TimelineLogic.clipStartMs(clips, index)

    /**
     * Maps an absolute project time inside the clip at [index] to the local
     * time of the clipped, speed-adjusted media item (0-based window).
     */
    fun projectToItemLocalMs(clip: Clip, projectLocalMs: Long): Long =
        (projectLocalMs.coerceAtLeast(0) * clip.speed).roundToLong()

    /** Inverse of [projectToItemLocalMs]. */
    fun itemLocalToProjectMs(clip: Clip, itemLocalMs: Long): Long =
        (itemLocalMs.coerceAtLeast(0) / clip.speed).roundToLong()

    /**
     * Clamps a playhead request (scrub tap/drag, transport seek) into the
     * valid project range. The single clamp every writer of the playhead
     * uses, so positions are always valid project times.
     */
    fun clampPlayheadMs(ms: Long, durationMs: Long): Long =
        ms.coerceIn(0L, durationMs.coerceAtLeast(0L))
}

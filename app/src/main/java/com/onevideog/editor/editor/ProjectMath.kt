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

    /**
     * v0.5.1 (empty project): a project WITHOUT base video is always at
     * least 5 s long, and grows as clips (text/image/audio) are added.
     * The initial 5 s gives the timeline a usable scrub range and gives
     * the black-background export a real duration from the first element.
     */
    const val MIN_EMPTY_DURATION_MS = 5_000L

    /** Duration of the concatenated video sequence (timeline ms). */
    fun videoDurationMs(clips: List<Clip>): Long = clips.sumOf { it.effectiveDurationMs }

    /** End time of the latest overlay window (0 when there are none). */
    fun overlayEndMs(overlays: List<OverlayItem>): Long =
        overlays.maxOfOrNull { it.endMs } ?: 0L

    /** End time of the latest audio clip (0 when there are none). */
    fun audioEndMs(audioClips: List<AudioClipItem>): Long =
        audioClips.maxOfOrNull { it.endMs } ?: 0L

    /**
     * Authoritative project duration — THE documented project rule (v0.3.0,
     * extended v0.5.1 for empty projects):
     *
     *  - When the project has video clips, the duration is the VIDEO
     *    timeline — period. NOTHING extends it. The Media3 1.4.1 export
     *    composition is exactly video-length (audio sequences are clamped
     *    to the video duration — [AudioTimeline.exportPlan] — and overlay
     *    windows only render inside clips), so the editor timeline, the
     *    playhead range and the reported export duration must all equal the
     *    video length or the user is shown a duration the exported file
     *    does not have (v0.3.0 device-reported temporal bug: an image bar
     *    dragged past the video end stretched the timeline while the export
     *    stayed video-length, and playback beyond the video end froze the
     *    UI). Elements whose windows fall outside the video range are NOT
     *    discarded — they persist, stay editable in the timeline and simply
     *    render nothing outside the active range (identical rule in preview
     *    and export, see [ClipEffectPlan.overlayWindowsForClip]).
     *  - When the project has NO video clips (text/image/audio-only
     *    project), the duration is at least [MIN_EMPTY_DURATION_MS] (5 s,
     *    the "empty project" starting length) and grows to the longest
     *    element — so such projects stay usable and exportable on a black
     *    background.
     *
     * Preview and export both read the duration from here — what the user
     * sees is exactly what is exported.
     */
    fun projectDurationMs(project: OvgProject): Long {
        val video = videoDurationMs(project.clips)
        return if (project.clips.isEmpty()) {
            maxOf(
                MIN_EMPTY_DURATION_MS,
                overlayEndMs(project.overlays),
                audioEndMs(project.audioClips),
            )
        } else {
            video
        }
    }

    /**
     * Widest timeline extent of ANY element (video length, overlay bars,
     * audio bars). The timeline UI renders out to this so bars placed past
     * the video end remain visible and draggable (they render nothing, but
     * the user can always move them back into the active range).
     */
    fun timelineSpanMs(project: OvgProject): Long = maxOf(
        projectDurationMs(project),
        overlayEndMs(project.overlays),
        audioEndMs(project.audioClips),
    )

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

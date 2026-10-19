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
     * Authoritative project duration: the video sequence length, extended to
     * cover any audio clip or overlay that ends later. Export and preview both
     * use this value.
     */
    fun projectDurationMs(project: OvgProject): Long = maxOf(
        videoDurationMs(project.clips),
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
}

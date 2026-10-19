package com.onevideog.editor.editor

import com.onevideog.editor.data.AudioClipItem

/**
 * Pure planning of independent audio clips onto the project timeline.
 * Used by the preview audio scheduler and the export composition builder so
 * both place audio identically.
 */
object AudioTimeline {

    /** One placed audio segment on the project timeline. */
    data class Segment(
        val sourceStartMs: Long,
        val sourceEndMs: Long,
        val timelineStartMs: Long,
    ) {
        val durationMs: Long get() = sourceEndMs - sourceStartMs
        val timelineEndMs: Long get() = timelineStartMs + durationMs
    }

    const val MAX_LOOP_SEGMENTS = 200

    /**
     * Segments for [clip]:
     *  - normal clip: the trimmed range placed at [clip.startMs].
     *  - looping clip: the trimmed range repeated back-to-back until
     *    [fillUntilMs] (project video duration), capped at [MAX_LOOP_SEGMENTS].
     */
    fun segmentsFor(clip: AudioClipItem, fillUntilMs: Long?): List<Segment> {
        val duration = clip.trimmedDurationMs
        if (duration <= 0) return emptyList()
        val first = Segment(clip.trimStartMs, clip.trimEndMs, clip.startMs)
        if (!clip.loop || fillUntilMs == null) return listOf(first)

        val segments = mutableListOf(first)
        var cursor = first.timelineEndMs
        var guard = 0
        while (cursor < fillUntilMs && segments.size < MAX_LOOP_SEGMENTS) {
            segments += Segment(clip.trimStartMs, clip.trimEndMs, cursor)
            cursor += duration
            guard++
            if (guard > MAX_LOOP_SEGMENTS) break
        }
        return segments
    }

    /**
     * Segments of [clip] that are audible at [timeMs] (used by the preview
     * scheduler to decide which players must be running).
     */
    fun activeSegmentAt(clip: AudioClipItem, timeMs: Long): Segment? =
        segmentsFor(clip, null).firstOrNull { timeMs >= it.timelineStartMs && timeMs < it.timelineEndMs }
}

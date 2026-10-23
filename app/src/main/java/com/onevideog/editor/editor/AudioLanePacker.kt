package com.onevideog.editor.editor

import com.onevideog.editor.data.AudioClipItem

/**
 * v0.8.0 (P2) — deterministic interval lane-packing for the AUDIO timeline
 * lanes. Same contract as [LanePacker] (overlays), applied to audio clips:
 *
 *  - Clips are sorted by (startMs, endMs, id) — a stable, total order, so
 *    packing never depends on list order or object identity.
 *  - A lane is reused only when the clip already placed in it ends at or
 *    before the next clip starts (end == start counts as NON-overlapping).
 *  - Any overlap allocates the next free lane below — audio clips OVERLAP
 *    AND MIX when played/exported (they are NOT hidden like overlays); the
 *    lane is DISPLAY-ONLY. It is computed at render time and NEVER stored
 *    in the model — the export plan ([AudioTimeline.exportPlan]) and the
 *    preview scheduler ([AudioPreviewController]) are lane-agnostic.
 *  - Packing never changes a clip's temporal start/end.
 *
 * Pure — JVM-tested by AudioLanePackerTest.
 */
object AudioLanePacker {

    /** One audio clip placed on one timeline lane. */
    data class Assignment(val clip: AudioClipItem, val lane: Int)

    /**
     * Packs valid intervals (trimmedDuration > 0) into lanes. Zero-length
     * clips are ignored (they render nothing and would corrupt lane reuse).
     */
    fun pack(clips: List<AudioClipItem>): List<Assignment> {
        val sorted = clips
            .filter { it.trimmedDurationMs > 0 }
            .sortedWith(compareBy({ it.startMs }, { it.endMs }, { it.id }))
        val laneEnds = mutableListOf<Long>()
        val result = mutableListOf<Assignment>()
        for (clip in sorted) {
            val lane = laneEnds.indexOfFirst { it <= clip.startMs }
            if (lane >= 0) {
                laneEnds[lane] = clip.endMs
                result += Assignment(clip, lane)
            } else {
                laneEnds += clip.endMs
                result += Assignment(clip, laneEnds.lastIndex)
            }
        }
        return result
    }

    /** Number of lanes required to display [clips] without visual overlap. */
    fun laneCount(clips: List<AudioClipItem>): Int =
        pack(clips).maxOfOrNull { it.lane + 1 } ?: 0
}

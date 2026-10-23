package com.onevideog.editor.editor

import com.onevideog.editor.data.AudioClipItem

/**
 * v0.8.0 (P4) — the pure crossfade rule between two audio clips.
 *
 * Semantics (deliberately SIMPLE — documented in PREVIEW_EXPORT_PARITY.md):
 * when two audio clips overlap on the timeline AND both edges have fades,
 * the overlap region crossfades automatically — the earlier clip's fadeOut
 * attenuates it while the later clip's fadeIn raises it. The gain math
 * ([AudioGainMath.fadeEnvelope]) is LINEAR on both sides, so the sum in the
 * overlap is NOT constant-power (a "dip" of a few dB mid-overlap is the
 * accepted, documented simplification; equal-power curves are a future
 * phase).
 *
 * The button/gesture ("crossfade") does the rest: given a selected clip it
 * picks its NEAREST neighbour (previous by timeline position, else next),
 * MOVES the later clip so the two overlap by [requestedMs], and sets
 * both fades to exactly that overlap. Both edits are plain model edits —
 * one ViewModel commit, undo-friendly.
 *
 * Pure — JVM-tested by AudioCrossfadeTest.
 */
object AudioCrossfade {

    /** Requested overlap when the user does not pick one explicitly. */
    const val DEFAULT_CROSSFADE_MS = 800L

    /** The minimum overlap a crossfade can produce (same floor as trims). */
    const val MIN_CROSSFADE_MS = 150L

    data class Plan(
        /** The earlier clip (fade-out side), with the fade set. */
        val earlier: AudioClipItem,
        /** The later clip (fade-in side), moved + fade set. */
        val later: AudioClipItem,
        /** The overlap the plan produces (ms). */
        val overlapMs: Long,
    )

    /**
     * The neighbour of [selected] on [clips]: the clip with the greatest
     * endMs <= selected.startMs (the one BEFORE), else the clip with the
     * smallest startMs >= selected.endMs (the one AFTER). Null when the
     * selected clip has no audio neighbour (nothing to crossfade with).
     */
    fun neighbour(clips: List<AudioClipItem>, selected: AudioClipItem): AudioClipItem? {
        val before = clips
            .filter { it.id != selected.id && it.endMs <= selected.startMs }
            .maxByOrNull { it.endMs }
        val after = clips
            .filter { it.id != selected.id && it.startMs >= selected.endMs }
            .minByOrNull { it.startMs }
        return before ?: after
    }

    /**
     * Plans the crossfade between [earlier] and [later] with a requested
     * overlap of [requestedMs]: the later clip is moved (when needed) so
     * its start sits [overlapMs] before the earlier clip's end; both fades
     * are set to the overlap. Clamped: the overlap never exceeds either
     * clip's duration and never falls below [MIN_CROSSFADE_MS] (unless a
     * clip is shorter than that — then the overlap is the shorter
     * duration); the later clip's start never moves below 0.
     */
    fun plan(
        earlier: AudioClipItem,
        later: AudioClipItem,
        requestedMs: Long = DEFAULT_CROSSFADE_MS,
    ): Plan {
        // The overlap never exceeds either clip's duration; the floor
        // (MIN_CROSSFADE_MS) applies only when both clips are long enough.
        val maxOverlap = minOf(earlier.trimmedDurationMs, later.trimmedDurationMs)
            .coerceAtLeast(1)
        val overlap = requestedMs
            .coerceIn(0, maxOverlap)
            .coerceAtLeast(minOf(MIN_CROSSFADE_MS, maxOverlap))
        // Move the later clip so the overlap exists (keep it when already
        // bigger — the existing overlap is the user's intent).
        val newStart = if (later.startMs + overlap <= earlier.endMs) {
            later.startMs // already overlapping by at least `overlap`
        } else {
            (earlier.endMs - overlap).coerceAtLeast(0)
        }
        val movedLater = later.copy(startMs = newStart)
        return Plan(
            earlier = earlier.copy(fadeOutMs = overlap),
            later = movedLater.copy(fadeInMs = overlap),
            overlapMs = overlap,
        )
    }
}

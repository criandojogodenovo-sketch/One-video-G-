package com.onevideog.editor.editor

import com.onevideog.editor.data.Keyframe
import com.onevideog.editor.data.TextAnimPreset
import kotlin.math.roundToLong

/**
 * Pure entrance-animation planner (v0.5.0, Fase G3).
 *
 * Given a preset + duration + the overlay's visible window, produces each
 * segment's entrance start time (staggered, fully INSIDE the window) and
 * the entrance keyframes.
 *
 * KEYFRAME VALUE SEMANTICS (important — consumed by
 * [ExpandedOverlays.combineTransform]): the produced keyframes are
 * RELATIVE, not absolute:
 *  - opacity: multiplicative factor (0 = invisible, 1 = parent opacity);
 *  - scale:   multiplicative factor (0.6 = 60% of the segment's static scale);
 *  - y:       DELTA in content-height fractions (added to the static y);
 *  - x / rotationDeg: never animated by presets (base 0 / 0).
 *
 * This lets one synthetic evaluation (base = identity: x=0, y=0, scale=1,
 * rotation=0, opacity=1) interpolate the deltas/factors with the SAME
 * [KeyframeEvaluator] the rest of the app uses — no second interpolation
 * implementation.
 *
 * Hold semantics: [KeyframeEvaluator] holds the first keyframe before it
 * and the last after it, so a segment is invisible (opacity 0) before its
 * entrance starts and keeps its final state until the overlay window ends.
 */
object TextAnimationPlanner {

    /** Duration bounds applied to the user slider value. */
    const val MIN_DURATION_MS = 100L
    const val MAX_DURATION_MS = 5000L

    /** SLIDE_UP travel distance (fraction of the content height). */
    const val SLIDE_DELTA_Y = 0.06f

    /** POP scale overshoot factors (start, peak, settle). */
    const val POP_START = 0.6f
    const val POP_PEAK = 1.05f

    /** TYPEWRITER appears in one frame (1 ms step — effectively a step). */
    const val TYPEWRITER_STEP_MS = 1L

    data class SegmentSchedule(
        val segmentIndex: Int,
        /** Absolute project time the segment's entrance starts. */
        val startMs: Long,
        /** Relative entrance keyframes (see the class docs for semantics). */
        val keyframes: List<Keyframe>,
    )

    /**
     * Plans the entrance for [segmentCount] segments inside
     * [windowStartMs, windowEndMs].
     *
     * Stagger: the free slack is (window - duration); segment i starts at
     * windowStart + i * slack/(count-1), so the LAST segment's entrance
     * always finishes at/before the window end. A single segment starts at
     * the window start.
     */
    fun plan(
        preset: TextAnimPreset,
        durationMs: Long,
        windowStartMs: Long,
        windowEndMs: Long,
        segmentCount: Int,
    ): List<SegmentSchedule> {
        if (preset == TextAnimPreset.NONE || segmentCount <= 0) return emptyList()
        val windowLen = (windowEndMs - windowStartMs).coerceAtLeast(0)
        val duration = durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
            .coerceAtMost(windowLen.coerceAtLeast(MIN_DURATION_MS))
        val slack = (windowLen - duration).coerceAtLeast(0)
        val step = if (segmentCount > 1) slack.toFloat() / (segmentCount - 1) else 0f
        return (0 until segmentCount).map { index ->
            val start = windowStartMs + (index * step).roundToLong()
            SegmentSchedule(
                segmentIndex = index,
                startMs = start,
                keyframes = entranceKeyframes(preset, start, duration),
            )
        }
    }

    /** Relative keyframes for one segment's entrance. */
    fun entranceKeyframes(
        preset: TextAnimPreset,
        startMs: Long,
        durationMs: Long,
    ): List<Keyframe> = when (preset) {
        TextAnimPreset.NONE -> emptyList()
        TextAnimPreset.FADE -> listOf(
            Keyframe(timeMs = startMs, opacity = 0f),
            Keyframe(timeMs = startMs + durationMs, opacity = 1f),
        )
        TextAnimPreset.SLIDE_UP -> listOf(
            // Starts slightly BELOW its slot (content y grows downward),
            // faded out; slides up into place while fading in.
            Keyframe(timeMs = startMs, y = SLIDE_DELTA_Y, opacity = 0f),
            Keyframe(timeMs = startMs + durationMs, y = 0f, opacity = 1f),
        )
        TextAnimPreset.POP -> listOf(
            Keyframe(timeMs = startMs, scale = POP_START, opacity = 0f),
            Keyframe(
                timeMs = startMs + durationMs / 2,
                scale = POP_PEAK,
                opacity = 1f,
            ),
            Keyframe(timeMs = startMs + durationMs, scale = 1f, opacity = 1f),
        )
        TextAnimPreset.TYPEWRITER -> listOf(
            Keyframe(timeMs = startMs, opacity = 0f),
            Keyframe(timeMs = startMs + TYPEWRITER_STEP_MS, opacity = 1f),
        )
    }

    /**
     * The identity "relative transform": evaluating a synthetic overlay
     * whose base is this transform and whose keyframes come from
     * [entranceKeyframes] returns pure deltas/factors at any time.
     */
    fun identityRelative() =
        com.onevideog.editor.data.OverlayTransform(
            x = 0f,
            y = 0f,
            scale = 1f,
            rotationDeg = 0f,
            opacity = 1f,
        )
}

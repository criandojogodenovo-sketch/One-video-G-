package com.onevideog.editor.editor

import com.onevideog.editor.data.Clip
import com.onevideog.editor.data.SpeedPoint
import kotlin.math.roundToLong

/**
 * v0.7.0 (Fase K) — THE pure speed-ramp planner. One implementation for the
 * timeline UI, the preview playback speed and the export composition.
 *
 * Time bases (documented contract, locked by SpeedRampPlannerTest):
 *  - The ramp is defined on the CONSTANT-SPEED basis: localTimeMs lives in
 *    [0, Clip.effectiveDurationMs] — the clip duration AS IF no ramp existed
 *    ((trimEnd - trimStart) / clip.speed). Clip.effectiveDurationMs is
 *    UNCHANGED by ramps (byte-compatible with every pre-v0.7.0 document).
 *  - Between two ramp points the factor interpolates LINEARLY; before the
 *    first / after the last point the factor clamps to that point's factor.
 *  - The ACTUAL clip footprint on the project timeline is
 *    [outputDurationMs] — the sum over segments of sourceSpan / factor. The
 *    v0.3.0 contract (projectDurationMs == video sequence length) is
 *    preserved BY CONSTRUCTION: ProjectMath.videoDurationMs,
 *    TimelineLogic.clipStartMs/clipIndexAt, ClipEffectPlan.overlayWindowsForClip
 *    and TransitionPlanner all read the ramp-aware duration from here.
 *  - [projectToItemLocalMs] / [itemLocalToProjectMs] are the ramp-aware
 *    replacements of the ProjectMath mappings (ProjectMath delegates here);
 *    for a clip WITHOUT valid ramps they return EXACTLY the pre-v0.7.0
 *    values (projectLocal * speed / sourceLocal / speed).
 *
 * Export (K3): a ramped clip becomes at most [MAX_SEGMENTS]
 * EditedMediaItems, each one constant-speed (SpeedChangeEffect(segment.speed),
 * clipping configuration trimmed to the segment's source range) — planned by
 * CompositionFactory.planVideoSequence, which is the single sequence plan
 * for the composition. Preview (K4): ExoPlayer plays ONE item and follows
 * the segment speed live through setPlaybackSpeed — the factor inside a
 * segment is constant, so the preview can jump between segments (documented
 * limitation, PREVIEW_EXPORT_PARITY).
 *
 * Performance limit (K7): at most [MAX_SEGMENTS] segments per clip. A ramp
 * with more points is TRUNCATED (the first MAX_SEGMENTS intervals are kept;
 * the tail is absorbed into the last segment at its factor) — callers log
 * SPEED_RAMP_LIMIT via [wasTruncated].
 */
object SpeedRampPlanner {

    const val MIN_FACTOR = 0.1f
    const val MAX_FACTOR = 4.0f

    /** K7 — maximum constant-speed segments per clip (export item count cap). */
    const val MAX_SEGMENTS = 16

    /** Why a ramp point was dropped (evidence for SPEED_RAMP_INVALID). */
    enum class InvalidReason {
        /** localTimeMs outside [0, clip effective duration]. */
        TIME_OUT_OF_RANGE,
        /** factor outside [MIN_FACTOR, MAX_FACTOR] or non-finite. */
        FACTOR_OUT_OF_RANGE,
    }

    /**
     * One constant-speed segment. [startMs]/[endMs] are on the ramp domain
     * (constant-speed basis); [sourceStartMs]/[sourceEndMs] are in-file ms
     * (the export ClippingConfiguration window); [outputStartMs]/
     * [outputDurationMs] are clip-local OUTPUT times (the segment's footprint
     * on the project timeline).
     */
    data class SpeedSegment(
        val startMs: Long,
        val endMs: Long,
        val speed: Float,
        val sourceStartMs: Long,
        val sourceEndMs: Long,
        val outputStartMs: Long,
        val outputDurationMs: Long,
    )

    // ------------------------------------------------------------ sanitizing

    /**
     * Drops every invalid point of [ramps] against [clip]'s current domain
     * (out of range, non-finite, out-of-order timestamps are sorted; equal
     * timestamps keep the first point) and returns the surviving list sorted
     * by localTimeMs — null when fewer than 2 points remain (the clip then
     * behaves constant-speed, per the spec). Callers log SPEED_RAMP_INVALID
     * for the dropped points (the planner stays Android-free and silent).
     */
    fun sanitizePoints(clip: Clip, ramps: List<SpeedPoint> = clip.speedRamps): List<SpeedPoint>? {
        val domainEnd = clip.effectiveDurationMs
        val valid = ramps
            .filter { it.factor.isFinite() }
            .filter { it.localTimeMs in 0..domainEnd }
            .filter { it.factor in MIN_FACTOR..MAX_FACTOR }
        val sorted = valid.sortedBy { it.localTimeMs }
        val deduped = mutableListOf<SpeedPoint>()
        for (p in sorted) {
            if (deduped.isEmpty() || deduped.last().localTimeMs != p.localTimeMs) {
                deduped += p
            }
        }
        return if (deduped.size < 2) null else deduped
    }

    /** The dropped points with their reasons (for SPEED_RAMP_INVALID logs). */
    fun invalidPoints(
        clip: Clip,
        ramps: List<SpeedPoint> = clip.speedRamps,
    ): List<Pair<SpeedPoint, InvalidReason>> = ramps.mapNotNull { p ->
        val reason = when {
            !p.factor.isFinite() || p.factor < MIN_FACTOR || p.factor > MAX_FACTOR ->
                InvalidReason.FACTOR_OUT_OF_RANGE
            p.localTimeMs < 0 || p.localTimeMs > clip.effectiveDurationMs ->
                InvalidReason.TIME_OUT_OF_RANGE
            else -> null
        }
        reason?.let { p to it }
    }

    // -------------------------------------------------------------- planning

    /**
     * The constant-speed segments covering the WHOLE clip window. Without at
     * least 2 valid ramp points this is ONE segment at [Clip.speed] (the
     * pre-v0.7.0 behaviour, byte-compatible). With ramps: the ramp domain is
     * covered end-to-end (implicit boundary points at 0 and the domain end),
     * each inter-point interval gets >= 1 segment (extra slots up to
     * [MAX_SEGMENTS] are distributed proportionally to interval length,
     * largest-remainder), and the factor of a segment is the interpolated
     * factor at the segment MIDPOINT.
     */
    fun planSegments(clip: Clip): List<SpeedSegment> {
        val domainEnd = clip.effectiveDurationMs
        if (domainEnd <= 0L) {
            return listOf(
                SpeedSegment(0, 0, clip.speed, clip.trimStartMs, clip.trimEndMs, 0, 0),
            )
        }
        var pts = sanitizePoints(clip) ?: run {
            val output = ((clip.trimEndMs - clip.trimStartMs).coerceAtLeast(0) / clip.speed)
                .roundToLong()
            return listOf(
                SpeedSegment(
                    0, domainEnd, clip.speed, clip.trimStartMs, clip.trimEndMs, 0, output,
                ),
            )
        }
        // Implicit boundary points so the domain is covered end-to-end.
        if (pts.first().localTimeMs > 0L) pts = listOf(SpeedPoint(0, pts.first().factor)) + pts
        if (pts.last().localTimeMs < domainEnd) {
            pts = pts + SpeedPoint(domainEnd, pts.last().factor)
        }
        // K7 truncation: keep the first MAX_SEGMENTS intervals; the tail of
        // the domain is absorbed into the last kept interval (its end snaps
        // to the domain end at the kept point's factor).
        if (pts.size - 1 > MAX_SEGMENTS) {
            pts = pts.take(MAX_SEGMENTS + 1).dropLast(1) + SpeedPoint(
                domainEnd,
                pts[MAX_SEGMENTS].factor,
            )
        }

        val intervals = pts.zipWithNext()
        val base = intervals.size
        // Only intervals where the factor CHANGES benefit from subdivision —
        // a constant interval is exactly representable by ONE segment (a
        // constant-factor ramp therefore plans as a single segment).
        val changing = BooleanArray(intervals.size) { j ->
            intervals[j].first.factor != intervals[j].second.factor
        }
        val extra = (MAX_SEGMENTS - base).coerceAtLeast(0)
        // Largest-remainder distribution of the extra slots, proportional to
        // interval duration among the CHANGING intervals (longer ramps get
        // finer quantization first): floor allocation first, then the
        // leftover slots one each to the largest remainders — the total
        // lands on exactly MAX_SEGMENTS when fully subdivided.
        val changingMs = intervals.withIndex().sumOf { (j, pair) ->
            if (changing[j]) pair.second.localTimeMs - pair.first.localTimeMs else 0L
        }.coerceAtLeast(1)
        val raw = FloatArray(intervals.size) { j ->
            if (!changing[j] || extra == 0) 0f
            else {
                (intervals[j].second.localTimeMs - intervals[j].first.localTimeMs).toFloat() *
                    extra / changingMs
            }
        }
        val counts = IntArray(intervals.size) { 1 }
        if (extra > 0) {
            var used = 0
            for (j in intervals.indices) {
                val floor = raw[j].toInt()
                counts[j] += floor
                used += floor
            }
            var left = extra - used
            raw.withIndex()
                .filter { changing[it.index] }
                .sortedByDescending { it.value - it.value.toInt() }
                .forEach { (j, _) ->
                    if (left > 0) {
                        counts[j] += 1
                        left--
                    }
                }
        }

        val segments = mutableListOf<SpeedSegment>()
        var outputStart = 0L
        intervals.forEachIndexed { j, (a, b) ->
            val n = counts[j].coerceAtLeast(1)
            val span = (b.localTimeMs - a.localTimeMs).coerceAtLeast(1)
            for (s in 0 until n) {
                val s0 = a.localTimeMs + span * s / n
                val s1 = a.localTimeMs + span * (s + 1) / n
                val mid = (s0 + s1) / 2.0
                val speed = factorAtPoints(pts, mid)
                val srcStart = clip.trimStartMs + Math.round(s0 * clip.speed)
                val srcEnd = clip.trimStartMs + Math.round(s1 * clip.speed)
                if (srcEnd <= srcStart) continue // zero-length sub-segment (tiny interval)
                val outDur = ((srcEnd - srcStart).coerceAtLeast(0) / speed).roundToLong()
                segments += SpeedSegment(
                    startMs = s0,
                    endMs = s1,
                    speed = speed,
                    sourceStartMs = srcStart,
                    sourceEndMs = srcEnd,
                    outputStartMs = outputStart,
                    outputDurationMs = outDur,
                )
                outputStart += outDur
            }
        }
        return segments
    }

    /** K7 evidence: true when the stored ramp produced more than [MAX_SEGMENTS]. */
    fun wasTruncated(clip: Clip): Boolean {
        val pts = sanitizePoints(clip) ?: return false
        var count = pts.size
        if (pts.first().localTimeMs > 0) count += 1
        if (pts.last().localTimeMs < clip.effectiveDurationMs) count += 1
        return count - 1 > MAX_SEGMENTS
    }

    /** The ACTUAL clip footprint on the project timeline (ramp-aware). */
    fun outputDurationMs(clip: Clip): Long =
        planSegments(clip).sumOf { it.outputDurationMs }

    // --------------------------------------------------------- interpolation

    /** Linearly interpolated factor at ramp-domain time [localMs] (clamped ends). */
    fun factorAtLocalMs(clip: Clip, localMs: Long): Float {
        val pts = sanitizePoints(clip) ?: return clip.speed
        return factorAtPoints(pts, localMs.toDouble())
    }

    private fun factorAtPoints(pts: List<SpeedPoint>, localMs: Double): Float {
        if (localMs <= pts.first().localTimeMs) return pts.first().factor
        if (localMs >= pts.last().localTimeMs) return pts.last().factor
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            if (localMs >= a.localTimeMs && localMs <= b.localTimeMs) {
                val span = (b.localTimeMs - a.localTimeMs).coerceAtLeast(1)
                val t = ((localMs - a.localTimeMs) / span).toFloat()
                return a.factor + (b.factor - a.factor) * t
            }
        }
        return pts.last().factor
    }

    // -------------------------------------------------- time-base mappings

    /**
     * Project-local OUTPUT time (within the clip) -> item-local SOURCE time
     * (ms since trimStart). Without valid ramps: EXACTLY the pre-v0.7.0
     * ProjectMath formula (projectLocal * speed).
     */
    fun projectToItemLocalMs(clip: Clip, projectLocalMs: Long): Long {
        val ramps = sanitizePoints(clip)
        if (ramps == null) {
            return (projectLocalMs.coerceAtLeast(0) * clip.speed).roundToLong()
        }
        var t = projectLocalMs.coerceAtLeast(0)
        val segments = planSegments(clip)
        for (seg in segments) {
            if (t < seg.outputDurationMs || seg === segments.last()) {
                val local = t.coerceAtMost(seg.outputDurationMs)
                return seg.sourceStartMs + Math.round(local * seg.speed)
            }
            t -= seg.outputDurationMs
        }
        return clip.trimEndMs - clip.trimStartMs
    }

    /**
     * Item-local SOURCE time (ms since trimStart) -> project-local OUTPUT
     * time. Without valid ramps: EXACTLY the pre-v0.7.0 ProjectMath formula
     * (sourceLocal / speed).
     */
    fun itemLocalToProjectMs(clip: Clip, itemLocalMs: Long): Long {
        val ramps = sanitizePoints(clip)
        if (ramps == null) {
            return (itemLocalMs.coerceAtLeast(0) / clip.speed).roundToLong()
        }
        val s = itemLocalMs.coerceAtLeast(0)
        for (seg in planSegments(clip)) {
            if (s < seg.sourceEndMs || seg.sourceEndMs >= clip.trimEndMs) {
                val local = (s - seg.sourceStartMs).coerceIn(0, seg.sourceEndMs - seg.sourceStartMs)
                return seg.outputStartMs + Math.round(local / seg.speed)
            }
        }
        val last = planSegments(clip).last()
        return last.outputStartMs + last.outputDurationMs
    }

    /** The segment speed at project-local OUTPUT time [projectLocalMs] (K4). */
    fun speedAtProjectLocalMs(clip: Clip, projectLocalMs: Long): Float {
        val ramps = sanitizePoints(clip)
        if (ramps == null) return clip.speed
        var t = projectLocalMs.coerceAtLeast(0)
        val segments = planSegments(clip)
        for (seg in segments) {
            if (t < seg.outputDurationMs || seg === segments.last()) return seg.speed
            t -= seg.outputDurationMs
        }
        return clip.speed
    }
}

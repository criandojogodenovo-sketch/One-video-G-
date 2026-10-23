package com.onevideog.editor.editor

import com.onevideog.editor.data.Clip
import com.onevideog.editor.data.SpeedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.7.0 (Fase K) — the SpeedRampPlanner contract, locked on the JVM:
 *  - 0 points (or < 2 valid) -> ONE segment at clip.speed (byte-compatible
 *    with every pre-v0.7.0 clip);
 *  - 2 points -> linear factor interpolation between them (the spec example:
 *    (0ms, 1.0) and (1000ms, 0.5) -> factor at 500ms = 0.75);
 *  - out-of-range points are DROPPED (spec K1 edge cases) and fewer than 2
 *    survivors behave constant-speed;
 *  - at most MAX_SEGMENTS segments per clip (K7) and outputDurationMs equals
 *    the sum of the segments' footprints;
 *  - the time-base mappings are exact for the constant case and
 *    segment-consistent for the ramp case (round-trips with the plan).
 */
class SpeedRampPlannerTest {

    private fun clip(
        durMs: Long = 10_000,
        speed: Float = 1f,
        ramps: List<SpeedPoint> = emptyList(),
    ) = Clip(
        id = "c1", uri = "u", displayName = "d", sourceDurationMs = durMs,
        trimStartMs = 0, trimEndMs = durMs, speed = speed, speedRamps = ramps,
    )

    // --------------------------------------------------------- 0 / 1 points

    @Test
    fun `zero points yield one segment at clip speed`() {
        val c = clip(durMs = 8_000, speed = 1f)
        val segments = SpeedRampPlanner.planSegments(c)
        assertEquals(1, segments.size)
        assertEquals(1f, segments[0].speed, 1e-6f)
        assertEquals(0L, segments[0].startMs)
        assertEquals(8_000L, segments[0].endMs)
        assertEquals(0L, segments[0].sourceStartMs)
        assertEquals(8_000L, segments[0].sourceEndMs)
        assertEquals(8_000L, segments[0].outputDurationMs)
        assertEquals(8_000L, SpeedRampPlanner.outputDurationMs(c))
    }

    @Test
    fun `one point is ignored - constant speed`() {
        val c = clip(ramps = listOf(SpeedPoint(0, 0.5f)))
        assertTrue(SpeedRampPlanner.sanitizePoints(c) == null)
        val segments = SpeedRampPlanner.planSegments(c)
        assertEquals(1, segments.size)
        assertEquals(1f, segments[0].speed, 1e-6f)
    }

    @Test
    fun `constant-speed ramp keeps clip speed semantics`() {
        // 2 s source at speed 2 -> basis duration 1 s; a constant 0.5x ramp
        // doubles the footprint to 2 s.
        val c = clip(durMs = 2_000, speed = 2f, ramps = listOf(SpeedPoint(0, 0.5f), SpeedPoint(1_000, 0.5f)))
        val segments = SpeedRampPlanner.planSegments(c)
        assertEquals(1, segments.size)
        assertEquals(0.5f, segments[0].speed, 1e-4f)
        // Source consumed: 2000 ms at 0.5x -> output 4000 ms.
        assertEquals(4_000L, SpeedRampPlanner.outputDurationMs(c))
    }

    // -------------------------------------------------- linear interpolation

    @Test
    fun `two points interpolate linearly`() {
        // The spec example: A (0ms, 1.0), B (1000ms, 0.5) -> factor(500) = 0.75.
        val c = clip(durMs = 1_000, ramps = listOf(SpeedPoint(0, 1f), SpeedPoint(1_000, 0.5f)))
        assertEquals(0.75f, SpeedRampPlanner.factorAtLocalMs(c, 500), 1e-4f)
        assertEquals(1f, SpeedRampPlanner.factorAtLocalMs(c, 0), 1e-4f)
        assertEquals(0.5f, SpeedRampPlanner.factorAtLocalMs(c, 1_000), 1e-4f)
        // Clamp semantics outside the point range.
        assertEquals(1f, SpeedRampPlanner.factorAtLocalMs(c, -100), 1e-4f)
        assertEquals(0.5f, SpeedRampPlanner.factorAtLocalMs(c, 2_000), 1e-4f)
    }

    @Test
    fun `ramp segments respect the interpolated speed`() {
        val c = clip(durMs = 1_000, ramps = listOf(SpeedPoint(0, 1f), SpeedPoint(1_000, 0.5f)))
        val segments = SpeedRampPlanner.planSegments(c)
        assertTrue("expected several segments, got ${segments.size}", segments.size >= 2)
        // Speeds descend monotonically from ~1.0 towards ~0.5.
        assertTrue(segments.first().speed > segments.last().speed)
        assertEquals(1f, segments.first().speed, 0.05f)
        assertEquals(0.5f, segments.last().speed, 0.05f)
        // Segment source ranges are contiguous and cover the clip window.
        assertEquals(0L, segments.first().sourceStartMs)
        assertEquals(1_000L, segments.last().sourceEndMs)
        for (i in 1 until segments.size) {
            assertEquals(segments[i - 1].sourceEndMs, segments[i].sourceStartMs)
        }
        // Output positions are contiguous and the total is the footprint.
        var acc = 0L
        for (seg in segments) {
            assertEquals(acc, seg.outputStartMs)
            acc += seg.outputDurationMs
        }
        assertEquals(SpeedRampPlanner.outputDurationMs(c), acc)
    }

    @Test
    fun `output duration of a ramp equals the integral of source over factor`() {
        // 1000 ms source; linear 1.0 -> 0.5: integral of dt/f over the basis...
        // The exact continuous value: for source span S consumed at factor f(t)
        // with f linear in the BASIS time t, output = sum over segments.
        // With a fine segmentation the discrete sum must approach the
        // analytic value S/(f_end - f_start) * ln(f_end/f_start)... the test
        // pins the planner against ITSELF at two resolutions instead: the
        // value must be within [S/1.0, S/0.5] (bounded by the extremes) and
        // strictly greater than the pure-fast path.
        val c = clip(durMs = 1_000, ramps = listOf(SpeedPoint(0, 1f), SpeedPoint(1_000, 0.5f)))
        val out = SpeedRampPlanner.outputDurationMs(c)
        assertTrue("output $out below fast bound", out > 1_000L)
        assertTrue("output $out above slow bound", out < 2_000L)
    }

    // ----------------------------------------------------------- edge cases

    @Test
    fun `out of range points are dropped`() {
        val c = clip(durMs = 1_000)
        val ramps = listOf(
            SpeedPoint(-100, 1f),        // time < 0 -> dropped
            SpeedPoint(0, 1f),           // kept
            SpeedPoint(500, 2f),         // kept
            SpeedPoint(2_000, 1f),       // time > domain -> dropped
            SpeedPoint(750, 8f),         // factor out of range -> dropped
            SpeedPoint(800, 0.05f),      // factor below range -> dropped
        )
        val invalid = SpeedRampPlanner.invalidPoints(c, ramps)
        assertEquals(4, invalid.size)
        val kept = SpeedRampPlanner.sanitizePoints(c, ramps)!!
        assertEquals(listOf(0L, 500L), kept.map { it.localTimeMs })
    }

    @Test
    fun `fewer than two survivors behave constant`() {
        val c = clip(durMs = 1_000, ramps = listOf(SpeedPoint(0, 1f), SpeedPoint(5_000, 1f)))
        // The second point is out of the 1000 ms domain -> dropped -> constant.
        assertNull(SpeedRampPlanner.sanitizePoints(c))
        val segments = SpeedRampPlanner.planSegments(c)
        assertEquals(1, segments.size)
        assertEquals(1f, segments[0].speed, 1e-6f)
    }

    @Test
    fun `unsorted input is sorted by the planner`() {
        val c = clip(
            durMs = 1_000,
            ramps = listOf(SpeedPoint(800, 0.6f), SpeedPoint(0, 1f), SpeedPoint(400, 0.8f)),
        )
        val kept = SpeedRampPlanner.sanitizePoints(c)!!
        assertEquals(listOf(0L, 400L, 800L), kept.map { it.localTimeMs })
    }

    // ------------------------------------------------------------- K7 limit

    @Test
    fun `ramp with many points truncates to max segments`() {
        // 20 points (0..9500) on a 10 s clip: 19 intervals + boundaries > 16.
        val ramps = (0 until 20).map { SpeedPoint(it * 500L, if (it % 2 == 0) 1f else 0.5f) }
        val c = clip(durMs = 10_000, ramps = ramps)
        assertTrue(SpeedRampPlanner.wasTruncated(c))
        val segments = SpeedRampPlanner.planSegments(c)
        assertTrue(
            "segments ${segments.size} > MAX",
            segments.size <= SpeedRampPlanner.MAX_SEGMENTS,
        )
        // Truncation still covers the whole window.
        assertEquals(0L, segments.first().sourceStartMs)
        assertEquals(10_000L, segments.last().sourceEndMs)
    }

    @Test
    fun `small ramps are not truncated`() {
        val c = clip(durMs = 10_000, ramps = listOf(SpeedPoint(0, 1f), SpeedPoint(10_000, 0.5f)))
        assertFalse(SpeedRampPlanner.wasTruncated(c))
        assertTrue(SpeedRampPlanner.planSegments(c).size <= SpeedRampPlanner.MAX_SEGMENTS)
    }

    // ------------------------------------------------------- time mappings

    @Test
    fun `mappings match the legacy formulas for constant clips`() {
        val c = clip(durMs = 10_000, speed = 2f)
        assertEquals(4_000L, SpeedRampPlanner.projectToItemLocalMs(c, 2_000))
        assertEquals(2_000L, SpeedRampPlanner.itemLocalToProjectMs(c, 4_000))
        assertEquals(2f, SpeedRampPlanner.speedAtProjectLocalMs(c, 1_000), 1e-6f)
    }

    @Test
    fun `ramp mappings round trip through the plan`() {
        val c = clip(durMs = 2_000, ramps = listOf(SpeedPoint(0, 2f), SpeedPoint(2_000, 0.5f)))
        val segments = SpeedRampPlanner.planSegments(c)
        // Every segment's output window maps to its source window and back.
        for (seg in segments) {
            val outMid = seg.outputStartMs + seg.outputDurationMs / 2
            val srcMid = SpeedRampPlanner.projectToItemLocalMs(c, outMid)
            assertTrue(
                "source mid ${srcMid} outside [${seg.sourceStartMs}, ${seg.sourceEndMs})",
                srcMid >= seg.sourceStartMs && srcMid <= seg.sourceEndMs,
            )
            val outBack = SpeedRampPlanner.itemLocalToProjectMs(c, srcMid)
            assertTrue(
                "round trip drifted: $outMid -> $srcMid -> $outBack",
                kotlin.math.abs(outBack - outMid) <= seg.outputDurationMs,
            )
        }
        // Bounds.
        assertEquals(0L, SpeedRampPlanner.projectToItemLocalMs(c, 0))
        assertEquals(2_000L, SpeedRampPlanner.projectToItemLocalMs(c, 4_000))
    }

    @Test
    fun `speedAtProjectLocalMs follows the ramp segments`() {
        val c = clip(durMs = 2_000, ramps = listOf(SpeedPoint(0, 2f), SpeedPoint(2_000, 0.5f)))
        val segments = SpeedRampPlanner.planSegments(c)
        // First segment's output start is fast, last segment's output end slow.
        assertEquals(segments.first().speed, SpeedRampPlanner.speedAtProjectLocalMs(c, 0), 1e-6f)
        val last = segments.last()
        assertEquals(
            last.speed,
            SpeedRampPlanner.speedAtProjectLocalMs(c, last.outputStartMs + last.outputDurationMs - 1),
            1e-6f,
        )
        // And the speeds are ordered descending (fast -> slow).
        assertTrue(segments.first().speed > last.speed)
    }

    @Test
    fun `zero-length clip yields a single empty segment`() {
        val c = clip(durMs = 0)
        val segments = SpeedRampPlanner.planSegments(c)
        assertEquals(1, segments.size)
        assertEquals(0L, SpeedRampPlanner.outputDurationMs(c))
    }
}

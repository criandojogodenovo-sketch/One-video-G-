package com.onevideog.editor

import com.onevideog.editor.editor.TimelineScale
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

/**
 * Regression tests for the density-aware timeline scale. Guards against the
 * v0.2.1 px/dp mismatch (ruler taps scrubbing ~2x off at phone densities,
 * blocks misaligned with the ruler) — every conversion must round-trip.
 */
class TimelineScaleTest {

    @Test
    fun `at zoom 1 and density 1 one design px is 60 ms`() {
        val scale = TimelineScale(density = 1f, zoom = 1f)
        assertEquals(60f, scale.msPerPx, 0.0001f)
        assertEquals(1000f / 60f, scale.timeToPx(1000L), 0.0001f)
    }

    @Test
    fun `density multiplies pixels - the v0_2_1 bug class`() {
        val scale = TimelineScale(density = 2f, zoom = 1f)
        // 1 second spans 1000/60 design px = 16.67 dp = 33.33 real px.
        assertEquals(1000f / 60f * 2f, scale.timeToPx(1000L), 0.001f)
    }

    @Test
    fun `zoom divides ms per px`() {
        val scale = TimelineScale(density = 1f, zoom = 2f)
        assertEquals(30f, scale.msPerPx, 0.0001f)
        val zoomedOut = TimelineScale(density = 1f, zoom = 0.5f)
        assertEquals(120f, zoomedOut.msPerPx, 0.0001f)
    }

    @Test
    fun `time and pixel conversions round-trip`() {
        val scale = TimelineScale(density = 2.6f, zoom = 1.7f)
        for (ms in longArrayOf(0, 250, 1000, 47_500, 601_234)) {
            val back = scale.pxToTime(scale.timeToPx(ms))
            assertEquals(
                "ms=$ms",
                ms.toFloat(),
                back.toFloat(),
                1.5f, // sub-pixel rounding tolerance
            )
        }
    }

    @Test
    fun `px to time clamps to the project range`() {
        val scale = TimelineScale(density = 2f, zoom = 1f)
        val duration = 5_000L
        assertEquals(0L, scale.pxToTimeClamped(-100f, duration))
        assertEquals(duration, scale.pxToTimeClamped(99_999f, duration))
        assertEquals(2_500L, scale.pxToTimeClamped(scale.timeToPx(2_500f), duration))
    }

    @Test
    fun `zero and negative density are guarded`() {
        val scale = TimelineScale(density = 0f, zoom = 1f)
        assertEquals(TimelineScale(density = 1f, zoom = 1f).msPerPx, scale.msPerPx, 0.0001f)
    }

    @Test
    fun `zoom clamp bounds editing requests`() {
        assertEquals(0.5f, TimelineScale.clampZoom(0.1f), 0.0001f)
        assertEquals(6f, TimelineScale.clampZoom(99f), 0.0001f)
        assertEquals(1.4f, TimelineScale.clampZoom(1.4f), 0.0001f)
    }

    @Test
    fun `span widths stay proportional at any density`() {
        val scale = TimelineScale(density = 1.75f, zoom = 1f)
        val short = scale.spanToPx(1_000L)
        val long = scale.spanToPx(10_000L)
        assertEquals(
            10f,
            long / short,
            0.01f,
        )
        assert(abs(long - short * 10f) < 0.5f)
    }
}

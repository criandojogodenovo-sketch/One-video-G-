package com.onevideog.editor

import com.onevideog.editor.editor.TimelineScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * v0.2.4 regression: device-evidence positions (start / middle / end)
     * must round-trip through the ruler mapping at the reporting device's
     * density (realme RMX3624 — 720 px-wide HD+ panel, density 1.65) and at
     * the other common phone densities, for every zoom step. A mapping bug
     * would show up as a large px→time error at one of the extremes.
     */
    @Test
    fun `start middle and end scrub positions map correctly at device densities`() {
        val durationMs = 60_000L
        val fractions = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val zooms = floatArrayOf(0.5f, 1f, 2f, 4f)
        for (density in floatArrayOf(1f, 1.65f, 2.15f, 2.6f)) {
            for (zoom in zooms) {
                val scale = TimelineScale(density = density, zoom = zoom)
                for (fraction in fractions) {
                    val targetMs = (durationMs * fraction).toLong()
                    val px = scale.timeToPx(targetMs)
                    val back = scale.pxToTimeClamped(px, durationMs)
                    // Sub-pixel round trip: 1 px of error at zoom 0.5 /
                    // density 1 is 120 ms; at the tightest zoom the tolerance
                    // is one design pixel.
                    val toleranceMs = TimelineScale.DESIGN_MS_PER_PX / zoom * 1.01f
                    assertEquals(
                        "density=$density zoom=$zoom fraction=$fraction",
                        targetMs.toFloat(),
                        back.toFloat(),
                        toleranceMs,
                    )
                }
            }
        }
    }

    /**
     * v0.2.4 regression: the device report showed a scrub ending at 120 ms.
     * Whatever produced it, the mapping itself must be able to REPRESENT
     * 120 ms exactly where the finger lands and clamp negative px to 0.
     */
    @Test
    fun `near-zero scrub positions clamp and represent correctly`() {
        val scale = TimelineScale(density = 1.65f, zoom = 1f)
        assertEquals(0L, scale.pxToTimeClamped(-50f, 60_000L))
        assertEquals(0L, scale.pxToTimeClamped(0f, 60_000L))
        val px120 = scale.timeToPx(120L)
        assertTrue(px120 >= 0f)
        assertEquals(120f, scale.pxToTimeClamped(px120, 60_000L).toFloat(), TimelineScale.DESIGN_MS_PER_PX * 1.01f)
    }
}

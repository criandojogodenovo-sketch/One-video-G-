package com.onevideog.editor.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.5 regression: the VIEWPORT_GEOMETRY evidence event must fire once per
 * DISTINCT geometry — not once per layout pass / video callback.
 */
class ViewportGeometryTrackerTest {

    private fun geom(
        videoW: Int = 0,
        videoH: Int = 0,
        surfaceW: Int = 720,
        surfaceH: Int = 405,
        viewportW: Int = 720,
        viewportH: Int = 405,
        aspect: Float = 4f / 3f,
    ) = ViewportGeometryTracker.Geometry(
        videoW, videoH, surfaceW, surfaceH, viewportW, viewportH, aspect,
    )

    @Test
    fun `first geometry logs, identical does not`() {
        val tracker = ViewportGeometryTracker()
        assertTrue(tracker.shouldLog(geom()))
        assertFalse(tracker.shouldLog(geom()))
        assertFalse(tracker.shouldLog(geom()))
    }

    @Test
    fun `any source change logs again`() {
        val tracker = ViewportGeometryTracker()
        tracker.shouldLog(geom()) // pre-decode state: video 0x0
        // Video decode arrives later (0x0 -> real geometry).
        assertTrue(tracker.shouldLog(geom(videoW = 640, videoH = 480)))
        // Surface relayout.
        assertTrue(tracker.shouldLog(geom(videoW = 640, videoH = 480, surfaceW = 360, surfaceH = 202, viewportW = 360, viewportH = 202)))
        // Viewport change (rotation/aspect switch).
        assertTrue(tracker.shouldLog(geom(videoW = 640, videoH = 480, surfaceW = 360, surfaceH = 202, viewportW = 202, viewportH = 360, aspect = 16f / 9f)))
        // A state equal to an OLDER one (not the last) still logs: last-only dedupe.
        assertTrue(tracker.shouldLog(geom()))
    }

    @Test
    fun `reset logs again for the same geometry`() {
        val tracker = ViewportGeometryTracker()
        tracker.shouldLog(geom())
        assertFalse(tracker.shouldLog(geom()))
        tracker.reset()
        assertTrue(tracker.shouldLog(geom()))
    }
}

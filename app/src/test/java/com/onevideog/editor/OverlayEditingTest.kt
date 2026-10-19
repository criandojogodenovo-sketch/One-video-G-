package com.onevideog.editor

import com.onevideog.editor.editor.OverlayEditing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the pure interval editing rules used by the timeline
 * edge handles and the sheet editors (min duration, zero/negative length
 * prevention, boundary clamping).
 */
class OverlayEditingTest {

    private val MIN = OverlayEditing.MIN_OVERLAY_MS

    @Test
    fun `left trim clamps to zero and to end minus minimum`() {
        assertEquals(0L, OverlayEditing.clampTrimStart(-500, 10_000, MIN))
        assertEquals(500L, OverlayEditing.clampTrimStart(500, 10_000, MIN))
        // 10_000 - 150 is the latest legal start for end=10_000.
        assertEquals(9_850L, OverlayEditing.clampTrimStart(9_990, 10_000, MIN))
    }

    @Test
    fun `left trim on a too-short interval clamps to zero`() {
        assertEquals(0L, OverlayEditing.clampTrimStart(50, 100, MIN))
    }

    @Test
    fun `right trim clamps to start plus minimum`() {
        assertEquals(650L, OverlayEditing.clampTrimEnd(500, 600, MIN))
        assertEquals(4_000L, OverlayEditing.clampTrimEnd(500, 4_000, MIN))
    }

    @Test
    fun `move clamps to zero`() {
        assertEquals(0L, OverlayEditing.clampMove(-123))
        assertEquals(4_321L, OverlayEditing.clampMove(4_321))
    }

    @Test
    fun `free trim rejects zero and negative durations`() {
        assertNull(OverlayEditing.clampTrim(1_000, 1_000, MIN))
        assertNull(OverlayEditing.clampTrim(2_000, 1_000, MIN))
        assertNull(OverlayEditing.clampTrim(0, 100, MIN))
    }

    @Test
    fun `free trim accepts valid ranges and clamps negatives`() {
        assertEquals(100L to 2_000L, OverlayEditing.clampTrim(100, 2_000, MIN))
        assertEquals(0L to 150L, OverlayEditing.clampTrim(-10, 150, MIN))
    }

    @Test
    fun `minimum is 150 ms`() {
        assertEquals(150L, MIN)
    }
}

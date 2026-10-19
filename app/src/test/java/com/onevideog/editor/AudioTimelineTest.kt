package com.onevideog.editor.editor

import com.onevideog.editor.data.AudioClipItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTimelineTest {

    private fun clip(start: Long, trimStart: Long, trimEnd: Long, loop: Boolean = false) =
        AudioClipItem(
            id = "a", uri = "u", displayName = "d", sourceDurationMs = 30_000,
            startMs = start, trimStartMs = trimStart, trimEndMs = trimEnd, loop = loop,
        )

    @Test
    fun `normal clip produces a single segment`() {
        val segments = AudioTimeline.segmentsFor(clip(1_000, 500, 3_500), null)
        assertEquals(1, segments.size)
        assertEquals(500L, segments[0].sourceStartMs)
        assertEquals(3_500L, segments[0].sourceEndMs)
        assertEquals(1_000L, segments[0].timelineStartMs)
        assertEquals(4_000L, segments[0].timelineEndMs) // 1000 + (3500-500)
    }

    @Test
    fun `looping clip repeats until the fill target`() {
        val segments = AudioTimeline.segmentsFor(clip(0, 0, 2_000, loop = true), 7_000)
        assertEquals(4, segments.size)
        // Back-to-back placement.
        for (i in 1 until segments.size) {
            assertEquals(segments[i - 1].timelineEndMs, segments[i].timelineStartMs)
        }
        assertEquals(8_000L, segments.last().timelineEndMs) // overshoots 7s, matches loop behaviour
    }

    @Test
    fun `empty trim has no segments`() {
        assertTrue(AudioTimeline.segmentsFor(clip(0, 1_000, 1_000), 5_000).isEmpty())
    }

    @Test
    fun `active segment at finds the playing segment`() {
        val clip = clip(1_000, 0, 2_000)
        assertNull(AudioTimeline.activeSegmentAt(clip, 500))
        val active = AudioTimeline.activeSegmentAt(clip, 2_000)
        assertEquals(1_000L, active?.timelineStartMs)
        assertNull(AudioTimeline.activeSegmentAt(clip, 3_001))
    }

    // ------------------------------------------------------------------
    // sourcePositionAt — the pure preview-scheduling decision behind
    // AudioPreviewController (regression tests for the v0.2.1 audio fixes).
    // ------------------------------------------------------------------

    @Test
    fun `source position is null before start and after end`() {
        val c = clip(1_000, 500, 3_500, loop = false)
        assertNull(AudioTimeline.sourcePositionAt(c, 999, 10_000))
        assertEquals(500L, AudioTimeline.sourcePositionAt(c, 1_000, 10_000))
        assertEquals(3_499L, AudioTimeline.sourcePositionAt(c, 3_999, 10_000))
        assertNull(AudioTimeline.sourcePositionAt(c, 4_000, 10_000))
    }

    @Test
    fun `looping source position wraps across the trimmed range`() {
        val c = clip(0, 1_000, 3_000, loop = true)
        // 0 → 1000, 2000 → wraps to 1000, 4000 → wraps to 2000-1000=1000+... 4000 % 2000 = 0 → 1000
        assertEquals(1_000L, AudioTimeline.sourcePositionAt(c, 0, 10_000))
        assertEquals(2_999L, AudioTimeline.sourcePositionAt(c, 1_999, 10_000))
        assertEquals(1_000L, AudioTimeline.sourcePositionAt(c, 2_000, 10_000))
        assertEquals(1_500L, AudioTimeline.sourcePositionAt(c, 4_500, 10_000))
    }

    @Test
    fun `looping clip is silent past the video fill end`() {
        val c = clip(0, 0, 2_000, loop = true)
        assertEquals(1_999L, AudioTimeline.sourcePositionAt(c, 9_999, 10_000))
        assertNull(AudioTimeline.sourcePositionAt(c, 10_000, 10_000))
    }

    @Test
    fun `source position never exceeds the source duration`() {
        val c = AudioClipItem(
            id = "a", uri = "u", displayName = "d", sourceDurationMs = 5_000,
            startMs = 0, trimStartMs = 0, trimEndMs = 5_000, loop = false,
        )
        assertEquals(4_999L, AudioTimeline.sourcePositionAt(c, 4_999, 0))
        // Degenerate metadata (zero duration source) must schedule nothing.
        assertNull(AudioTimeline.sourcePositionAt(c.copy(sourceDurationMs = 0), 0, 0))
    }

    @Test
    fun `empty trim schedules nothing`() {
        val c = clip(0, 2_000, 2_000)
        assertNull(AudioTimeline.sourcePositionAt(c, 0, 10_000))
    }
}

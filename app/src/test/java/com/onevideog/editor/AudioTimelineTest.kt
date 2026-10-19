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
}

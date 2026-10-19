package com.onevideog.editor

import com.onevideog.editor.data.AudioClipItem
import com.onevideog.editor.editor.AudioTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the export audio plan. The P0 export-failure trace
 * found that non-looping audio extending beyond the video end produced a
 * sequence LONGER than the video sequence — unequal parallel sequence
 * durations in a Media3 1.4.1 Composition. The plan must now clamp EXACTLY
 * to the video duration.
 */
class AudioTimelineExportPlanTest {

    private fun audio(
        startMs: Long,
        trimStartMs: Long = 0,
        trimEndMs: Long = 30_000,
        sourceDurationMs: Long = 30_000,
        loop: Boolean = false,
    ) = AudioClipItem(
        id = "a",
        uri = "content://test/audio",
        displayName = "audio",
        sourceDurationMs = sourceDurationMs,
        startMs = startMs,
        trimStartMs = trimStartMs,
        trimEndMs = trimEndMs,
        loop = loop,
    )

    private val VIDEO = 60_000L

    @Test
    fun `audio inside the video gets a tail silence to match exactly`() {
        val plan = AudioTimeline.exportPlan(audio(startMs = 0, trimEndMs = 20_000), VIDEO)
        assertEquals(0, plan.leadSilenceMs)
        assertEquals(1, plan.segments.size)
        assertEquals(20_000, plan.segments[0].durationMs)
        assertEquals(40_000, plan.tailSilenceMs)
        assertEquals(VIDEO, plan.totalDurationMs)
    }

    @Test
    fun `audio with a lead gap gets leading silence`() {
        val plan = AudioTimeline.exportPlan(audio(startMs = 10_000), VIDEO)
        assertEquals(10_000, plan.leadSilenceMs)
        assertEquals(VIDEO, plan.totalDurationMs)
    }

    @Test
    fun `audio extending beyond the video end is clipped`() {
        val plan = AudioTimeline.exportPlan(audio(startMs = 50_000), VIDEO)
        assertEquals(1, plan.segments.size)
        assertEquals(50_000, plan.segments[0].timelineStartMs)
        assertEquals(10_000, plan.segments[0].durationMs)
        assertEquals(0, plan.tailSilenceMs)
        assertEquals(VIDEO, plan.totalDurationMs)
    }

    @Test
    fun `audio starting at or after the video end contributes nothing`() {
        val plan = AudioTimeline.exportPlan(audio(startMs = VIDEO), VIDEO)
        assertTrue(plan.segments.isEmpty())
        assertEquals(0, plan.totalDurationMs)
    }

    @Test
    fun `zero fill (no video) plans nothing`() {
        val plan = AudioTimeline.exportPlan(audio(startMs = 0), 0)
        assertTrue(plan.segments.isEmpty())
        assertEquals(0, plan.totalDurationMs)
    }

    @Test
    fun `plan is exact for late non-loop audio starting mid video`() {
        // start 45s, source window 0..30s -> audible 45..60s (clamped at 60s).
        val plan = AudioTimeline.exportPlan(
            audio(startMs = 45_000, trimStartMs = 0, trimEndMs = 30_000),
            VIDEO,
        )
        assertEquals(45_000, plan.leadSilenceMs)
        assertEquals(15_000, plan.segments[0].durationMs)
        assertEquals(VIDEO, plan.totalDurationMs)
    }

    @Test
    fun `non-loop plan segments preserve source offsets`() {
        val plan = AudioTimeline.exportPlan(
            audio(startMs = 5_000, trimStartMs = 2_000, trimEndMs = 8_000),
            VIDEO,
        )
        val segment = plan.segments.single()
        assertEquals(2_000, segment.sourceStartMs)
        assertEquals(8_000, segment.sourceEndMs)
        assertEquals(5_000, segment.timelineStartMs)
    }
}

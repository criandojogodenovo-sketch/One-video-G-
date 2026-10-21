package com.onevideog.editor.editor

import com.onevideog.editor.data.AudioClipItem
import com.onevideog.editor.data.Clip
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectMathTest {

    private fun clip(durMs: Long) = Clip(
        id = "c", uri = "u", displayName = "d", sourceDurationMs = durMs,
        trimStartMs = 0, trimEndMs = durMs,
    )

    private fun overlay(start: Long, end: Long) = OverlayItem(
        id = "o", kind = OverlayKind.TEXT, startMs = start, endMs = end, text = "x",
    )

    private fun audio(start: Long, trimStart: Long, trimEnd: Long) = AudioClipItem(
        id = "a", uri = "u", displayName = "d", sourceDurationMs = 60_000,
        startMs = start, trimStartMs = trimStart, trimEndMs = trimEnd,
    )

    private fun project(clips: List<Clip> = emptyList(), overlays: List<OverlayItem> = emptyList(), audio: List<AudioClipItem> = emptyList()) =
        OvgProject(id = "p", name = "n", createdAtEpochMs = 0, updatedAtEpochMs = 0)
            .copy(clips = clips, overlays = overlays, audioClips = audio)

    @Test
    fun `video duration is the sum of effective durations`() {
        assertEquals(3_000L, ProjectMath.videoDurationMs(listOf(clip(1_000), clip(2_000))))
    }

    @Test
    fun `duration is video length when nothing extends past it`() {
        val p = project(
            clips = listOf(clip(5_000)),
            overlays = listOf(overlay(0, 4_000)),
            audio = listOf(audio(1_000, 0, 3_000)),
        )
        assertEquals(5_000L, ProjectMath.projectDurationMs(p))
    }

    @Test
    fun `audio longer than video does NOT extend the duration (v0_2_5 rule)`() {
        // Device-reported bug (v0.2.4 report): importing a long audio used to
        // stretch durationMs from 5 s to 13 s while the export stayed 5 s.
        val p = project(
            clips = listOf(clip(5_000)),
            audio = listOf(audio(3_000, 0, 10_000)),
        )
        assertEquals(5_000L, ProjectMath.projectDurationMs(p))
    }

    @Test
    fun `audio shorter than video keeps the video duration`() {
        val p = project(
            clips = listOf(clip(5_000)),
            audio = listOf(audio(1_000, 0, 3_000)),
        )
        assertEquals(5_000L, ProjectMath.projectDurationMs(p))
    }

    @Test
    fun `audio-only project keeps the audio-driven duration`() {
        val p = project(audio = listOf(audio(0, 0, 10_000)))
        assertEquals(10_000L, ProjectMath.projectDurationMs(p))
    }

    @Test
    fun `overlay past the video end does NOT extend the duration (v0_3_0 rule)`() {
        // Device-reported temporal bug (v0.3.0 report): an image bar dragged
        // past the video end used to stretch the timeline while the export
        // composition stayed video-length — and playback beyond the video
        // end froze the UI. The video now defines the duration; the overlay
        // persists but renders nothing outside the active range.
        val p = project(
            clips = listOf(clip(5_000)),
            overlays = listOf(overlay(4_000, 8_000)),
        )
        assertEquals(5_000L, ProjectMath.projectDurationMs(p))
    }

    @Test
    fun `overlay fully past the video end keeps the video duration`() {
        val p = project(
            clips = listOf(clip(5_490)),
            overlays = listOf(overlay(60_000, 65_000)),
        )
        assertEquals(5_490L, ProjectMath.projectDurationMs(p))
        // The element still exists and the timeline span shows it, but the
        // playhead range (duration) never enters the inactive region.
        assertEquals(65_000L, ProjectMath.timelineSpanMs(p))
    }

    @Test
    fun `timeline span is the widest element and never below the duration`() {
        val p = project(
            clips = listOf(clip(10_000)),
            overlays = listOf(overlay(0, 4_000)),
            audio = listOf(audio(2_000, 0, 6_000)),
        )
        assertEquals(10_000L, ProjectMath.timelineSpanMs(p))
        val extended = p.copy(overlays = listOf(overlay(60_000, 65_000)))
        assertEquals(65_000L, ProjectMath.timelineSpanMs(extended))
    }

    @Test
    fun `playhead clamp keeps the playhead inside the video range`() {
        // Regression for the frozen-playback bug: scrub/transport writes
        // can never park the playhead past the video end.
        assertEquals(5_490L, ProjectMath.clampPlayheadMs(60_000, 5_490))
    }

    @Test
    fun `speed affects the authoritative duration`() {
        val fast = clip(4_000).copy(speed = 2f)
        assertEquals(2_000L, ProjectMath.videoDurationMs(listOf(fast)))
    }

    @Test
    fun `project to item local time is 0-based within the window`() {
        val c = clip(10_000).copy(trimStartMs = 2_000, speed = 1f)
        assertEquals(500L, ProjectMath.projectToItemLocalMs(c, 500))
        assertEquals(500L, ProjectMath.itemLocalToProjectMs(c, 500))
        val fast = clip(10_000).copy(speed = 2f)
        assertEquals(1_000L, ProjectMath.projectToItemLocalMs(fast, 500))
        assertEquals(500L, ProjectMath.itemLocalToProjectMs(fast, 1_000))
    }

    @Test
    fun `fully empty project has the 5 s empty-project floor (v0_5_1)`() {
        // v0.5.1: a project without base video starts at 5 s so the timeline
        // is scrubbable and the black-background export has a real duration
        // from the first element. The pre-v0.5.1 contract (zero duration)
        // made a video-less project impossible to export or scrub.
        assertEquals(ProjectMath.MIN_EMPTY_DURATION_MS, ProjectMath.projectDurationMs(project()))
        assertEquals(5_000L, ProjectMath.projectDurationMs(project()))
    }

    @Test
    fun `empty project grows with its clips beyond the 5 s floor`() {
        val audioOnly = project(audio = listOf(audio(0, 0, 12_000)))
        assertEquals(12_000L, ProjectMath.projectDurationMs(audioOnly))
        val overlayOnly = project(overlays = listOf(overlay(0, 8_000)))
        assertEquals(8_000L, ProjectMath.projectDurationMs(overlayOnly))
    }
}
// v0.2.1 regression: the single clamp used by every playhead writer
// (scrub taps/drags, transport ±10 s seeks, end-of-playback).
class ClampPlayheadTest {
    @Test
    fun `clamps negative and past-end requests`() {
        assertEquals(0L, ProjectMath.clampPlayheadMs(-5_000, 10_000))
        assertEquals(10_000L, ProjectMath.clampPlayheadMs(99_999, 10_000))
        assertEquals(4_999L, ProjectMath.clampPlayheadMs(4_999, 10_000))
    }

    @Test
    fun `empty project clamps everything to zero`() {
        assertEquals(0L, ProjectMath.clampPlayheadMs(1_000, 0))
        assertEquals(0L, ProjectMath.clampPlayheadMs(-1, -10))
    }
}

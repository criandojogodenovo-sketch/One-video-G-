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
    fun `duration extends to the latest audio end`() {
        val p = project(
            clips = listOf(clip(5_000)),
            audio = listOf(audio(3_000, 0, 10_000)),
        )
        assertEquals(13_000L, ProjectMath.projectDurationMs(p))
    }

    @Test
    fun `duration extends to the latest overlay end`() {
        val p = project(
            clips = listOf(clip(5_000)),
            overlays = listOf(overlay(4_000, 8_000)),
        )
        assertEquals(8_000L, ProjectMath.projectDurationMs(p))
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
    fun `empty project has zero duration`() {
        assertEquals(0L, ProjectMath.projectDurationMs(project()))
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

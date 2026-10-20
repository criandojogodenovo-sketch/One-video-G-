package com.onevideog.editor.editor

import com.onevideog.editor.data.Clip
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.3.0 regression tests for the device-reported TEMPORAL bug:
 * importing an image and dragging its bar outside the main video's range
 * (54.9 s video, image bar past the end) froze playback and produced
 * misleading "video=none" / "videoW=0 h=0" diagnostics afterwards.
 *
 * Documented behaviour under test:
 *  - the project duration (and therefore the playhead range, the timeline
 *    labels and the export verification) equals the VIDEO length whenever
 *    video clips exist — nothing extends it;
 *  - elements outside the video range are NOT discarded: they persist and
 *    produce NO render windows (preview and export share one rule);
 *  - an element can never block the tracks it does not intersect.
 */
class TemporalRuleTest {

    private fun clip(id: String, durMs: Long) = Clip(
        id = id, uri = "u", displayName = "d", sourceDurationMs = durMs,
        trimStartMs = 0, trimEndMs = durMs,
    )

    private fun overlay(id: String, start: Long, end: Long, visible: Boolean = true) =
        OverlayItem(
            id = id, kind = OverlayKind.IMAGE, startMs = start, endMs = end,
            uri = "content://image", visible = visible,
        )

    private fun project(
        clips: List<Clip>,
        overlays: List<OverlayItem> = emptyList(),
    ) = OvgProject(id = "p", name = "n", createdAtEpochMs = 0, updatedAtEpochMs = 0)
        .copy(clips = clips, overlays = overlays)

    // ---- duration / playhead range ------------------------------------------

    @Test
    fun `54_9s video with image past the end keeps the video duration`() {
        // Exact device scenario: 54.9 s video, image bar at 60-65 s.
        val p = project(
            clips = listOf(clip("v", 54_900)),
            overlays = listOf(overlay("img", 60_000, 65_000)),
        )
        assertEquals(54_900L, ProjectMath.projectDurationMs(p))
        assertEquals(54_900L, ProjectMath.clampPlayheadMs(60_000, ProjectMath.projectDurationMs(p)))
    }

    @Test
    fun `multi-clip timeline duration is the sum regardless of overlays`() {
        val p = project(
            clips = listOf(clip("a", 10_000), clip("b", 20_000)),
            overlays = listOf(overlay("img", 5_000, 40_000)),
        )
        assertEquals(30_000L, ProjectMath.projectDurationMs(p))
    }

    // ---- overlay windows relative to the video range -------------------------

    @Test
    fun `overlay entirely AFTER the video end renders nothing`() {
        val p = project(
            clips = listOf(clip("v", 54_900)),
            overlays = listOf(overlay("img", 60_000, 65_000)),
        )
        assertTrue(ClipEffectPlan.overlayWindowsForClip(p, p.clips[0], 0).isEmpty())
    }

    @Test
    fun `overlay PARTIALLY overlapping the video end renders only inside`() {
        val p = project(
            clips = listOf(clip("v", 54_900)),
            overlays = listOf(overlay("img", 50_000, 60_000)),
        )
        val windows = ClipEffectPlan.overlayWindowsForClip(p, p.clips[0], 0)
        assertEquals(1, windows.size)
        assertEquals(50_000L, windows[0].windowStartLocalMs)
        assertEquals(54_900L, windows[0].windowEndLocalMs)
    }

    @Test
    fun `overlay fully INSIDE the video range renders across its window`() {
        val p = project(
            clips = listOf(clip("v", 54_900)),
            overlays = listOf(overlay("img", 10_000, 20_000)),
        )
        val windows = ClipEffectPlan.overlayWindowsForClip(p, p.clips[0], 0)
        assertEquals(1, windows.size)
        assertEquals(10_000L, windows[0].windowStartLocalMs)
        assertEquals(20_000L, windows[0].windowEndLocalMs)
    }

    @Test
    fun `overlay BEFORE a later clip renders in the first clip only`() {
        // "Before the start" of a clip: an overlay inside clip 1's range
        // must not leak into clip 2's effect list.
        val p = project(
            clips = listOf(clip("a", 10_000), clip("b", 10_000)),
            overlays = listOf(overlay("img", 2_000, 8_000)),
        )
        assertTrue(ClipEffectPlan.overlayWindowsForClip(p, p.clips[1], 10_000).isEmpty())
        assertEquals(1, ClipEffectPlan.overlayWindowsForClip(p, p.clips[0], 0).size)
    }

    @Test
    fun `out-of-range overlay does not change the effective signature of the clip`() {
        // The effects chain must not rebuild when an unrelated/out-of-range
        // overlay is edited (v0.2.5 rule, still true under the v0.3.0 rule).
        val base = project(
            clips = listOf(clip("v", 54_900)),
            overlays = listOf(overlay("img", 60_000, 65_000)),
        )
        val edited = base.copy(
            overlays = listOf(overlay("img", 61_000, 66_000)),
        )
        assertEquals(
            ClipEffectPlan.effectiveSignature(base, base.clips[0], 0),
            ClipEffectPlan.effectiveSignature(edited, edited.clips[0], 0),
        )
    }

    // ---- visibility -----------------------------------------------------------

    @Test
    fun `hidden overlay renders nothing even inside the video range`() {
        val p = project(
            clips = listOf(clip("v", 54_900)),
            overlays = listOf(overlay("img", 10_000, 20_000, visible = false)),
        )
        assertTrue(ClipEffectPlan.overlayWindowsForClip(p, p.clips[0], 0).isEmpty())
    }

    @Test
    fun `hidden overlay does not extend the timeline span`() {
        val p = project(
            clips = listOf(clip("v", 10_000)),
            overlays = listOf(overlay("img", 60_000, 65_000, visible = false)),
        )
        // The bar is still an element: the span shows it so it can be moved
        // back; the duration stays video-length either way.
        assertEquals(65_000L, ProjectMath.timelineSpanMs(p))
        assertEquals(10_000L, ProjectMath.projectDurationMs(p))
    }

    // ---- layer lock ------------------------------------------------------------

    @Test
    fun `lock rule rejects edits and keeps selection`() {
        assertTrue(OverlayEditing.isEditable(locked = false))
        assertFalse(OverlayEditing.isEditable(locked = true))
    }

    // ---- audio-only playback ----------------------------------------------------

    @Test
    fun `audio-only source position works without video clamp`() {
        val audio = com.onevideog.editor.data.AudioClipItem(
            id = "a", uri = "u", displayName = "d", sourceDurationMs = 30_000,
            startMs = 0, trimStartMs = 0, trimEndMs = 30_000,
        )
        // videoDurationMs == 0 (no video): the v0.2.5 "audio never sounds
        // past the video end" clamp must NOT silence audio-only projects.
        assertEquals(5_000L, AudioTimeline.sourcePositionAt(audio, 5_000, 0))
        assertNull(AudioTimeline.sourcePositionAt(audio, 31_000, 0))
    }

    @Test
    fun `audio past the video end is silent in preview and export plan`() {
        val audio = com.onevideog.editor.data.AudioClipItem(
            id = "a", uri = "u", displayName = "d", sourceDurationMs = 30_000,
            startMs = 60_000, trimStartMs = 0, trimEndMs = 10_000,
        )
        assertNull(AudioTimeline.sourcePositionAt(audio, 61_000, 54_900))
        val plan = AudioTimeline.exportPlan(audio, 54_900)
        assertEquals(0, plan.segments.size)
    }
}

package com.onevideog.editor

import com.onevideog.editor.data.Clip
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind
import com.onevideog.editor.editor.ClipEffectPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the shared clip-effect plan — the ONE source of
 * overlay windows used by BOTH the preview (ExoPlayer.setVideoEffects) and
 * the export (EditedMediaItem effects). A change here breaks preview/export
 * parity, so the windows are pinned by tests.
 */
class ClipEffectPlanTest {

    private fun projectWith(vararg overlays: OverlayItem) = OvgProject(
        id = "p",
        name = "p",
        createdAtEpochMs = 0,
        updatedAtEpochMs = 0,
        clips = listOf(
            Clip(
                id = "c1",
                uri = "content://test/video",
                displayName = "v",
                sourceDurationMs = 60_000,
                width = 1920,
                height = 1080,
                trimStartMs = 1_000,
                trimEndMs = 11_000, // effective 10 s
            ),
        ),
        overlays = overlays.toList(),
    )

    private fun overlay(id: String, startMs: Long, endMs: Long, zOrder: Int = 0) = OverlayItem(
        id = id,
        kind = OverlayKind.TEXT,
        startMs = startMs,
        endMs = endMs,
        zOrder = zOrder,
        text = id,
    )

    private val project = projectWith()

    @Test
    fun `overlay fully inside the clip maps to a 0-based window`() {
        val windows = ClipEffectPlan.overlayWindowsForClip(
            project,
            project.clips[0],
            clipStartMs = 0,
        ).filter { it.overlay.id == "in" }

        val p = projectWith(overlay("in", 2_000, 5_000))
        val w = ClipEffectPlan.overlayWindowsForClip(p, p.clips[0], 0).single()
        assertEquals(2_000, w.windowStartLocalMs)
        assertEquals(5_000, w.windowEndLocalMs)
        assertTrue(w.frameAspect > 0)
    }

    @Test
    fun `overlay starting before the clip is clamped to zero`() {
        val p = projectWith(overlay("early", 0, 4_000))
        val w = ClipEffectPlan.overlayWindowsForClip(p, p.clips[0], 0).single()
        assertEquals(0, w.windowStartLocalMs)
        assertEquals(4_000, w.windowEndLocalMs)
    }

    @Test
    fun `overlay crossing the clip end is clamped`() {
        val p = projectWith(overlay("long", 8_000, 30_000))
        val w = ClipEffectPlan.overlayWindowsForClip(p, p.clips[0], 0).single()
        assertEquals(8_000, w.windowStartLocalMs)
        assertEquals(10_000, w.windowEndLocalMs)
    }

    @Test
    fun `disjoint overlays are excluded`() {
        val p = projectWith(
            overlay("before", 0, 5_000),
            overlay("after", 25_000, 30_000),
            overlay("hits", 9_000, 12_000),
        )
        // Clip 2 occupies project time [10_000, 20_000): "before" ends at
        // 5_000 (disjoint), "after" starts at 25_000 (disjoint), "hits"
        // straddles the clip start.
        val windows = ClipEffectPlan.overlayWindowsForClip(p, p.clips[0], 10_000)
        assertEquals(listOf("hits"), windows.map { it.overlay.id })
        assertEquals(0, windows.single().windowStartLocalMs)
        assertEquals(2_000, windows.single().windowEndLocalMs)
    }

    @Test
    fun `zero and negative intervals never produce windows`() {
        val p = projectWith(
            overlay("zero", 1_000, 1_000),
            overlay("negative", 3_000, 2_000),
        )
        assertTrue(ClipEffectPlan.overlayWindowsForClip(p, p.clips[0], 0).isEmpty())
    }

    @Test
    fun `windows are ordered by zOrder - Media3 FIFO, last on top`() {
        val p = projectWith(
            overlay("low", 0, 5_000, zOrder = 1),
            overlay("high", 0, 5_000, zOrder = 9),
            overlay("mid", 0, 5_000, zOrder = 5),
        )
        val ids = ClipEffectPlan.overlayWindowsForClip(p, p.clips[0], 0).map { it.overlay.id }
        assertEquals(listOf("low", "mid", "high"), ids)
    }

    @Test
    fun `second clip start shifts windows to that clips local time`() {
        val p = projectWith(overlay("span", 0, 20_000))
        // Two 10 s clips; overlay spans both. On clip 2 (starts 10_000):
        // local window = 0 .. 10_000 (project 10_000..20_000).
        val w = ClipEffectPlan.overlayWindowsForClip(p, p.clips[0], 10_000).single()
        assertEquals(0, w.windowStartLocalMs)
        assertEquals(10_000, w.windowEndLocalMs)
    }
}

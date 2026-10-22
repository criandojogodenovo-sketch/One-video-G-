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

    /**
     * v0.5.8: frameAspect is the OUTPUT frame aspect — for a fixed-aspect
     * project the project ratio (the composition Presentation scales to
     * ExportConfig.outputSize at that ratio and the preview positions
     * overlays against the same ratio), NOT the source clip's aspect. The
     * old source-first order squashed overlays vertically whenever the
     * source aspect differed from the project aspect (exported animated
     * text looked "small with wide letter gaps").
     */
    @Test
    fun `fixed-aspect project uses the project ratio for the frame aspect`() {
        val fixed = projectWith(overlay("t", 2_000, 5_000))
            .copy(aspect = com.onevideog.editor.data.ProjectAspect.R9_16.name)
        val w = ClipEffectPlan.overlayWindowsForClip(fixed, fixed.clips[0], 0).single()
        assertEquals(9f / 16f, w.frameAspect, 1e-6f)
    }

    @Test
    fun `fixed 1x1 project uses 1 for the frame aspect`() {
        val square = projectWith(overlay("t", 2_000, 5_000))
            .copy(aspect = com.onevideog.editor.data.ProjectAspect.R1_1.name)
        val w = ClipEffectPlan.overlayWindowsForClip(square, square.clips[0], 0).single()
        assertEquals(1f, w.frameAspect, 1e-6f)
    }

    @Test
    fun `source project keeps the clip source aspect for the frame aspect`() {
        // projectWith builds a 1920x1080 clip with the default SOURCE aspect:
        // ratio() is null there, so the clip's probed aspect stays authoritative.
        val p = projectWith(overlay("t", 2_000, 5_000))
        val w = ClipEffectPlan.overlayWindowsForClip(p, p.clips[0], 0).single()
        assertEquals(16f / 9f, w.frameAspect, 1e-6f)
    }

    @Test
    fun `clip without probed dimensions falls back to the project ratio then 16x9`() {
        val base = projectWith(overlay("t", 2_000, 5_000))
        val noProbe = base.copy(
            aspect = com.onevideog.editor.data.ProjectAspect.R9_16.name,
            clips = listOf(base.clips[0].copy(width = 0, height = 0)),
        )
        val w = ClipEffectPlan.overlayWindowsForClip(noProbe, noProbe.clips[0], 0).single()
        assertEquals(9f / 16f, w.frameAspect, 1e-6f)
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

    // ---- v0.5.0 (G4): segments from ExpandedOverlays -----------------------

    private fun segment(parentId: String, index: Int, zOrder: Int = 3) = OverlayItem(
        id = "$parentId#s$index",
        kind = OverlayKind.TEXT,
        startMs = 2_000,
        endMs = 5_000,
        zOrder = zOrder,
        text = "seg$index",
    )

    @Test
    fun `segments inherit the parent window and keep the reading order`() {
        val segments = listOf(
            segment("p", 0),
            segment("p", 1),
            segment("p", 2),
        )
        val windows = ClipEffectPlan.overlayWindowsForClip(
            overlays = segments,
            clipEndMs = 10_000,
            frameAspect = 16f / 9f,
            clipStartMs = 0,
        )
        // Same window as the parent, one per segment, in reading order
        // (stable sort with equal zOrder — Media3 draws the last on top,
        // so first-word-first is preserved).
        assertEquals(listOf("p#s0", "p#s1", "p#s2"), windows.map { it.overlay.id })
        windows.forEach {
            assertEquals(2_000, it.windowStartLocalMs)
            assertEquals(5_000, it.windowEndLocalMs)
        }
    }

    @Test
    fun `segments clamp to the clip exactly like their parent`() {
        val segments = listOf(segment("p", 0), segment("p", 1))
        // Clip starts at 3_000 (project): window clamps to local 0..2_000.
        val windows = ClipEffectPlan.overlayWindowsForClip(
            overlays = segments,
            clipEndMs = 13_000,
            frameAspect = 16f / 9f,
            clipStartMs = 3_000,
        )
        assertEquals(2, windows.size)
        windows.forEach {
            assertEquals(0, it.windowStartLocalMs)
            assertEquals(2_000, it.windowEndLocalMs)
        }
    }

    @Test
    fun `hidden segments produce no window like hidden overlays`() {
        val hidden = segment("p", 0).copy(visible = false)
        val shown = segment("p", 1)
        val windows = ClipEffectPlan.overlayWindowsForClip(
            overlays = listOf(hidden, shown),
            clipEndMs = 10_000,
            frameAspect = 16f / 9f,
        )
        assertEquals(listOf("p#s1"), windows.map { it.overlay.id })
    }

    @Test
    fun `segments of different parents interleave by zOrder`() {
        val windows = ClipEffectPlan.overlayWindowsForClip(
            overlays = listOf(
                segment("a", 0, zOrder = 1),
                segment("b", 0, zOrder = 0),
                segment("b", 1, zOrder = 0),
            ),
            clipEndMs = 10_000,
            frameAspect = 16f / 9f,
        )
        // zOrder 0 group keeps its internal reading order.
        assertEquals(listOf("b#s0", "b#s1", "a#s0"), windows.map { it.overlay.id })
    }
}

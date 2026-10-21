package com.onevideog.editor.editor

import com.onevideog.editor.data.Interpolation
import com.onevideog.editor.data.Keyframe
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind
import com.onevideog.editor.data.OverlayTransform
import com.onevideog.editor.data.TextAnimPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.5.0 (Fase G3) — pure entrance-animation planning: stagger fits the
 * overlay window, holds behave through the SAME [KeyframeEvaluator] the
 * pipelines use, TYPEWRITER is a step, POP overshoots and settles.
 * Also covers the pure composition ([ExpandedOverlays.composeStatic] /
 * [ExpandedOverlays.combineTransform]) that turns parent transform +
 * relative entrance into the composed per-segment keyframes.
 */
class TextAnimationPlannerTest {

    // ---- stagger inside the window -------------------------------------------

    @Test
    fun `stagger spreads entrances so the last one ends inside the window`() {
        val schedules = TextAnimationPlanner.plan(
            TextAnimPreset.FADE, durationMs = 600,
            windowStartMs = 1_000, windowEndMs = 4_000, segmentCount = 3,
        )
        assertEquals(3, schedules.size)
        // Slack = 3000-600 = 2400; step = 1200.
        assertEquals(1_000L, schedules[0].startMs)
        assertEquals(2_200L, schedules[1].startMs)
        assertEquals(3_400L, schedules[2].startMs)
        for (s in schedules) {
            val end = s.keyframes.maxOf { it.timeMs }
            assertTrue("entrance must fit the window", end <= 4_000)
        }
    }

    @Test
    fun `single segment starts at the window start`() {
        val schedules = TextAnimationPlanner.plan(
            TextAnimPreset.FADE, 600, 2_000, 8_000, segmentCount = 1,
        )
        assertEquals(1, schedules.size)
        assertEquals(2_000L, schedules[0].startMs)
    }

    @Test
    fun `window shorter than the duration clamps the entrance inside it`() {
        val schedules = TextAnimationPlanner.plan(
            TextAnimPreset.FADE, 5_000, 1_000, 2_000, segmentCount = 2,
        )
        for (s in schedules) {
            assertTrue(s.keyframes.last().timeMs <= 2_000)
            assertTrue(s.startMs >= 1_000)
        }
    }

    @Test
    fun `no segments or NONE preset produce nothing`() {
        assertTrue(
            TextAnimationPlanner.plan(TextAnimPreset.NONE, 600, 0, 1_000, 3).isEmpty(),
        )
        assertTrue(
            TextAnimationPlanner.plan(TextAnimPreset.FADE, 600, 0, 1_000, 0).isEmpty(),
        )
    }

    // ---- preset shapes ---------------------------------------------------------

    @Test
    fun `fade is opacity 0 to 1`() {
        val kfs = TextAnimationPlanner.entranceKeyframes(TextAnimPreset.FADE, 500, 600)
        assertEquals(2, kfs.size)
        assertEquals(0f, kfs[0].opacity!!, 1e-6f)
        assertEquals(1f, kfs[1].opacity!!, 1e-6f)
        assertEquals(1_100L, kfs[1].timeMs)
    }

    @Test
    fun `slide up animates the y delta from below with opacity`() {
        val kfs = TextAnimationPlanner.entranceKeyframes(TextAnimPreset.SLIDE_UP, 0, 600)
        assertEquals(2, kfs.size)
        assertEquals(TextAnimationPlanner.SLIDE_DELTA_Y, kfs[0].y!!, 1e-6f)
        assertEquals(0f, kfs[0].opacity!!, 1e-6f)
        assertEquals(0f, kfs[1].y!!, 1e-6f)
        assertEquals(1f, kfs[1].opacity!!, 1e-6f)
    }

    @Test
    fun `pop overshoots to the peak and settles`() {
        val kfs = TextAnimationPlanner.entranceKeyframes(TextAnimPreset.POP, 0, 600)
        assertEquals(3, kfs.size)
        assertEquals(TextAnimationPlanner.POP_START, kfs[0].scale!!, 1e-6f)
        assertEquals(TextAnimationPlanner.POP_PEAK, kfs[1].scale!!, 1e-6f)
        assertEquals(1f, kfs[2].scale!!, 1e-6f)
        assertEquals(0f, kfs[0].opacity!!, 1e-6f)
        assertEquals(1f, kfs[1].opacity!!, 1e-6f)
    }

    @Test
    fun `typewriter is a 1 ms step`() {
        val kfs = TextAnimationPlanner.entranceKeyframes(TextAnimPreset.TYPEWRITER, 700, 600)
        assertEquals(2, kfs.size)
        assertEquals(700L, kfs[0].timeMs)
        assertEquals(701L, kfs[1].timeMs)
        assertEquals(0f, kfs[0].opacity!!, 1e-6f)
        assertEquals(1f, kfs[1].opacity!!, 1e-6f)
    }

    // ---- holds through the shared evaluator ------------------------------------

    private fun synthetic(keyframes: List<Keyframe>) = OverlayItem(
        id = "rel",
        kind = OverlayKind.TEXT,
        transform = TextAnimationPlanner.identityRelative(),
        keyframes = keyframes,
        interpolation = Interpolation.LINEAR,
    )

    @Test
    fun `segment is invisible before its entrance and holds after it`() {
        val kfs = TextAnimationPlanner.entranceKeyframes(TextAnimPreset.FADE, 1_000, 600)
        val rel = synthetic(kfs)
        // Before the start: hold on the first keyframe (opacity 0).
        assertEquals(0f, KeyframeEvaluator.evaluate(rel, 500).opacity, 1e-4f)
        assertEquals(0f, KeyframeEvaluator.evaluate(rel, 1_000).opacity, 1e-4f)
        // Mid-transition: half-way linear.
        assertEquals(0.5f, KeyframeEvaluator.evaluate(rel, 1_300).opacity, 1e-2f)
        // After the end: hold (final state until the window ends).
        assertEquals(1f, KeyframeEvaluator.evaluate(rel, 2_000).opacity, 1e-4f)
        assertEquals(1f, KeyframeEvaluator.evaluate(rel, 9_000).opacity, 1e-4f)
    }

    @Test
    fun `typewriter holds both before and after the 1 ms step`() {
        val rel = synthetic(
            TextAnimationPlanner.entranceKeyframes(TextAnimPreset.TYPEWRITER, 1_000, 600),
        )
        assertEquals(0f, KeyframeEvaluator.evaluate(rel, 999).opacity, 1e-4f)
        assertEquals(1f, KeyframeEvaluator.evaluate(rel, 1_100).opacity, 1e-4f)
    }

    // ---- pure composition (ExpandedOverlays) ------------------------------------

    @Test
    fun `composeStatic offsets the segment by its block-frame position`() {
        val parent = OverlayTransform(x = 0.5f, y = 0.5f, scale = 0.5f)
        val rel = ExpandedOverlays.RelGeometry(
            centerXFrac = 0.25f, centerYFrac = 0.5f,
            widthFrac = 0.2f, blockAspectW = 16f / 9f,
        )
        val out = ExpandedOverlays.composeStatic(parent, rel, contentAspect = 16f / 9f)
        // dx = (0.25-0.5)*0.5 = -0.125; dy = 0 (centred vertically).
        assertEquals(0.375f, out.x, 1e-4f)
        assertEquals(0.5f, out.y, 1e-4f)
        assertEquals(0.1f, out.scale, 1e-4f) // 0.5 * 0.2
        assertEquals(0f, out.rotationDeg, 1e-4f)
        assertEquals(1f, out.opacity, 1e-4f)
    }

    @Test
    fun `composeStatic rotates the offset with the parent (clockwise, y-down)`() {
        val parent = OverlayTransform(x = 0.5f, y = 0.5f, scale = 0.5f, rotationDeg = 90f)
        val rel = ExpandedOverlays.RelGeometry(0.25f, 0.5f, 0.2f, 16f / 9f)
        val out = ExpandedOverlays.composeStatic(parent, rel, contentAspect = 16f / 9f)
        // A point LEFT of the centre rotates UP under clockwise rotation in
        // y-down screen space (same convention as the preview canvas).
        assertEquals(0.5f, out.x, 1e-4f)
        assertEquals(0.375f, out.y, 1e-4f)
        assertEquals(90f, out.rotationDeg, 1e-4f)
    }

    @Test
    fun `combineTransform applies deltas and factors`() {
        val static = OverlayTransform(x = 0.5f, y = 0.5f, scale = 0.2f, opacity = 0.8f)
        val relative = OverlayTransform(x = 0f, y = 0.06f, scale = 0.6f, opacity = 0f)
        val out = ExpandedOverlays.combineTransform(static, relative)
        assertEquals(0.5f, out.x, 1e-6f)
        assertEquals(0.56f, out.y, 1e-4f)
        assertEquals(0.12f, out.scale, 1e-4f)
        assertEquals(0f, out.opacity, 1e-6f)
    }

    @Test
    fun `segment ids are deterministic and mappable`() {
        assertEquals("ov#s3", ExpandedOverlays.segmentId("ov", 3))
        assertTrue(ExpandedOverlays.isSegmentId("ov#s3"))
        assertTrue(!ExpandedOverlays.isSegmentId("ov"))
        assertEquals("ov", ExpandedOverlays.parentIdOf("ov#s3"))
    }

    @Test
    fun `composed keyframes follow the parent animation at authored times`() {
        // Parent fades its own opacity 0->1 between 0 and 1000 (authored kf);
        // a FADE entrance for 1 segment starting at 0 with duration 600.
        val parent = OverlayItem(
            id = "p",
            kind = OverlayKind.TEXT,
            startMs = 0,
            endMs = 2_000,
            text = "Oi",
            transform = OverlayTransform(x = 0.5f, y = 0.5f),
            keyframes = listOf(Keyframe(timeMs = 0, opacity = 0f), Keyframe(timeMs = 1_000, opacity = 1f)),
        )
        val schedules = TextAnimationPlanner.plan(
            TextAnimPreset.FADE, 600, parent.startMs, parent.endMs, segmentCount = 1,
        )
        val rel = ExpandedOverlays.RelGeometry(0.5f, 0.5f, 0.5f, 16f / 9f)
        // Compose at the parent's authored time 1000: parent opacity = 1,
        // entrance finished -> segment opacity 1. At t=0: parent 0 * entrance 0 = 0.
        val synthetic = synthetic(schedules[0].keyframes)
        val at0 = ExpandedOverlays.combineTransform(
            ExpandedOverlays.composeStatic(
                KeyframeEvaluator.evaluate(parent, 0), rel, 16f / 9f,
            ),
            KeyframeEvaluator.evaluate(synthetic, 0),
        )
        assertEquals(0f, at0.opacity, 1e-4f)
        val at1000 = ExpandedOverlays.combineTransform(
            ExpandedOverlays.composeStatic(
                KeyframeEvaluator.evaluate(parent, 1_000), rel, 16f / 9f,
            ),
            KeyframeEvaluator.evaluate(synthetic, 1_000),
        )
        assertEquals(1f, at1000.opacity, 1e-4f)
    }
}

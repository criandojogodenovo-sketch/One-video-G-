package com.onevideog.editor.editor

import com.onevideog.editor.data.Clip
import com.onevideog.editor.data.ClipEffect
import com.onevideog.editor.data.ColorCurves
import com.onevideog.editor.data.Curve
import com.onevideog.editor.data.CurvePoint
import com.onevideog.editor.data.EffectKind
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.SpeedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.7.0 (J4) — the factory-level contract:
 *  - the canonical PIPELINE order is locked as data next to the builder:
 *    speed → color → curves → 2D effects → transitions → overlays;
 *  - curves = null / all-identity adds NO effect (J3 no-GPU-cost rule);
 *  - effectiveSignature: curves and speedRamps ARE inputs (decoder
 *    re-prepares — same class as effects/transitions); overlays and the
 *    playhead are still NOT (v0.3.1 contract intact).
 */
class ClipEffectsFactoryTest {

    private fun clip(
        id: String = "c1",
        curves: ColorCurves? = null,
        ramps: List<SpeedPoint> = emptyList(),
        effects: List<ClipEffect> = emptyList(),
    ) = Clip(
        id = id, uri = "u", displayName = "d", sourceDurationMs = 10_000,
        trimStartMs = 0, trimEndMs = 10_000,
        curves = curves, speedRamps = ramps, effects = effects,
    )

    private fun project(clip: Clip) =
        OvgProject(id = "p", name = "n", createdAtEpochMs = 0, updatedAtEpochMs = 0)
            .copy(clips = listOf(clip))

    private fun nonLinear() = Curve(listOf(CurvePoint(0f, 0.05f), CurvePoint(1f, 0.95f)))

    private fun curves(master: Curve = nonLinear()) = ColorCurves(
        master = master,
        red = Curve(listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))),
        green = Curve(listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))),
        blue = Curve(listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))),
    )

    // ---------------------------------------------------------- pipeline order

    @Test
    fun `the canonical pipeline order is speed camera color curves effects2d transitions overlays`() {
        // v0.9.0 (Fase L2): the 2.5D camera sits right after speed — it
        // reframes the footage; colour/curves/2D effects/transitions then
        // apply to the camera-framed frame, identically in both pipelines.
        assertEquals(
            listOf("speed", "camera", "color", "curves", "effects2d", "transitions", "overlays"),
            ClipEffectsFactory.PIPELINE_ORDER,
        )
    }

    @Test
    fun `curves null and identity add no effect instance`() {
        assertTrue(ColorCurvesEffects.effectsFor(null).isEmpty())
        assertTrue(ColorCurvesEffects.effectsFor(curves(Curve(listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))))).isEmpty())
        // One non-identity channel: exactly ONE effect (the whole curves pass).
        assertEquals(1, ColorCurvesEffects.effectsFor(curves()).size)
        assertEquals(1, ColorCurvesEffects.effectsFor(curves(master = nonLinear())).size)
    }

    // ------------------------------------------------------ effectiveSignature

    @Test
    fun `setting curves changes the signature`() {
        val base = project(clip())
        val withCurves = project(clip(curves = curves()))
        assertNotEquals(
            ClipEffectPlan.effectiveSignature(base, base.clips[0], 0),
            ClipEffectPlan.effectiveSignature(withCurves, withCurves.clips[0], 0),
        )
    }

    @Test
    fun `changing a curve point changes the signature`() {
        val first = project(clip(curves = curves(master = nonLinear())))
        val second = project(
            clip(curves = curves(master = Curve(listOf(CurvePoint(0f, 0.1f), CurvePoint(1f, 0.95f))))),
        )
        assertNotEquals(
            ClipEffectPlan.effectiveSignature(first, first.clips[0], 0),
            ClipEffectPlan.effectiveSignature(second, second.clips[0], 0),
        )
    }

    @Test
    fun `setting speed ramps changes the signature`() {
        val base = project(clip())
        val withRamp = project(
            clip(ramps = listOf(SpeedPoint(0, 1f), SpeedPoint(10_000, 0.5f))),
        )
        val changedRamp = project(
            clip(ramps = listOf(SpeedPoint(0, 1f), SpeedPoint(10_000, 0.4f))),
        )
        assertNotEquals(
            ClipEffectPlan.effectiveSignature(base, base.clips[0], 0),
            ClipEffectPlan.effectiveSignature(withRamp, withRamp.clips[0], 0),
        )
        assertNotEquals(
            ClipEffectPlan.effectiveSignature(withRamp, withRamp.clips[0], 0),
            ClipEffectPlan.effectiveSignature(changedRamp, changedRamp.clips[0], 0),
        )
    }

    @Test
    fun `clearing curves and ramps returns to the base signature`() {
        val base = project(clip())
        val decorated = project(
            clip(
                curves = curves(),
                ramps = listOf(SpeedPoint(0, 1f), SpeedPoint(10_000, 0.5f)),
                effects = listOf(ClipEffect(EffectKind.BLUR, 0.5f)),
            ),
        )
        val cleared = project(clip())
        assertEquals(
            ClipEffectPlan.effectiveSignature(base, base.clips[0], 0),
            ClipEffectPlan.effectiveSignature(cleared, cleared.clips[0], 0),
        )
        assertNotEquals(
            ClipEffectPlan.effectiveSignature(base, base.clips[0], 0),
            ClipEffectPlan.effectiveSignature(decorated, decorated.clips[0], 0),
        )
    }

    @Test
    fun `playhead and overlay edits still never change the signature`() {
        val base = project(clip())
        assertEquals(
            ClipEffectPlan.effectiveSignature(base, base.clips[0], 0),
            ClipEffectPlan.effectiveSignature(base, base.clips[0], 9_999),
        )
    }

    // ------------------------------------------------- ramp-aware integrations

    @Test
    fun `overlay windows and transitions follow the ramped footprint`() {
        // 10 s source at a constant 0.5x ramp -> output 20 s; an overlay
        // spanning far beyond the clip must be clipped to the RAMPED end
        // (20 s), not the 10 s basis end.
        val c = clip(
            ramps = listOf(SpeedPoint(0, 0.5f), SpeedPoint(10_000, 0.5f)),
        )
        val p = project(c)
        val windows = ClipEffectPlan.overlayWindowsForClip(
            overlays = listOf(
                com.onevideog.editor.data.OverlayItem(
                    id = "o1",
                    kind = com.onevideog.editor.data.OverlayKind.TEXT,
                    startMs = 0,
                    endMs = 100_000, // far beyond the clip
                    text = "x",
                ),
            ),
            clipEndMs = 0L + SpeedRampPlanner.outputDurationMs(c),
            frameAspect = 16f / 9f,
            clipStartMs = 0L,
        )
        assertEquals(1, windows.size)
        assertEquals(SpeedRampPlanner.outputDurationMs(c), windows[0].windowEndLocalMs)
        assertTrue(windows[0].windowEndLocalMs > c.effectiveDurationMs)
        // The transition 50% rule also reads the ramped footprint: a 6 s
        // window exceeds 50% of the ramped clip (20 s / 2 = 10 s).
        val other = clip(id = "c2")
        val t = com.onevideog.editor.data.ClipTransition(
            id = "t",
            betweenClipIds = "c1" to "c2",
            durationMs = 6_000,
        )
        assertEquals(
            TransitionPlanner.InvalidReason.DURATION_EXCEEDS_HALF,
            TransitionPlanner.validate(listOf(c, other), t),
        )
    }

    // -------------------------------------------------- v0.9.0 camera (L2)

    @Test
    fun `setting the camera changes the signature - it is a GL input`() {
        val base = project(clip())
        val withCamera = base.copy(
            camera = com.onevideog.editor.data.CameraState(panX = 0.1f, zoom = 1.5f),
        )
        assertNotEquals(
            ClipEffectPlan.effectiveSignature(base, base.clips[0], 0),
            ClipEffectPlan.effectiveSignature(withCamera, withCamera.clips[0], 0),
        )
        // Clearing returns to the base.
        assertEquals(
            ClipEffectPlan.effectiveSignature(base, base.clips[0], 0),
            ClipEffectPlan.effectiveSignature(
                withCamera.copy(camera = com.onevideog.editor.data.CameraState()),
                withCamera.clips[0],
                0,
            ),
        )
    }

    @Test
    fun `overlay styles and depth stay OUTSIDE the signature - v0_3_1 intact`() {
        val base = project(clip())
        val styled = base.copy(
            overlays = listOf(
                com.onevideog.editor.data.OverlayItem(
                    id = "o1",
                    kind = com.onevideog.editor.data.OverlayKind.TEXT,
                    startMs = 0,
                    endMs = 5_000,
                    text = "x",
                    zDepth = -0.5f,
                    style = com.onevideog.editor.data.OverlayStylePresets.SOFT,
                ),
            ),
        )
        assertEquals(
            ClipEffectPlan.effectiveSignature(base, base.clips[0], 0),
            ClipEffectPlan.effectiveSignature(styled, styled.clips[0], 0),
        )
    }

    @Test
    fun `depth defines the draw order - zOrder breaks ties (D2)`() {
        val front = com.onevideog.editor.data.OverlayItem(
            id = "front", kind = com.onevideog.editor.data.OverlayKind.TEXT,
            startMs = 0, endMs = 5_000, text = "front", zOrder = 0, zDepth = 1f,
        )
        val back = com.onevideog.editor.data.OverlayItem(
            id = "back", kind = com.onevideog.editor.data.OverlayKind.TEXT,
            startMs = 0, endMs = 5_000, text = "back", zOrder = 9, zDepth = -1f,
        )
        val tieA = com.onevideog.editor.data.OverlayItem(
            id = "tieA", kind = com.onevideog.editor.data.OverlayKind.TEXT,
            startMs = 0, endMs = 5_000, text = "a", zOrder = 2,
        )
        val tieB = com.onevideog.editor.data.OverlayItem(
            id = "tieB", kind = com.onevideog.editor.data.OverlayKind.TEXT,
            startMs = 0, endMs = 5_000, text = "b", zOrder = 5,
        )
        val windows = ClipEffectPlan.overlayWindowsForClip(
            overlays = listOf(front, back, tieB, tieA),
            clipEndMs = 5_000,
            frameAspect = 16f / 9f,
        )
        // Deeper first (regardless of zOrder), then zOrder within a depth.
        assertEquals(
            listOf("back", "tieA", "tieB", "front"),
            windows.map { it.overlay.id },
        )
    }
}

package com.onevideog.editor.editor

import com.onevideog.editor.data.CameraKeyframe
import com.onevideog.editor.data.CameraState
import com.onevideog.editor.data.Interpolation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.0 (Fase L, L5) — the pure depth-camera contract:
 *  1. zDepth 0 (and the default camera) is the EXACT identity — every v5
 *     document and every plain overlay renders the v0.8.1 numbers;
 *  2. parallax is PROPORTIONAL to the depth (linear);
 *  3. camera keyframes interpolate (hold before/after, lerp/ease between);
 *  4. the D2 draw-order comparator: deeper first, zOrder tie-break.
 */
class CameraMathTest {

    private val identity = CameraState()

    // ------------------------------------------------------------- identity

    @Test
    fun `default camera is the identity for every depth`() {
        val evaluated = CameraMath.evaluate(identity, 0L)
        assertEquals(0f, evaluated.panX, 1e-6f)
        assertEquals(0f, evaluated.panY, 1e-6f)
        assertEquals(1f, evaluated.zoom, 1e-6f)
        for (depth in listOf(-1f, -0.5f, 0f, 0.42f, 1f)) {
            val affine = CameraMath.affineForDepth(evaluated, depth)
            assertEquals(0f, affine.shiftXFrac, 1e-6f)
            assertEquals(0f, affine.shiftYFrac, 1e-6f)
            assertEquals(1f, affine.scaleMul, 1e-6f)
            assertEquals(CameraMath.DepthAffine.IDENTITY, affine)
        }
    }

    @Test
    fun `zDepth zero is the exact identity under any camera`() {
        val camera = CameraState(panX = 0.8f, panY = -0.6f, zoom = 2.5f)
        val evaluated = CameraMath.evaluate(camera, 1_234L)
        assertEquals(CameraMath.DepthAffine.IDENTITY, CameraMath.affineForDepth(evaluated, 0f))
        // Non-finite depths count as 0 — a corrupt value can never move a layer.
        assertEquals(
            CameraMath.DepthAffine.IDENTITY,
            CameraMath.affineForDepth(evaluated, Float.NaN),
        )
    }

    // ------------------------------------------------------------- parallax

    @Test
    fun `parallax is proportional to the depth (linear)`() {
        val camera = CameraState(panX = 1f, panY = 0.5f, zoom = 2f)
        val evaluated = CameraMath.evaluate(camera, 0L)
        val quarter = CameraMath.affineForDepth(evaluated, 0.25f)
        val half = CameraMath.affineForDepth(evaluated, 0.5f)
        val full = CameraMath.affineForDepth(evaluated, 1f)
        // Shifts scale linearly with d.
        assertEquals(quarter.shiftXFrac * 2f, half.shiftXFrac, 1e-5f)
        assertEquals(quarter.shiftXFrac * 4f, full.shiftXFrac, 1e-5f)
        assertEquals(1f * CameraMath.PAN_GAIN, full.shiftXFrac, 1e-5f)
        assertEquals(0.5f * CameraMath.PAN_GAIN, full.shiftYFrac, 1e-5f)
        // Scale multiplier: 1 + (zoom-1)*d*ZOOM_GAIN = 1 + 1*d*0.35.
        assertEquals(1.175f, half.scaleMul, 1e-5f)
        assertEquals(1.35f, full.scaleMul, 1e-5f)
        // Closer layers (negative depth) move the opposite way and shrink
        // under zoom-in relative to the plane.
        val close = CameraMath.affineForDepth(evaluated, -1f)
        assertEquals(-1f * CameraMath.PAN_GAIN, close.shiftXFrac, 1e-5f)
        assertEquals(1f - CameraMath.ZOOM_GAIN, close.scaleMul, 1e-5f)
    }

    @Test
    fun `shifts coerce to the frame edge exactly like the export anchor`() {
        val affine = CameraMath.affineForDepth(
            CameraMath.evaluate(CameraState(panX = 1f), 0L),
            1f,
        )
        // x = 0.9 + 0.25 = 1.15 -> coerced to 1.0 (the NDC anchor clamp twin).
        assertEquals(1f, CameraMath.applyShift(0.9f, affine.shiftXFrac), 1e-6f)
        // x = -0.2 + 0.25 = 0.05 — inside the frame, no coercion.
        assertEquals(0.05f, CameraMath.applyShift(-0.2f, affine.shiftXFrac), 1e-6f)
        // x = -0.4 + 0.25 = -0.15 -> coerced to 0.0.
        assertEquals(0f, CameraMath.applyShift(-0.4f, affine.shiftXFrac), 1e-6f)
        assertEquals(0.25f, CameraMath.applyShift(0f, affine.shiftXFrac), 1e-6f)
    }

    @Test
    fun `depths and ranges coerce to the documented limits`() {
        val camera = CameraState(panX = 5f, panY = -9f, zoom = 99f)
        val evaluated = CameraMath.evaluate(camera, 0L)
        assertEquals(CameraMath.PAN_MAX, evaluated.panX, 1e-6f)
        assertEquals(CameraMath.PAN_MIN, evaluated.panY, 1e-6f)
        assertEquals(CameraMath.ZOOM_MAX, evaluated.zoom, 1e-6f)
        // zDepth beyond ±1 clamps.
        val full = CameraMath.affineForDepth(evaluated, 7f)
        assertEquals(1f * CameraMath.PAN_GAIN, full.shiftXFrac, 1e-5f)
        // Scale multiplier floor never collapses: the worst case in range
        // (zoom 3, d = -1) still leaves 1 - 2*0.35 = 0.30.
        val closest = CameraMath.affineForDepth(
            CameraMath.evaluate(CameraState(zoom = 3f), 0L),
            -1f,
        )
        assertEquals(0.3f, closest.scaleMul, 1e-6f)
        // The floor only exists for out-of-range input (defence in depth).
        assertEquals(CameraMath.MIN_SCALE_MUL, CameraMath.applyScale(0.1f, CameraMath.DepthAffine(0f, 0f, CameraMath.MIN_SCALE_MUL)) / 0.1f, 1e-6f)
    }

    // ------------------------------------------------------------ keyframes

    @Test
    fun `camera keyframes interpolate and hold at the edges`() {
        val camera = CameraState(
            panX = 0f, panY = 0f, zoom = 1f,
            keyframes = listOf(
                CameraKeyframe(timeMs = 1_000, panX = 0f, zoom = 1f),
                CameraKeyframe(timeMs = 3_000, panX = 1f, zoom = 3f),
            ),
        )
        // Hold before the first keyframe.
        assertEquals(0f, CameraMath.evaluate(camera, 0L).panX, 1e-6f)
        // Linear midpoint.
        val mid = CameraMath.evaluate(camera, 2_000L)
        assertEquals(0.5f, mid.panX, 1e-5f)
        assertEquals(2f, mid.zoom, 1e-5f)
        // Hold after the last keyframe.
        assertEquals(1f, CameraMath.evaluate(camera, 9_000L).panX, 1e-6f)
        assertEquals(3f, CameraMath.evaluate(camera, 9_000L).zoom, 1e-6f)
        // Ease-in-out midpoint = smoothstep(0.5) = 0.5 (symmetric), and the
        // quarter point differs from linear (curve actually applied).
        val eased = camera.copy(interpolation = Interpolation.EASE_IN_OUT)
        assertEquals(0.5f, CameraMath.evaluate(eased, 2_000L).panX, 1e-5f)
        val quarterEased = CameraMath.evaluate(eased, 1_500L).panX
        val quarterLinear = CameraMath.evaluate(camera, 1_500L).panX
        assertTrue(quarterEased < quarterLinear) // smoothstep starts slow
    }

    @Test
    fun `null keyframe properties inherit the base value`() {
        val camera = CameraState(
            panX = 0.3f, panY = -0.2f, zoom = 2f,
            keyframes = listOf(
                CameraKeyframe(timeMs = 500, zoom = 2.5f), // panX/panY null
                CameraKeyframe(timeMs = 1_500, zoom = 2.5f),
            ),
        )
        val at = CameraMath.evaluate(camera, 1_000L)
        assertEquals(0.3f, at.panX, 1e-6f) // base inherited
        assertEquals(-0.2f, at.panY, 1e-6f)
        assertEquals(2.5f, at.zoom, 1e-6f) // keyframed
    }

    // ---------------------------------------------------------- draw order

    @Test
    fun `draw order is deeper-first with zOrder as tie-break (D2)`() {
        fun ov(id: String, zDepth: Float, zOrder: Int) =
            com.onevideog.editor.data.OverlayItem(
                id = id, kind = com.onevideog.editor.data.OverlayKind.TEXT,
                zDepth = zDepth, zOrder = zOrder,
            )
        val sorted = listOf(
            ov("front", -0.5f, 3),
            ov("plane-z1", 0f, 1),
            ov("deep-z9", 0.8f, 9),
            ov("plane-z2", 0f, 2),
            ov("deep-z1", 0.8f, 1),
        ).sortedWith(CameraMath.drawOrderComparator)
        assertEquals(
            listOf("deep-z1", "deep-z9", "plane-z1", "plane-z2", "front"),
            sorted.map { it.id },
        )
        // Non-finite depth sorts as 0.
        val withNan = listOf(
            ov("nan", Float.NaN, 5),
            ov("plane", 0f, 1),
        ).sortedWith(CameraMath.drawOrderComparator)
        assertEquals(listOf("plane", "nan"), withNan.map { it.id })
    }

    // ------------------------------------------------ L2b spike model (D4)

    @Test
    fun `plane affine is the full camera (no depth attenuation) - spike model`() {
        val evaluated = CameraMath.evaluate(CameraState(panX = 0.4f, zoom = 1.5f), 0L)
        val plane = CameraMath.planeAffine(evaluated)
        assertEquals(0.4f * CameraMath.PAN_GAIN, plane.shiftXFrac, 1e-6f)
        assertEquals(1.5f, plane.scaleMul, 1e-6f)
        // And the identity camera keeps the plane untouched.
        assertEquals(
            CameraMath.DepthAffine.IDENTITY,
            CameraMath.planeAffine(CameraMath.evaluate(CameraState(), 0L)),
        )
    }
}

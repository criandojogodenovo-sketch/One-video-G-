package com.onevideog.editor.editor

import com.onevideog.editor.data.CameraKeyframe
import com.onevideog.editor.data.CameraState
import com.onevideog.editor.data.Interpolation
import com.onevideog.editor.data.OverlayTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.0 (Fase L2) — the pure 2.5D camera math contract:
 *  - zDepth 0 + identity camera = exact identity (the pre-v0.9.0 behaviour);
 *  - parallax is proportional to depth (deeper moves less, foreground more);
 *  - keyframes interpolate with the project curve and hold at the ends;
 *  - the video-plane matrix and the overlay path carry the SAME values.
 */
class CameraMathTest {

    // ------------------------------------------------------------- identity

    @Test
    fun `identity camera and depth zero are the exact identity`() {
        val t = OverlayTransform(x = 0.37f, y = 0.71f, scale = 0.42f, rotationDeg = 17f, opacity = 0.8f)
        val mapped = CameraMath.applyToTransform(CameraMath.IDENTITY_POSE, 0f, t)
        assertEquals(t, mapped)
    }

    @Test
    fun `default camera state is identity`() {
        assertTrue(CameraMath.isIdentity(CameraState()))
        assertTrue(!CameraMath.isIdentity(CameraState(zoom = 1.5f)))
        // An all-identity keyframe track is still the identity camera.
        assertTrue(
            CameraMath.isIdentity(
                CameraState(
                    keyframes = listOf(
                        CameraKeyframe(0, 0f, 0f, 1f),
                        CameraKeyframe(1000, 0f, 0f, 1f),
                    ),
                ),
            ),
        )
    }

    // ------------------------------------------------------------- parallax

    @Test
    fun `depth zero rides the video plane - the plane affine equals the matrix`() {
        val pose = CameraMath.Pose(panX = 0.15f, panY = -0.1f, zoom = 2f)
        val d = CameraMath.affineForDepth(pose, 0f)
        val m = CameraMath.videoPlaneMatrixValues(pose)
        // android Matrix values order: [MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y, MSCALE_Y, MTRANS_Y, ..]
        assertEquals(d.zoom, m[0], 1e-6f)
        assertEquals(0f, m[1], 1e-6f)
        assertEquals(d.ndcShiftX, m[2], 1e-6f)
        assertEquals(0f, m[3], 1e-6f)
        assertEquals(d.zoom, m[4], 1e-6f)
        assertEquals(d.ndcShiftY, m[5], 1e-6f)
        // Affine bottom row.
        assertEquals(0f, m[6], 1e-6f)
        assertEquals(0f, m[7], 1e-6f)
        assertEquals(1f, m[8], 1e-6f)
    }

    @Test
    fun `parallax is proportional to depth - deeper moves half, foreground one and a half`() {
        val pose = CameraMath.Pose(panX = 0.2f, panY = 0f, zoom = 1.8f)
        val plane = CameraMath.affineForDepth(pose, 0f)
        val deep = CameraMath.affineForDepth(pose, -1f)
        val near = CameraMath.affineForDepth(pose, 1f)

        assertEquals(pose.zoom, plane.zoom, 1e-6f)
        // Depth -1: multiplier 0.5 — half the pan, half the zoom DELTA.
        assertEquals(2f * 0.2f * 0.5f, deep.ndcShiftX, 1e-6f)
        assertEquals(1f + (pose.zoom - 1f) * 0.5f, deep.zoom, 1e-6f)
        // Depth +1: multiplier 1.5.
        assertEquals(2f * 0.2f * 1.5f, near.ndcShiftX, 1e-6f)
        assertEquals(1f + (pose.zoom - 1f) * 1.5f, near.zoom, 1e-6f)
    }

    @Test
    fun `pan directions follow the screen convention in the overlay mapping`() {
        // +panX moves content right: the mapped x grows. +panY moves it down:
        // the mapped y grows (content y is DOWN-positive).
        val right = CameraMath.applyToTransform(
            CameraMath.Pose(panX = 0.1f, panY = 0f, zoom = 1f), 0f,
            OverlayTransform(x = 0.5f, y = 0.5f, scale = 0.5f),
        )
        assertTrue(right.x > 0.5f)
        assertEquals(0.5f, right.y, 1e-6f)
        val down = CameraMath.applyToTransform(
            CameraMath.Pose(panX = 0f, panY = 0.1f, zoom = 1f), 0f,
            OverlayTransform(x = 0.5f, y = 0.5f, scale = 0.5f),
        )
        assertTrue(down.y > 0.5f)
        assertEquals(0.5f, down.x, 1e-6f)
    }

    @Test
    fun `zoom scales the overlay with the plane and keeps rotation and opacity`() {
        val pose = CameraMath.Pose(panX = 0f, panY = 0f, zoom = 2f)
        val mapped = CameraMath.applyToTransform(
            pose, 0f,
            OverlayTransform(x = 0.25f, y = 0.75f, scale = 0.3f, rotationDeg = -40f, opacity = 0.7f),
        )
        assertEquals(0.6f, mapped.scale, 1e-5f)
        assertEquals(-40f, mapped.rotationDeg, 1e-5f)
        assertEquals(0.7f, mapped.opacity, 1e-5f)
        // The centre scales about the frame centre in NDC: x=0.25 (left of
        // centre) maps to xNdc −0.5 → 2× → −1 → content x 0; y mirrors.
        assertEquals(0f, mapped.x, 1e-5f)
        assertEquals(1f, mapped.y, 1e-5f)
    }

    @Test
    fun `the camera clamps the mapped position to the content edge - parity with the export anchors`() {
        val mapped = CameraMath.applyToTransform(
            CameraMath.Pose(panX = 0.5f, panY = 0f, zoom = 1f), 0f,
            OverlayTransform(x = 0.9f, y = 0.5f, scale = 0.3f),
        )
        // The export anchors coerce to ±1 NDC (the frame border); the shared
        // math clamps the mapped centre the SAME way so both paths agree.
        assertEquals(1f, mapped.x, 1e-6f)
        assertEquals(0.5f, mapped.y, 1e-6f)
    }

    // ------------------------------------------------------------ keyframes

    @Test
    fun `keyframes interpolate linearly and hold at the ends`() {
        val camera = CameraState(
            keyframes = listOf(
                CameraKeyframe(0, panX = -0.2f, panY = 0.1f, zoom = 1.2f),
                CameraKeyframe(1000, panX = 0.2f, panY = -0.1f, zoom = 2.2f),
            ),
            interpolation = Interpolation.LINEAR,
        )
        val start = CameraMath.evaluate(camera, 0)
        assertEquals(-0.2f, start.panX, 1e-6f)
        assertEquals(1.2f, start.zoom, 1e-6f)
        val mid = CameraMath.evaluate(camera, 500)
        assertEquals(0f, mid.panX, 1e-5f)
        assertEquals(1.7f, mid.zoom, 1e-5f)
        val end = CameraMath.evaluate(camera, 5000)
        assertEquals(0.2f, end.panX, 1e-6f)
        assertEquals(2.2f, end.zoom, 1e-6f)
        // Before the first keyframe: hold.
        val before = CameraMath.evaluate(camera, -100)
        assertEquals(-0.2f, before.panX, 1e-6f)
    }

    @Test
    fun `ease in-out follows the smoothstep curve`() {
        val camera = CameraState(
            keyframes = listOf(
                CameraKeyframe(0, panX = 0f, panY = 0f, zoom = 1f),
                CameraKeyframe(1000, panX = 1f, panY = 0f, zoom = 1f),
            ),
            interpolation = Interpolation.EASE_IN_OUT,
        )
        val quarter = CameraMath.evaluate(camera, 250).panX
        val half = CameraMath.evaluate(camera, 500).panX
        // smoothstep(0.25) ≈ 0.156 < 0.25; smoothstep(0.5) = 0.5.
        assertEquals(0.15625f, quarter, 1e-4f)
        assertEquals(0.5f, half, 1e-5f)
        assertTrue(quarter < 0.25f)
    }

    @Test
    fun `no keyframes evaluates the static pose`() {
        val camera = CameraState(panX = 0.3f, panY = -0.2f, zoom = 2.5f)
        val pose = CameraMath.evaluate(camera, 12345)
        assertEquals(0.3f, pose.panX, 1e-6f)
        assertEquals(-0.2f, pose.panY, 1e-6f)
        assertEquals(2.5f, pose.zoom, 1e-6f)
    }

    // ------------------------------------------------------------ sanitize

    @Test
    fun `sanitize clamps ranges and replaces non-finite values`() {
        val camera = CameraMath.sanitize(
            CameraState(
                panX = Float.NaN,
                panY = 5f,
                zoom = -2f,
                keyframes = listOf(
                    CameraKeyframe(-100, 0f, 0f, 1f), // negative time dropped
                    CameraKeyframe(0, Float.POSITIVE_INFINITY, 0f, 9f),
                    CameraKeyframe(50, 0.1f, 0f, 2f), // distinct 50 ms slot
                    CameraKeyframe(100, 0.2f, 0f, 2f),
                ),
            ),
        )
        assertEquals(0f, camera.panX, 1e-6f)
        assertEquals(CameraMath.PAN_LIMIT, camera.panY, 1e-6f)
        assertEquals(CameraMath.ZOOM_MIN, camera.zoom, 1e-6f)
        assertEquals(3, camera.keyframes.size) // -100 dropped, no dedup collision
        assertEquals(0.1f, camera.keyframes[1].panX, 1e-6f)
        assertEquals(CameraMath.ZOOM_MAX, camera.keyframes[0].zoom, 1e-6f)
    }

    @Test
    fun `sanitize caps the keyframe count`() {
        val many = (0 until CameraMath.MAX_KEYFRAMES + 10).map {
            CameraKeyframe(timeMs = it * 1000L, panX = 0.01f * it, panY = 0f, zoom = 1f)
        }
        val sanitized = CameraMath.sanitizeKeyframes(many)
        assertEquals(CameraMath.MAX_KEYFRAMES, sanitized.size)
    }

    @Test
    fun `depth multiplier clamps out-of-range depths`() {
        assertEquals(0.5f, CameraMath.depthMultiplier(-5f), 1e-6f)
        assertEquals(1.5f, CameraMath.depthMultiplier(5f), 1e-6f)
        assertEquals(1f, CameraMath.depthMultiplier(Float.NaN), 1e-6f)
    }

    @Test
    fun `poses differ between cameras for the signature`() {
        val a = CameraMath.evaluate(CameraState(zoom = 1f), 0)
        val b = CameraMath.evaluate(CameraState(zoom = 2f), 0)
        assertNotEquals(a, b)
        assertFalse(CameraMath.isIdentity(CameraState(zoom = 2f)))
    }
}

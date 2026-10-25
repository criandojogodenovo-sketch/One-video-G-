package com.onevideog.editor.editor

import com.onevideog.editor.data.CameraState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.0 (Fase L) — SPIKE L2b under the D4 gate: the video plane with a
 * depth camera via an affine (ScaleAndRotateTransformation models exactly
 * one 2D affine — scale then rotate then translate around the frame centre).
 *
 * WHAT THE SPIKE MEASURES: with the plane affine applied in MODEL space and
 * mapped through the SAME NDC bridge the overlays use, the four corners of
 * the transformed video plane match the affine's exact corners within the
 * parity tolerance (1 px) on BOTH standard aspects (portrait 9:16, landscape
 * 16:9), at several camera states — i.e. the MATH is parity-clean.
 *
 * WHY v0.9.0 DEFERS THE INTEGRATION (the D4 gate is NOT passed): the corner
 * harness above proves the model, but the REAL gate wants the corners
 * measured on the RENDERED pipeline (≤1 px on device). Wiring
 * ScaleAndRotateTransformation into both pipelines changes the frame
 * geometry contract the whole editor builds on:
 *  - PREVIEW: the PlayerView surface fills the content rect — a transformed
 *    video no longer fills it, so the content-rect/letterbox math
 *    (OverlayLayout.fitRect), the overlay clip and every hardened placement
 *    assumption would need a second geometry path;
 *  - EXPORT: Presentation letterboxes AFTER the per-item effects, so the
 *    rotated plane's empty corners land differently than in the preview
 *    PlayerView — no JVM harness can decide that (the existing M4 port
 *    models overlays, which the v0.8.1 device report validated in situ).
 * Per D4 the camera therefore applies to OVERLAYS ONLY in v0.9.0
 * (zDepth != 0), labelled "câmara de profundidade (camadas)" in the UI, and
 * the plane integration stays a documented limitation
 * (PREVIEW_EXPORT_PARITY item 29) until the parity harness can run on
 * device footage.
 */
class VideoPlaneCameraSpikeTest {

    private fun planeCornersModel(
        frameW: Float,
        frameH: Float,
        shiftXFrac: Float,
        shiftYFrac: Float,
        scaleMul: Float,
    ): List<Pair<Float, Float>> {
        // The plane's model corners (0..1 fractions), transformed by the
        // spike affine EXACTLY like computePlacements transforms overlays:
        // shift first (coerced to the frame), then scale around the CENTRE.
        val cx = CameraMath.applyShift(0.5f, shiftXFrac) * frameW
        val cy = CameraMath.applyShift(0.5f, shiftYFrac) * frameH
        val halfW = 0.5f * frameW * scaleMul
        val halfH = 0.5f * frameH * scaleMul
        return listOf(
            (cx - halfW) to (cy - halfH),
            (cx + halfW) to (cy - halfH),
            (cx - halfW) to (cy + halfH),
            (cx + halfW) to (cy + halfH),
        )
    }

    private fun planeCornersAffineMatrix(
        frameW: Float,
        frameH: Float,
        shiftXFrac: Float,
        shiftYFrac: Float,
        scaleMul: Float,
    ): List<Pair<Float, Float>> {
        // The same transform as one 2D affine: p' = S·p + t (the exact
        // semantics ScaleAndRotateTransformation applies to the input frame
        // — scale, then translate; no rotation in this spike set).
        val sx = frameW * shiftXFrac
        val sy = frameH * shiftYFrac
        return listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f).map { (ux, uy) ->
            (frameW / 2f + ux * frameW / 2f * scaleMul + sx) to
                (frameH / 2f + uy * frameH / 2f * scaleMul + sy)
        }
    }

    private fun assertCornersMatch(
        frameW: Float,
        frameH: Float,
        camera: CameraState,
        tolerancePx: Float = 1f,
    ) {
        val evaluated = CameraMath.evaluate(camera, 0L)
        val plane = CameraMath.planeAffine(evaluated)
        val model = planeCornersModel(frameW, frameH, plane.shiftXFrac, plane.shiftYFrac, plane.scaleMul)
        val affine = planeCornersAffineMatrix(frameW, frameH, plane.shiftXFrac, plane.shiftYFrac, plane.scaleMul)
        for (i in model.indices) {
            assertTrue(
                "corner $i drifted: model=${model[i]} affine=${affine[i]}",
                kotlin.math.abs(model[i].first - affine[i].first) <= tolerancePx &&
                    kotlin.math.abs(model[i].second - affine[i].second) <= tolerancePx,
            )
        }
    }

    @Test
    fun `spike - pan and zoom corners are parity-clean on portrait 9_16`() {
        assertCornersMatch(1080f, 1920f, CameraState(panX = 0.5f, panY = -0.25f, zoom = 1.4f))
        assertCornersMatch(1080f, 1920f, CameraState(panX = -1f, panY = 1f, zoom = 2.2f))
    }

    @Test
    fun `spike - pan and zoom corners are parity-clean on landscape 16_9`() {
        assertCornersMatch(1920f, 1080f, CameraState(panX = 0.5f, panY = 0.5f, zoom = 1.8f))
        assertCornersMatch(1920f, 1080f, CameraState(zoom = 3f))
    }

    @Test
    fun `spike - identity camera leaves the plane exactly the full frame`() {
        val corners = planeCornersModel(1080f, 1920f, 0f, 0f, 1f)
        assertEquals(0f, corners[0].first, 1e-3f)
        assertEquals(0f, corners[0].second, 1e-3f)
        assertEquals(1080f, corners[3].first, 1e-3f)
        assertEquals(1920f, corners[3].second, 1e-3f)
    }
}

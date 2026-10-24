package com.onevideog.editor.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * v0.8.1 (BUG A — device report, realme RMX3624): THE parity test for the
 * bug class "rotated overlay exports collapsed into a thin diagonal line".
 *
 * Root cause (proven against the Media3 1.4.1 sources and its own pixel
 * tests): OverlayShaderProgram draws the overlay at
 * `bitmap_px * (scale.x, scale.y)`, rotated cleanly by rotationDegrees
 * (OverlayMatrixProvider conjugates the rotation with the overlay aspect)
 * and centred on backgroundFrameAnchor. The pre-v0.8.1 formula assumed the
 * quad spans the whole frame and set an anisotropic scale
 * (sx = widthFrac, sy = widthFrac*frameAspect/bitmapAspect), which under the
 * REAL semantics collapsed the drawn height — for the reported 1203x192 text
 * block on a 1080x1920 frame, to 10.8 px: once rotated -55 deg, the device's
 * "thin diagonal line".
 *
 * The fix ([OverlayLayout.overlayExportScale]) sets ONE uniform scale. This
 * suite pins it with two independent layers:
 *
 *  1. GOLDEN ANCHORS — a faithful JVM port of the Media3 1.4.1 overlay
 *     matrix chain (OverlayMatrixProvider + BitmapOverlay flip + shader
 *     sampling) must reproduce the three MEASURED goldens of Media3's own
 *     OverlayShaderProgramPixelTest (bitmap 304x84, frame 1080x720):
 *     scale (1,1) -> 304x84; rotation 90 -> 84x304; scale (1080/304, 1)
 *     -> 1080x84. If a future Media3 upgrade changes the semantics, these
 *     anchors fail and the shared math must be revisited BEFORE shipping.
 *  2. CORNER PARITY — for rotations (-55.44, 45, 90, 0 deg) on portrait
 *     (0.5625) and landscape (16:9) frames, the four corners of the box the
 *     preview draws (Compose math: scale the box, rotate clockwise around
 *     its centre) must coincide with the four corners the export draws
 *     (the SAME OverlayLayout functions production calls -> Media3 matrix),
 *     within 1 px on the output frame.
 */
class OverlayExportParityTest {

    // -------------------------------------------------- Media3 1.4.1 port
    // Column-major 4x4, android.opengl.Matrix semantics: multiplyMM is the
    // standard product; translateM/scaleM/rotateM POST-multiply.

    private class M4(val m: FloatArray) {
        companion object {
            fun identity() = M4(
                floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
            )
        }

        operator fun times(o: M4): M4 {
            val r = FloatArray(16)
            for (c in 0..3) for (row in 0..3) {
                var acc = 0f
                for (k in 0..3) acc += m[k * 4 + row] * o.m[c * 4 + k]
                r[c * 4 + row] = acc
            }
            return M4(r)
        }
    }

    private fun translation(x: Float, y: Float, z: Float) = M4(
        floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, x, y, z, 1f),
    )

    private fun scaling(x: Float, y: Float, z: Float) = M4(
        floatArrayOf(x, 0f, 0f, 0f, 0f, y, 0f, 0f, 0f, 0f, z, 0f, 0f, 0f, 0f, 1f),
    )

    private fun rotationZ(deg: Float): M4 {
        val a = Math.toRadians(deg.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        // Column-major: col0 = (c, s), col1 = (-s, c) — android setRotateM.
        return M4(floatArrayOf(c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
    }

    /**
     * The vertex transformation of Media3 1.4.1's OverlayMatrixProvider for
     * (0,0) anchors — transcribed operation-for-operation from the source:
     *
     * T_bg * A(overlayW/bgW, overlayH/bgH) * S * O * S^-1 * Q(ovH/ovW) * R * Q^-1 * S
     */
    private fun media3OverlayVertexMatrix(
        bgW: Int,
        bgH: Int,
        bitmapW: Int,
        bitmapH: Int,
        sx: Float,
        sy: Float,
        rotationDegrees: Float,
        bgAnchorX: Float = 0f,
        bgAnchorY: Float = 0f,
    ): M4 {
        var m = M4.identity()
        m = m * translation(bgAnchorX, bgAnchorY, 0f)                 // backgroundFrameAnchor
        m = m * scaling(bitmapW / bgW.toFloat(), bitmapH / bgH.toFloat(), 1f) // aspectRatio
        m = m * scaling(sx, sy, 1f)                                   // scale
        m = m * translation(0f, 0f, 0f)                               // overlayFrameAnchor (0,0)
        m = m * scaling(1f / sx, 1f / sy, 1f)                         // scaleMatrixInv
        val q = bitmapH / bitmapW.toFloat()
        m = m * scaling(q, 1f, 1f)                                    // overlayAspectRatio
        m = m * rotationZ(rotationDegrees)                            // rotate
        m = m * scaling(1f / q, 1f, 1f)                               // overlayAspectRatioInv
        m = m * scaling(sx, sy, 1f)                                   // scale (again)
        return m
    }

    /**
     * The four bitmap corners as drawn on the frame, in px (screen coords,
     * y down). Shader semantics: sampling = getTexSamplingCoord(V * Inv(M) *
     * framePos) with V = BitmapOverlay's vertical flip, so the bitmap texel
     * s lands at p = M * V * (2s-1); px = ((ndc+1)/2 * size) with the y flip
     * into screen space.
     */
    private fun media3DrawnCornersPx(
        bgW: Int,
        bgH: Int,
        bitmapW: Int,
        bitmapH: Int,
        sx: Float,
        sy: Float,
        rotationDegrees: Float,
        bgAnchorX: Float = 0f,
        bgAnchorY: Float = 0f,
    ): List<Pair<Float, Float>> {
        val m = media3OverlayVertexMatrix(
            bgW, bgH, bitmapW, bitmapH, sx, sy, rotationDegrees,
            bgAnchorX = bgAnchorX,
            bgAnchorY = bgAnchorY,
        )
        val corners = listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)
        return corners.map { (cx, cy) ->
            val vx = cx
            val vy = -cy // BitmapOverlay vertical flip applied first (V * ...)
            val ndcX = m.m[0] * vx + m.m[4] * vy + m.m[12]
            val ndcY = m.m[1] * vx + m.m[5] * vy + m.m[13]
            (ndcX + 1f) / 2f * bgW to (1f - ndcY) / 2f * bgH
        }
    }

    private fun bbox(corners: List<Pair<Float, Float>>): Pair<Float, Float> {
        val xs = corners.map { it.first }
        val ys = corners.map { it.second }
        return (xs.max() - xs.min()) to (ys.max() - ys.min())
    }

    /** Drawn box dimensions from the edge vectors (rotation-agnostic). */
    private fun dims(corners: List<Pair<Float, Float>>): Pair<Float, Float> {
        // Corner order = texcoords (0,0),(1,0),(0,1),(1,1): edge 0->1 is the
        // bitmap width direction, 0->2 the height direction.
        val w = hypot(corners[1].first - corners[0].first, corners[1].second - corners[0].second)
        val h = hypot(corners[2].first - corners[0].first, corners[2].second - corners[0].second)
        return w to h
    }

    private fun hypot(x: Float, y: Float): Float = kotlin.math.hypot(x, y)

    // ------------------------------------------------------- golden anchors

    @Test
    fun `media3 port reproduces the measured golden - default scale draws natural size`() {
        // OverlayShaderProgramPixelTest.drawFrame_bitmapOverlay_blendsBitmapIntoFrame:
        // bitmap 304x84, frame 1080x720, scale (1,1) -> golden bbox 304x84.
        val corners = media3DrawnCornersPx(1080, 720, 304, 84, 1f, 1f, 0f)
        val (w, h) = bbox(corners)
        assertEquals(304f, w, 1f)
        assertEquals(84f, h, 1f)
    }

    @Test
    fun `media3 port reproduces the measured golden - rotation 90 is a clean quarter turn`() {
        // drawFrame_rotatedBitmapOverlay_blendsBitmapRotated90degrees:
        // bitmap 304x84, frame 1080x720, rotation 90 -> golden bbox 84x304.
        val corners = media3DrawnCornersPx(1080, 720, 304, 84, 1f, 1f, 90f)
        val (w, h) = bbox(corners)
        assertEquals(84f, w, 1f)
        assertEquals(304f, h, 1f)
    }

    @Test
    fun `media3 port reproduces the measured golden - scale multiplies bitmap px`() {
        // LetterBoxStretchedBitmapOverlay: scale.x = frameW/bitmapW, scale.y = 1
        // -> golden bbox 1080x84. PROVES scale multiplies the bitmap's own px
        // (the model the old formula got wrong).
        val corners = media3DrawnCornersPx(1080, 720, 304, 84, 1080f / 304f, 1f, 0f)
        val (w, h) = bbox(corners)
        assertEquals(1080f, w, 1f)
        assertEquals(84f, h, 1f)
    }

    // ---------------------------------------------------- corner parity

    /** Compose preview corners: scale the box, rotate clockwise (y-down). */
    private fun previewCorners(
        frameW: Float,
        frameH: Float,
        bitmapAspect: Float,
        x: Float,
        y: Float,
        scale: Float,
        rotationDegCw: Float,
    ): List<Pair<Float, Float>> {
        val (w, h) = OverlayLayout.previewSize(frameW, frameH, bitmapAspect, scale)
        val cx = x * frameW
        val cy = y * frameH
        val a = Math.toRadians(rotationDegCw.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        return listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f).map { (ux, uy) ->
            val dx = ux * w / 2f
            val dy = uy * h / 2f
            (cx + dx * c - dy * s) to (cy + dx * s + dy * c)
        }
    }

    /**
     * Export corners through the PRODUCTION shared math (the exact functions
     * KeyframedOverlay/AnimatedTextOverlay call) and the Media3 matrix port.
     */
    private fun exportCorners(
        frameW: Int,
        frameH: Int,
        bitmapW: Int,
        bitmapH: Int,
        x: Float,
        y: Float,
        scale: Float,
        rotationDegCw: Float,
    ): List<Pair<Float, Float>> {
        val s = OverlayLayout.overlayExportScale(bitmapW, frameW, scale)
        val (ndcX, ndcY) = OverlayLayout.toBackgroundNdc(x, y)
        val rotation = OverlayLayout.toExportRotation(rotationDegCw)
        return media3DrawnCornersPx(
            frameW, frameH, bitmapW, bitmapH, s, s, rotation,
            bgAnchorX = ndcX.coerceIn(-1f, 1f),
            bgAnchorY = ndcY.coerceIn(-1f, 1f),
        )
    }

    @Test
    fun `rotated overlays match preview on a portrait frame - the reported bug`() {
        // THE device report: 1-line text block 1203x192, scale 0.629,
        // rotDeg -55.44, on a 9:16 (0.5625) frame. Corner tolerance 1 px.
        parityCases(
            frameW = 1080,
            frameH = 1920,
            bitmapW = 1203,
            bitmapH = 192,
            x = 0.472f,
            y = 0.819f,
            scale = 0.629f,
            rotations = listOf(-55.44f, 45f, 90f, 0f),
            tolerancePx = 1f,
        )
    }

    @Test
    fun `rotated overlays match preview on a landscape frame`() {
        parityCases(
            frameW = 1920,
            frameH = 1080,
            bitmapW = 1203,
            bitmapH = 192,
            x = 0.472f,
            y = 0.819f,
            scale = 0.629f,
            rotations = listOf(-55.44f, 45f, 90f, 0f),
            tolerancePx = 1f,
        )
    }

    @Test
    fun `image-like overlays match preview at every rotation and aspect`() {
        // A portrait photo bitmap (the image decode cap is 1024) on both frames.
        parityCases(1080, 1920, 768, 1024, 0.3f, 0.4f, 0.35f, listOf(-55.44f, 45f, 90f, 0f), 1f)
        parityCases(1920, 1080, 768, 1024, 0.3f, 0.4f, 0.35f, listOf(-55.44f, 45f, 90f, 0f), 1f)
        // A square sticker bitmap.
        parityCases(1080, 1920, 1024, 1024, 0.7f, 0.2f, 0.2f, listOf(-55.44f, 45f, 90f, 0f), 1f)
    }

    private fun parityCases(
        frameW: Int,
        frameH: Int,
        bitmapW: Int,
        bitmapH: Int,
        x: Float,
        y: Float,
        scale: Float,
        rotations: List<Float>,
        tolerancePx: Float,
    ) {
        val bitmapAspect = bitmapW.toFloat() / bitmapH
        for (rot in rotations) {
            val preview = previewCorners(frameW.toFloat(), frameH.toFloat(), bitmapAspect, x, y, scale, rot)
            val export = exportCorners(frameW, frameH, bitmapW, bitmapH, x, y, scale, rot)
            for (i in preview.indices) {
                val dx = abs(preview[i].first - export[i].first)
                val dy = abs(preview[i].second - export[i].second)
                assertTrue(
                    "rotation $rot corner $i drifted: preview=${preview[i]} export=${export[i]}",
                    dx <= tolerancePx && dy <= tolerancePx,
                )
            }
            // The regression this test exists for: the drawn box keeps the
            // bitmap aspect (no collapsed axis) at EVERY rotation — box
            // dimensions from the edge vectors, not the axis-aligned bbox.
            val (w, h) = dims(export)
            val expectedW = scale * frameW
            val expectedH = expectedW / bitmapAspect
            assertEquals("rotation $rot width", expectedW, w, tolerancePx)
            assertEquals("rotation $rot height", expectedH, h, tolerancePx)
        }
    }
}

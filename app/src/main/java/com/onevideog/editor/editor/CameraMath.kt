package com.onevideog.editor.editor

import com.onevideog.editor.data.CameraKeyframe
import com.onevideog.editor.data.CameraState
import com.onevideog.editor.data.Interpolation
import com.onevideog.editor.data.OverlayTransform

/**
 * v0.9.0 (Fase L2) — THE ONE 2.5D camera math, shared by the preview
 * ([com.onevideog.editor.ui.editor.computePlacements]) and the export
 * ([KeyframedOverlay] / [AnimatedTextOverlay] / the video-plane
 * MatrixTransformation). There is no second implementation: what the user
 * pans/zooms in the editor is what the export renders.
 *
 * Model (decisão D2/D4 do estudo aprovado — Opção A "2.5D bake-to-texture"):
 *
 *  - The camera is a pan/zoom defined in CONTENT space (the frame the
 *    overlays are positioned against). The video plane carries it through a
 *    Media3 1.4.1 `MatrixTransformation` — verified against the real 1.4.1
 *    sources: the matrix maps NDC points affinely in the vertex shader
 *    (`gl_Position = uTransformationMatrix * aFramePosition`), the output
 *    frame KEEPS its size and pixels pushed outside NDC are CLIPPED
 *    (MatrixTransformation javadoc + DefaultShaderProgram.visiblePolygon) —
 *    exactly a camera crop. No new shader, no new dependency.
 *  - Every overlay sits on a depth plane [zDepth] (-1 deepest .. +1 closest,
 *    0 = the video plane). The camera reaches each plane with
 *    depth-proportional strength ([depthMultiplier]): zDepth 0 rides the
 *    video plane EXACTLY (same affine, parity by construction); deeper
 *    planes move/zoom less (parallax).
 *  - Draw order (D2): deeper = drawn first; [com.onevideog.editor.data
 *    .OverlayItem.zOrder] is the tie-break and remains the only thing
 *    bring-to-front/send-to-back manipulate.
 *
 * Pan units: fractions of the content size, +panX = content moves right,
 * +panY = content moves DOWN (screen convention, matches the editor
 * gesture space). Content NDC is +y UP, so panY flips sign on the way in
 * ([toNdcShift]) — the same flip the existing
 * [OverlayLayout.toBackgroundNdc] bridge performs.
 */
object CameraMath {

    /**
     * Parallax strength: a plane at zDepth d receives 1 + PARALLAX_STRENGTH·d
     * of the camera's motion/zoom. At the supported depth range -1..1 the
     * multiplier stays inside [0.5, 1.5] — the deepest plane moves HALF the
     * camera, the closest one moves 1.5×.
     */
    const val PARALLAX_STRENGTH = 0.5f

    /** Model clamps. The UI sliders stay inside ±0.5 pan / 1..3 zoom. */
    const val PAN_LIMIT = 1f
    const val ZOOM_MIN = 1f
    const val ZOOM_MAX = 3f
    const val DEPTH_MIN = -1f
    const val DEPTH_MAX = 1f

    /** Maximum stored camera keyframes (same cap philosophy as audio gain). */
    const val MAX_KEYFRAMES = 16

    /** The camera pose at one moment: pan fractions + zoom multiplier. */
    data class Pose(val panX: Float, val panY: Float, val zoom: Float)

    val IDENTITY_POSE: Pose = Pose(0f, 0f, 1f)

    /**
     * The per-plane camera: the zoom/translate multipliers
     * [affineForDepth] produces. NDC shifts are in content NDC (+x right,
     * +y up — the same convention as the export `backgroundFrameAnchor`).
     */
    data class DepthAffine(
        val zoom: Float,
        val ndcShiftX: Float,
        val ndcShiftY: Float,
    )

    /** True when the camera would render EXACTLY like its absence. */
    fun isIdentity(camera: CameraState): Boolean {
        if (!isIdentityPose(poseOf(camera.panX, camera.panY, camera.zoom))) return false
        return camera.keyframes.all {
            isIdentityPose(poseOf(it.panX, it.panY, it.zoom))
        }
    }

    /**
     * The camera pose at absolute project [timeMs]. No keyframes -> the
     * static base pose. With keyframes: hold before the first / after the
     * last, interpolate between neighbours with the project camera's
     * [Interpolation] (the same lerp/smoothstep the overlay keyframes use —
     * one curve implementation).
     */
    fun evaluate(camera: CameraState, timeMs: Long): Pose {
        val kfs = camera.keyframes.sortedBy { it.timeMs }
        if (kfs.isEmpty()) return poseOf(camera.panX, camera.panY, camera.zoom)
        val interpolation = camera.interpolation
        val first = kfs.first()
        val last = kfs.last()
        return when {
            timeMs <= first.timeMs -> poseOf(first.panX, first.panY, first.zoom)
            timeMs >= last.timeMs -> poseOf(last.panX, last.panY, last.zoom)
            else -> {
                var i = 0
                while (i < kfs.size - 1 && kfs[i + 1].timeMs <= timeMs) i++
                val a = kfs[i]
                val b = kfs[i + 1]
                val span = (b.timeMs - a.timeMs).coerceAtLeast(1)
                val u = (timeMs - a.timeMs).toFloat() / span
                val eased =
                    if (interpolation == Interpolation.EASE_IN_OUT) KeyframeEvaluator.smoothstep(u)
                    else u
                Pose(
                    panX = KeyframeEvaluator.lerp(a.panX, b.panX, eased),
                    panY = KeyframeEvaluator.lerp(a.panY, b.panY, eased),
                    zoom = KeyframeEvaluator.lerp(a.zoom, b.zoom, eased),
                )
            }
        }
    }

    /** Parallax multiplier of a depth plane (clamped to the model range). */
    fun depthMultiplier(zDepth: Float): Float =
        1f + PARALLAX_STRENGTH * zDepth.finiteOrElse(0f).coerceIn(DEPTH_MIN, DEPTH_MAX)

    /**
     * The camera as seen by ONE depth plane at [pose]: the zoom and the
     * content-NDC translation that plane receives. zDepth 0 returns the
     * video-plane camera EXACTLY (the values the MatrixTransformation uses).
     */
    fun affineForDepth(pose: Pose, zDepth: Float): DepthAffine {
        val safePose = sanitizedPose(pose)
        val m = depthMultiplier(zDepth)
        return DepthAffine(
            zoom = 1f + (safePose.zoom - 1f) * m,
            ndcShiftX = toNdcShiftX(safePose.panX) * m,
            ndcShiftY = toNdcShiftY(safePose.panY) * m,
        )
    }

    /**
     * Applies the depth camera to one overlay's evaluated transform — THE
     * bridge both pipelines call before the hardened v0.8.1 paths
     * ([OverlayLayout.toBackgroundNdc] + `setScale(s, s)` +
     * [OverlayLayout.toExportRotation]). Position maps through the plane's
     * affine in content NDC; scale multiplies by the plane zoom; rotation
     * and opacity are untouched (a pan/zoom camera does not rotate).
     *
     * The mapped position is clamped to 0..1 — THE parity decision: the
     * export anchors already coerce to the frame border
     * (`coerceIn(-1, 1)` in [KeyframedOverlay]), so the preview clamps the
     * mapped centre the SAME way in the SHARED math — an overlay pushed
     * past the edge sticks at the border IDENTICALLY on both paths (its box
     * still clips visually). Without this clamp the export would glue the
     * overlay to the edge while the preview let it drift out: a parity
     * break the local suite caught (the v0.8.1 lesson, now institutional).
     */
    fun applyToTransform(pose: Pose, zDepth: Float, t: OverlayTransform): OverlayTransform {
        val d = affineForDepth(pose, zDepth)
        val xNdc = 2f * t.x - 1f
        val yNdc = 1f - 2f * t.y
        val mappedXNdc = d.zoom * xNdc + d.ndcShiftX
        val mappedYNdc = d.zoom * yNdc + d.ndcShiftY
        return t.copy(
            x = ((mappedXNdc + 1f) / 2f).coerceIn(0f, 1f),
            y = ((1f - mappedYNdc) / 2f).coerceIn(0f, 1f),
            scale = t.scale * d.zoom,
        )
    }

    /**
     * The video-plane matrix for Media3's MatrixTransformation, as an
     * `android.graphics.Matrix` values array (getValues order:
     * [MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y, MSCALE_Y, MTRANS_Y, 0, 0, 1]).
     * Pure (no android import): the caller feeds it to Matrix.setValues and
     * the JVM parity harness consumes it directly.
     *
     * Mapping: content NDC p -> zoom·p + (shiftX, shiftY), i.e. a uniform
     * zoom about the frame centre plus the pan. In NDC, x=±1 is half the
     * frame width and y=±1 half the height, so a uniform NDC scale IS a
     * uniform pixel zoom (both axes scale by the same factor) and the
     * shifts land at shiftX·W/2 px horizontally / |shiftY|·H/2 px
     * vertically — no aspect correction is needed for a rotation-free
     * camera (verified against the ScaleAndRotateTransformation aspect
     * pre/post-scaling, which exists for ROTATIONS only).
     */
    fun videoPlaneMatrixValues(pose: Pose): FloatArray {
        val safePose = sanitizedPose(pose)
        val z = safePose.zoom
        return floatArrayOf(
            z, 0f, toNdcShiftX(safePose.panX),
            0f, z, toNdcShiftY(safePose.panY),
            0f, 0f, 1f,
        )
    }

    /** Sorts, sanitizes, dedups (50 ms snap) and caps the camera keyframes. */
    fun sanitizeKeyframes(keyframes: List<CameraKeyframe>): List<CameraKeyframe> =
        keyframes
            .filter { it.timeMs >= 0 }
            .map {
                CameraKeyframe(
                    timeMs = it.timeMs,
                    panX = it.panX.finiteOrElse(0f).coerceIn(-PAN_LIMIT, PAN_LIMIT),
                    panY = it.panY.finiteOrElse(0f).coerceIn(-PAN_LIMIT, PAN_LIMIT),
                    zoom = it.zoom.finiteOrElse(1f).coerceIn(ZOOM_MIN, ZOOM_MAX),
                )
            }
            .sortedBy { it.timeMs }
            .distinctBy { KeyframeEvaluator.snapTime(it.timeMs) }
            .take(MAX_KEYFRAMES)

    /** Sanitizes the whole camera (base pose + keyframes). */
    fun sanitize(camera: CameraState): CameraState = camera.copy(
        panX = camera.panX.finiteOrElse(0f).coerceIn(-PAN_LIMIT, PAN_LIMIT),
        panY = camera.panY.finiteOrElse(0f).coerceIn(-PAN_LIMIT, PAN_LIMIT),
        zoom = camera.zoom.finiteOrElse(1f).coerceIn(ZOOM_MIN, ZOOM_MAX),
        keyframes = sanitizeKeyframes(camera.keyframes),
    )

    // ------------------------------------------------------------- internals

    private fun poseOf(panX: Float, panY: Float, zoom: Float): Pose = sanitizedPose(
        Pose(panX, panY, zoom),
    )

    private fun sanitizedPose(pose: Pose): Pose = Pose(
        panX = pose.panX.finiteOrElse(0f).coerceIn(-PAN_LIMIT, PAN_LIMIT),
        panY = pose.panY.finiteOrElse(0f).coerceIn(-PAN_LIMIT, PAN_LIMIT),
        zoom = pose.zoom.finiteOrElse(1f).coerceIn(ZOOM_MIN, ZOOM_MAX),
    )

    private fun isIdentityPose(pose: Pose): Boolean =
        sanitizedPose(pose).let { it.panX == 0f && it.panY == 0f && it.zoom == 1f }

    /**
     * Pan fraction (+ = content moves right) -> content NDC shift
     * (+ = right): a shift of half the content width is NDC 1.
     */
    private fun toNdcShiftX(panX: Float): Float = 2f * panX

    /**
     * Pan fraction (+ = content moves DOWN) -> content NDC shift
     * (+ = UP): the y flip the model↔NDC bridge always performs.
     */
    private fun toNdcShiftY(panY: Float): Float = -2f * panY

    private fun Float.finiteOrElse(fallback: Float): Float =
        if (isFinite()) this else fallback
}

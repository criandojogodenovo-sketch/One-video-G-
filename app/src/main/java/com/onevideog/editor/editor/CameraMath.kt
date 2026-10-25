package com.onevideog.editor.editor

import com.onevideog.editor.data.CameraKeyframe
import com.onevideog.editor.data.CameraState
import com.onevideog.editor.data.Interpolation

/**
 * v0.9.0 (Fase L, L2) — pure depth-camera math, the ONE implementation both
 * pipelines consume:
 *
 *  - PREVIEW: [com.onevideog.editor.ui.editor.computePlacements] applies
 *    [affineForDepth] to each overlay's evaluated transform;
 *  - EXPORT: [KeyframedOverlay] / [AnimatedTextOverlay] apply the SAME
 *    [affineForDepth] before the hardened v0.8.1 bridge
 *    ([OverlayLayout.toBackgroundNdc] + uniform `setScale(s, s)` +
 *    [OverlayLayout.toExportRotation]) — NO new shader, NO second render
 *    path; the affine rides the existing OverlaySettings inputs.
 *
 * Model (2.5D parallax, bake-to-texture option A):
 *  - the camera is a virtual pan/zoom [CameraState];
 *  - an overlay's [com.onevideog.editor.data.OverlayItem.zDepth] d in
 *    [-1, 1] is its depth RELATIVE to the video plane (d = 0: exactly on the
 *    video plane);
 *  - the overlay's on-screen shift is proportional to its depth:
 *    shift = pan * d * [PAN_GAIN] and scaleMul = 1 + (zoom - 1) * d *
 *    [ZOOM_GAIN] — deeper layers (d > 0) move/zoom LESS than the camera,
 *    closer layers (d < 0) move/zoom MORE, and **d = 0 is the EXACT
 *    identity**, so any project without depth renders byte-for-byte the
 *    v0.8.1 behaviour (locked by CameraMathTest).
 *
 * D2 (order): zDepth only defines DRAW ORDER when it differs — deeper
 * (larger d) draws FIRST; the existing zOrder is the tie-breaker and stays
 * what bringOverlayToFront/sendOverlayToBack manipulate. The shared
 * comparator is [drawOrderComparator] (preview + export use the same one).
 *
 * L2b (D4 gate): the video plane itself is NOT transformed in v0.9.0.
 * The affine of the video plane would be [planeAffine] (full camera, no
 * depth attenuation); the spike harness (VideoPlaneCameraSpikeTest) pins its
 * corners through the SAME matrix model the OverlayExportParityTest uses,
 * but wiring ScaleAndRotateTransformation into BOTH pipelines changes the
 * PlayerView/content-rect geometry contract — deferred with a documented
 * limitation, and the camera applies to overlays only (UI label:
 * "depth camera (layers)").
 *
 * Pure — no Android types, fully JVM-testable.
 */
object CameraMath {

    /** Documented zDepth range (coerced on use). 0 = the video plane. */
    const val Z_DEPTH_MIN = -1f
    const val Z_DEPTH_MAX = 1f

    /** Documented camera pan range (fractions of the content size). */
    const val PAN_MIN = -1f
    const val PAN_MAX = 1f

    /** Documented camera zoom range (1 = unchanged). */
    const val ZOOM_MIN = 1f
    const val ZOOM_MAX = 3f

    /**
     * Max on-screen shift of a d = ±1 overlay under a full pan: ±0.25 of the
     * content size — a strong but safe parallax (the anchor still coerces at
     * the frame edge exactly like the preview, see PREVIEW_EXPORT_PARITY).
     */
    const val PAN_GAIN = 0.25f

    /**
     * Max scale factor delta of a d = ±1 overlay under zoom 3:
     * 1 + 2 × 0.35 = 1.70 (closer layer) / 1 − 0.70 = 0.30 (deeper layer),
     * floored at [MIN_SCALE_MUL].
     */
    const val ZOOM_GAIN = 0.35f

    /** Scale-multiplier floor (a layer can never collapse to nothing). */
    const val MIN_SCALE_MUL = 0.05f

    /** The camera evaluated at a project time (the pan/zoom that "is" then). */
    data class EvaluatedCamera(val panX: Float, val panY: Float, val zoom: Float)

    /**
     * The per-overlay affine the hardened v0.8.1 paths consume: a normalized
     * shift added to (x, y) BEFORE [OverlayLayout.toBackgroundNdc], and a
     * multiplier on the uniform scale BEFORE `setScale(s, s)`.
     */
    data class DepthAffine(val shiftXFrac: Float, val shiftYFrac: Float, val scaleMul: Float) {
        companion object {
            /** The exact identity — every zDepth-0 overlay and every default camera. */
            val IDENTITY = DepthAffine(0f, 0f, 1f)
        }
    }

    /**
     * Evaluates [camera] at absolute project [timeMs]: base (static) values
     * when no keyframes define a property; keyframed properties interpolate
     * with the camera's [com.onevideog.editor.data.Interpolation] using the
     * SAME curve functions the overlay keyframes use
     * ([KeyframeEvaluator.lerp] / smoothstep, hold before first / after
     * last). Non-finite model values fall back to the identity field.
     */
    fun evaluate(camera: CameraState, timeMs: Long): EvaluatedCamera {
        val base = EvaluatedCamera(
            panX = camera.panX.finiteOrElse(0f).coerceIn(PAN_MIN, PAN_MAX),
            panY = camera.panY.finiteOrElse(0f).coerceIn(PAN_MIN, PAN_MAX),
            zoom = camera.zoom.finiteOrElse(1f).coerceIn(ZOOM_MIN, ZOOM_MAX),
        )
        val kfs = camera.keyframes.sortedBy { it.timeMs }
        if (kfs.isEmpty()) return base
        val interpolation = camera.interpolation
        return EvaluatedCamera(
            panX = propertyAt(kfs, timeMs, base.panX, interpolation) { it.panX },
            panY = propertyAt(kfs, timeMs, base.panY, interpolation) { it.panY },
            zoom = propertyAt(kfs, timeMs, base.zoom, interpolation) { it.zoom },
        )
    }

    /**
     * The affine for one overlay at depth [zDepth] under the camera evaluated
     * at the frame time. Identity whenever zDepth == 0 (non-finite depths
     * count as 0 — a corrupt value can never move a layer).
     */
    fun affineForDepth(camera: EvaluatedCamera, zDepth: Float): DepthAffine {
        if (zDepth == 0f) return DepthAffine.IDENTITY
        val d = zDepth.finiteOrElse(0f).coerceIn(Z_DEPTH_MIN, Z_DEPTH_MAX)
        if (d == 0f) return DepthAffine.IDENTITY
        // `+ 0f` normalizes IEEE -0.0 (0 pan × negative depth) to +0.0 so
        // the identity comparison stays bit-exact.
        return DepthAffine(
            shiftXFrac = camera.panX * d * PAN_GAIN + 0f,
            shiftYFrac = camera.panY * d * PAN_GAIN + 0f,
            scaleMul = (1f + (camera.zoom - 1f) * d * ZOOM_GAIN)
                .coerceAtLeast(MIN_SCALE_MUL)
                .finiteOrElse(1f),
        )
    }

    /**
     * Convenience: evaluate + affine in one call (the production call sites
     * that hold the [CameraState] and a frame time).
     */
    fun affineForDepth(camera: CameraState, timeMs: Long, zDepth: Float): DepthAffine =
        affineForDepth(evaluate(camera, timeMs), zDepth)

    /**
     * Applies [affine] to a normalized x/y the SAME way both pipelines do:
     * add the shift, then coerce to 0..1 — the exact model-space twin of the
     * export's `toBackgroundNdc(...).coerceIn(-1, 1)` anchor clamp (documented
     * limitation: layer anchors cut at the frame edge), so preview and export
     * cannot disagree at the boundary.
     */
    fun applyShift(value: Float, shiftFrac: Float): Float =
        (value + shiftFrac).coerceIn(0f, 1f)

    /** Multiplies a scale fraction by [affine]'s scale multiplier. */
    fun applyScale(scale: Float, affine: DepthAffine): Float =
        scale * affine.scaleMul

    /**
     * L2b spike model: the affine the VIDEO PLANE itself would take (full
     * camera, no depth attenuation — the plane IS depth 0 but follows the
     * camera when integrated). Used by the spike harness only; NOT wired
     * into any pipeline in v0.9.0 (D4 gate — see the class docs).
     */
    fun planeAffine(camera: EvaluatedCamera): DepthAffine = DepthAffine(
        shiftXFrac = camera.panX * PAN_GAIN,
        shiftYFrac = camera.panY * PAN_GAIN,
        scaleMul = camera.zoom,
    )

    /**
     * D2 draw order: deeper layers FIRST (bigger zDepth earlier), then the
     * existing zOrder ascending (Media3 draws the LAST texture on top; the
     * preview draws the list in order with the last on top as well).
     * Non-finite depths sort as 0.
     */
    val drawOrderComparator: Comparator<com.onevideog.editor.data.OverlayItem> =
        Comparator { a, b ->
            val da = if (a.zDepth.isFinite()) a.zDepth else 0f
            val db = if (b.zDepth.isFinite()) b.zDepth else 0f
            // Descending depth (deeper first); the existing zOrder is the
            // tie-breaker ascending — both pipelines draw the list in order
            // with the LAST entry on top, exactly like the pre-L
            // sortedBy(zOrder) behaviour.
            val byDepth = db.compareTo(da)
            if (byDepth != 0) byDepth else a.zOrder.compareTo(b.zOrder)
        }

    // ------------------------------------------------------------- internals

    private inline fun propertyAt(
        defined: List<CameraKeyframe>,
        timeMs: Long,
        base: Float,
        interpolation: Interpolation,
        pick: (CameraKeyframe) -> Float?,
    ): Float {
        val points = defined.filter { pick(it) != null }
        if (points.isEmpty()) return base
        val first = points.first()
        val last = points.last()
        if (timeMs <= first.timeMs) return pick(first)!!.finiteOrElse(base)
        if (timeMs >= last.timeMs) return pick(last)!!.finiteOrElse(base)
        var i = 0
        while (i < points.size - 1 && points[i + 1].timeMs <= timeMs) i++
        val a = points[i]
        val b = points[i + 1]
        val span = (b.timeMs - a.timeMs).coerceAtLeast(1)
        val u = (timeMs - a.timeMs).toFloat() / span
        val eased = if (interpolation == Interpolation.EASE_IN_OUT) {
            KeyframeEvaluator.smoothstep(u)
        } else {
            u
        }
        val av = pick(a)!!.finiteOrElse(base)
        val bv = pick(b)!!.finiteOrElse(base)
        return KeyframeEvaluator.lerp(av, bv, eased)
    }

    private fun Float.finiteOrElse(fallback: Float): Float =
        if (isFinite()) this else fallback
}

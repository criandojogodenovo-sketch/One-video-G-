package com.onevideog.editor.editor

/**
 * Pure gesture math for the preview gizmos (v0.4.0, Etapa D). Extracted from
 * the Compose pointer handler so the tricky parts — quadrant-free rotation
 * and pinch scaling — are unit-testable without an Android runtime.
 *
 * Both preview gizmos and the properties panel write the SAME normalized
 * model ([com.onevideog.editor.data.OverlayTransform]); this object only
 * converts pointer geometry into model deltas.
 */
object GizmoMath {

    /**
     * Shortest signed angular delta (degrees) needed to rotate FROM [prevDeg]
     * TO [nowDeg], normalized to (-180, 180].
     *
     * Accumulating THESE deltas per pointer event never jumps quadrants:
     * dragging a rotation handle across the ±180° boundary yields +19° not
     * -341° (the previous absolute `angle - startAngle` formula produced a
     * ~360° flip at the atan2 seam — device-visible rotation jump).
     */
    fun angleDelta(prevDeg: Float, nowDeg: Float): Float {
        var d = (nowDeg - prevDeg) % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /**
     * Uniform (aspect-preserving) scale after a two-finger spread, from the
     * [baseScale] captured when the pinch started, the finger [startDist]
     * and the current [nowDist]. The result is clamped to the model range
     * (0.05..1.6) — the same limits the scale handle enforces. A degenerate
     * start distance (fingers on the same pixel) is guarded so the factor
     * can never explode.
     */
    fun pinchScale(baseScale: Float, startDist: Float, nowDist: Float): Float {
        val safeStart = if (startDist.isFinite() && startDist > 1f) startDist else 1f
        val safeNow = if (nowDist.isFinite()) nowDist else safeStart
        return (baseScale * (safeNow / safeStart)).coerceIn(0.05f, 1.6f)
    }
}

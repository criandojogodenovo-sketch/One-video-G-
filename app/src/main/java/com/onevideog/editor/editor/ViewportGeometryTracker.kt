package com.onevideog.editor.editor

/**
 * Dedupe gate for the preview geometry evidence event (v0.2.5, pure —
 * unit-tested).
 *
 * The three preview dimension sources (decoder output, surface view layout,
 * fitted viewport box) each change far more often than the geometry they
 * describe — layout passes, recompositions and per-item video callbacks all
 * fire during a normal session. Logging every callback would bury the
 * report; logging only REAL changes keeps one `VIEWPORT_GEOMETRY` event per
 * distinct geometry state, which is what the master-prompt item 2 asks the
 * report to prove: video vs surface vs viewport dimensions, separately.
 */
class ViewportGeometryTracker {

    /** One geometry snapshot (all px; video in decoder output units). */
    data class Geometry(
        val videoW: Int,
        val videoH: Int,
        val surfaceW: Int,
        val surfaceH: Int,
        val viewportW: Int,
        val viewportH: Int,
        val contentAspect: Float,
    )

    private var last: Geometry? = null

    /** True exactly when [g] differs from the previously seen geometry. */
    fun shouldLog(g: Geometry): Boolean {
        val changed = g != last
        if (changed) last = g
        return changed
    }

    /** Test/reset hook: forget the last geometry so the next call logs. */
    fun reset() {
        last = null
    }
}

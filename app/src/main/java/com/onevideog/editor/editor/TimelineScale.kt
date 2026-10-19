package com.onevideog.editor.editor

import kotlin.math.roundToLong

/**
 * Pure timeline time <-> pixel mapping, shared by the ruler, every track
 * row, the playhead line, the auto-scroll and pointer hit-testing.
 *
 * v0.2.1 bug being fixed: the timeline mixed dp (block widths, ruler width,
 * ruler labels) and px (pointer offsets, playhead offset, tick canvas). At
 * phone densities (typically 1.5–2.6) taps scrubbed to ~2x the intended
 * time and block positions no longer matched the ruler. Everything now goes
 * through ONE scale whose pixel unit is explicit: [designPx] is the
 * density-independent timeline pixel; [density] converts it to real pixels.
 *
 * Scale definition: at zoom = 1, one design pixel represents
 * [DESIGN_MS_PER_PX] milliseconds. Zoom multiplies pixels per millisecond.
 */
class TimelineScale(val density: Float, val zoom: Float) {

    companion object {
        /** Milliseconds represented by one design pixel at zoom = 1. */
        const val DESIGN_MS_PER_PX = 60f

        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 6f

        /** Clamp helper shared by the zoom buttons and pinch-to-zoom. */
        fun clampZoom(zoom: Float): Float = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    /** Real device pixels per millisecond at the current zoom. */
    val pxPerMs: Float
        get() {
            val safeDensity = if (density <= 0f) 1f else density
            return safeDensity / (DESIGN_MS_PER_PX / zoom)
        }

    /** Milliseconds per real device pixel. */
    val msPerPx: Float
        get() = 1f / pxPerMs

    fun timeToPx(ms: Long): Float = ms * pxPerMs

    fun timeToPx(ms: Float): Float = ms * pxPerMs

    /** Maps a pointer x (px) to a project time, without clamping. */
    fun pxToTime(px: Float): Long = (px / pxPerMs).roundToLong()

    /** Maps a pointer x (px) to a project time clamped to [0, durationMs]. */
    fun pxToTimeClamped(px: Float, durationMs: Long): Long =
        ProjectMath.clampPlayheadMs(pxToTime(px), durationMs)

    /**
     * Width in real pixels of a span of [durationMs] — used by track rows
     * laid out in px so blocks line up with the ruler at any density.
     */
    fun spanToPx(durationMs: Long): Float = durationMs * pxPerMs
}

package com.onevideog.editor.editor

/**
 * Pure editing rules for timed intervals (overlay bars, audio bars) shared
 * by the timeline edge handles and the sheet editors. Unit-tested: covers
 * the minimum-duration rule, zero/negative interval prevention and clamping
 * at the timeline boundaries.
 */
object OverlayEditing {

    /** Minimum overlay duration in project ms. */
    const val MIN_OVERLAY_MS = 150L

    /**
     * v0.3.0 layer-lock rule: a locked layer is still SELECTABLE (so the
     * user can inspect it and unlock it) but every editing write —
     * transform gesture, move/trim, keyframe edits, z-order changes and
     * delete — is rejected. Pure, unit-tested; enforced by the ViewModel
     * (single write path) and by the canvas hit-test (no gesture starts).
     */
    fun isEditable(locked: Boolean): Boolean = !locked

    /** Move rule: start >= 0 (duration preserved by the caller). */
    fun clampMove(newStartMs: Long): Long = newStartMs.coerceAtLeast(0)

    /**
     * Left edge handle: start stays in [0, end - minMs]. When the current
     * interval is already shorter than [minMs], start clamps to 0.
     */
    fun clampTrimStart(newStartMs: Long, currentEndMs: Long, minMs: Long): Long =
        newStartMs.coerceIn(0L, (currentEndMs - minMs).coerceAtLeast(0L))

    /** Right edge handle: end stays at or above start + minMs. */
    fun clampTrimEnd(currentStartMs: Long, newEndMs: Long, minMs: Long): Long =
        newEndMs.coerceAtLeast(currentStartMs + minMs)

    /** Sheet-side free trim of both edges at once (validated, total). */
    fun clampTrim(newStartMs: Long, newEndMs: Long, minMs: Long): Pair<Long, Long>? {
        val start = newStartMs.coerceAtLeast(0)
        val end = newEndMs.coerceAtLeast(0)
        if (end - start < minMs) return null
        return start to end
    }
}

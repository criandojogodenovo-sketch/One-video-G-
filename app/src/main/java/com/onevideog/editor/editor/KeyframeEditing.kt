package com.onevideog.editor.editor

import com.onevideog.editor.data.Keyframe
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayTransform

/**
 * Pure auto-key / navigation / collision rules for keyframe editing
 * (v0.5.0, Fase F). No Android classes and no ViewModel access: the
 * ViewModel is the single write path and calls [applyTransformEdit] inside
 * one [com.onevideog.editor.editor.EditorViewModel.commitProjectOrNull]
 * commit — ONE undo step per gesture/slider operation, exactly like the
 * v0.4.0 gizmo commits.
 *
 * Auto-key rule ([applyTransformEdit]):
 *  - overlay has NO keyframes      -> the edit writes the base transform
 *    (behaviour identical to v0.4.0 — plain overlays keep working);
 *  - overlay HAS keyframes and a keyframe sits within [TOLERANCE_MS] of the
 *    playhead                      -> THAT keyframe is updated in place (its
 *    exact timeMs is preserved);
 *  - overlay HAS keyframes, playhead between keyframes
 *                                  -> a new full keyframe is created at
 *    [KeyframeEvaluator.snapTime] (50 ms grid). A keyframe already sitting
 *    exactly on the snapped time is replaced (same rule as
 *    addKeyframeAtPlayhead).
 *
 * A transform edit defines the FULL transform (all five properties), like
 * the gizmo gesture it mirrors; partial (null-property) keyframes remain
 * creatable through the keyframe sheet, but auto-key writes are complete.
 */
object KeyframeEditing {

    /** Snap grid for created/moved keyframes (KeyframeEvaluator contract). */
    const val SNAP_MS = 50L

    /**
     * Playhead tolerance for "editing ON a keyframe": half the snap grid, so
     * the grid and the tolerance never disagree about which keyframe is the
     * target.
     */
    const val TOLERANCE_MS = SNAP_MS / 2

    /** How far [resolveMoveTarget] searches for a free 50 ms slot. */
    const val MOVE_SEARCH_RADIUS_MS = 200L

    /**
     * Auto-key write. [playheadMs] is the project playhead; [newTransform]
     * the post-gesture/post-slider transform. Pure: returns the updated
     * [OverlayItem] (or an equal one — the caller's no-op guard skips empty
     * undo steps).
     */
    fun applyTransformEdit(
        overlay: OverlayItem,
        playheadMs: Long,
        newTransform: OverlayTransform,
    ): OverlayItem {
        val safe = newTransform.withClampedValues()
        if (overlay.keyframes.isEmpty()) {
            return overlay.copy(transform = safe)
        }
        val existing = keyframeAt(overlay.keyframes, playheadMs, TOLERANCE_MS)
        return if (existing != null) {
            // Update in place, preserving the keyframe's exact time.
            val updated = existing.copy(
                x = safe.x,
                y = safe.y,
                scale = safe.scale,
                rotationDeg = safe.rotationDeg,
                opacity = safe.opacity,
            )
            overlay.copy(
                keyframes = overlay.keyframes.map {
                    if (it.timeMs == existing.timeMs) updated else it
                },
            )
        } else {
            val snapped = KeyframeEvaluator.snapTime(playheadMs, SNAP_MS)
            val created = Keyframe(
                timeMs = snapped,
                x = safe.x,
                y = safe.y,
                scale = safe.scale,
                rotationDeg = safe.rotationDeg,
                opacity = safe.opacity,
            )
            // Same replace-at-identical-time rule as addKeyframeAtPlayhead.
            overlay.copy(
                keyframes = (overlay.keyframes.filterNot { it.timeMs == snapped } + created)
                    .sortedBy { it.timeMs },
            )
        }
    }

    /**
     * F2 collision rule for dragging a keyframe marker (documented):
     *
     * 1. the requested time is snapped to the [SNAP_MS] grid;
     * 2. if that slot is FREE, it wins;
     * 3. if it is occupied by ANOTHER keyframe, the nearest free slot is
     *    searched alternately +/-50, +/-100, +/-150, +/-[MOVE_SEARCH_RADIUS_MS]
     *    (closest candidate first) — also clamped to >= 0;
     * 4. if every candidate inside the radius is occupied the move is
     *    REJECTED (null): the marker springs back and NO undo step is
     *    created. Moving onto another keyframe can never DELETE it (the
     *    pre-v0.5.0 moveKeyframe overwrote the target — data loss bug).
     *
     * "Reordering" is not a separate state: keyframes are a time-sorted list,
     * so a move that passes a neighbour simply sorts after it.
     *
     * @return the resolved target time, or null when the move must be rejected.
     */
    fun resolveMoveTarget(
        keyframes: List<Keyframe>,
        fromMs: Long,
        requestedMs: Long,
    ): Long? {
        val snapped = KeyframeEvaluator.snapTime(requestedMs, SNAP_MS)
        val occupied = keyframes.map { it.timeMs }.toSet() - setOf(fromMs)
        fun free(t: Long): Boolean = t >= 0 && t !in occupied
        if (free(snapped)) return snapped
        var step = SNAP_MS
        while (step <= MOVE_SEARCH_RADIUS_MS) {
            val up = snapped + step
            val down = snapped - step
            val downFree = free(down)
            val upFree = free(up)
            when {
                // Same |offset|: prefer the later slot (drag direction is
                // usually forward; deterministic either way).
                upFree && downFree -> return up
                upFree -> return up
                downFree -> return down
            }
            step += SNAP_MS
        }
        return null
    }

    /**
     * F3 navigation: the nearest keyframe strictly BEFORE the playhead
     * (beyond [TOLERANCE_MS], so stepping back from "on a keyframe" moves to
     * the previous one, not to itself).
     */
    fun previousKeyframe(keyframes: List<Keyframe>, playheadMs: Long): Keyframe? =
        keyframes
            .filter { it.timeMs < playheadMs - TOLERANCE_MS }
            .maxByOrNull { it.timeMs }

    /** F3 navigation: nearest keyframe strictly AFTER the playhead. */
    fun nextKeyframe(keyframes: List<Keyframe>, playheadMs: Long): Keyframe? =
        keyframes
            .filter { it.timeMs > playheadMs + TOLERANCE_MS }
            .minByOrNull { it.timeMs }

    /**
     * F4 indicator: the keyframe the playhead is currently "on" (within
     * [TOLERANCE_MS]), or null when the playhead sits between keyframes.
     */
    fun keyframeAt(
        keyframes: List<Keyframe>,
        playheadMs: Long,
        toleranceMs: Long = TOLERANCE_MS,
    ): Keyframe? = keyframes.minByOrNull {
        kotlin.math.abs(it.timeMs - playheadMs)
    }?.takeIf {
        kotlin.math.abs(it.timeMs - playheadMs) <= toleranceMs
    }

    /** Convenience wrapper on [keyframeAt] for an overlay. */
    fun keyframeAtPlayhead(overlay: OverlayItem, playheadMs: Long): Keyframe? =
        keyframeAt(overlay.keyframes, playheadMs)
}

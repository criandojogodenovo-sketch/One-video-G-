package com.onevideog.editor.editor

import com.onevideog.editor.data.OverlayItem

/**
 * Deterministic interval lane-packing for the overlay timeline lanes.
 *
 * Contract (unit-tested in LanePackerTest):
 *  - Clips are sorted by (startMs, endMs, zOrder, id) — a stable, total order,
 *    so packing never depends on list order or object identity.
 *  - A lane is reused only when the clip already placed in it ends at or
 *    before the next clip starts. Boundary rule: end == start counts as
 *    NON-overlapping (the clips touch) and share the lane.
 *  - Any overlap allocates the next free lane below.
 *  - Packing NEVER changes an overlay's temporal start/end — it only assigns
 *    a display row. Draw order stays an explicit z-order property (Media3
 *    OverlayEffect FIFO: highest zOrder rendered last = on top).
 */
object LanePacker {

    /** One overlay placed on one timeline lane. */
    data class Assignment(val overlay: OverlayItem, val lane: Int)

    /**
     * Packs valid intervals (end > start) into lanes. Zero-length and
     * negative intervals are ignored (they render nothing and would corrupt
     * lane reuse).
     */
    fun pack(overlays: List<OverlayItem>): List<Assignment> {
        val sorted = overlays
            .filter { it.endMs > it.startMs }
            .sortedWith(
                compareBy({ it.startMs }, { it.endMs }, { it.zOrder }, { it.id }),
            )
        val laneEnds = mutableListOf<Long>()
        val result = mutableListOf<Assignment>()
        for (overlay in sorted) {
            val lane = laneEnds.indexOfFirst { it <= overlay.startMs }
            if (lane >= 0) {
                laneEnds[lane] = overlay.endMs
                result += Assignment(overlay, lane)
            } else {
                laneEnds += overlay.endMs
                result += Assignment(overlay, laneEnds.lastIndex)
            }
        }
        return result
    }

    /** Number of lanes required to display [overlays] without visual overlap. */
    fun laneCount(overlays: List<OverlayItem>): Int =
        pack(overlays).maxOfOrNull { it.lane + 1 } ?: 0
}

package com.onevideog.editor

import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind
import com.onevideog.editor.editor.LanePacker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the deterministic interval lane-packing (P0 timeline
 * lanes). Covers the boundary rule (end == start shares a lane), overlap
 * allocation, tie-breaking determinism, zero/negative interval rejection and
 * lane-count reporting.
 */
class LanePackerTest {

    private fun overlay(
        id: String,
        startMs: Long,
        endMs: Long,
        zOrder: Int = 0,
    ) = OverlayItem(
        id = id,
        kind = OverlayKind.TEXT,
        startMs = startMs,
        endMs = endMs,
        zOrder = zOrder,
        text = id,
    )

    @Test
    fun `non-overlapping overlays share one lane`() {
        val packed = LanePacker.pack(
            listOf(
                overlay("a", 0, 1000),
                overlay("b", 1000, 2000),
                overlay("c", 2000, 3000),
            ),
        )
        assertEquals(listOf(0, 0, 0), packed.map { it.lane })
    }

    @Test
    fun `boundary rule - end equals next start shares the lane`() {
        val packed = LanePacker.pack(
            listOf(overlay("a", 0, 1000), overlay("b", 1000, 2000)),
        )
        assertEquals(0, packed[0].lane)
        assertEquals(0, packed[1].lane)
    }

    @Test
    fun `overlapping overlays get separate lanes`() {
        val packed = LanePacker.pack(
            listOf(overlay("a", 0, 2000), overlay("b", 1000, 3000)),
        )
        assertEquals(0, packed.first { it.overlay.id == "a" }.lane)
        assertEquals(1, packed.first { it.overlay.id == "b" }.lane)
    }

    @Test
    fun `lane is reused after overlap ends`() {
        val packed = LanePacker.pack(
            listOf(
                overlay("a", 0, 2000),
                overlay("b", 1000, 3000),
                overlay("c", 2500, 4000),
            ),
        )
        // c overlaps b (1000-3000) but not a (0-2000) -> reuses a's lane? The
        // algorithm reuses the FIRST lane whose end <= start; a ends 2000 <=
        // 2500 -> lane 0.
        assertEquals(0, packed.first { it.overlay.id == "c" }.lane)
    }

    @Test
    fun `nested intervals pack deterministically`() {
        val packed = LanePacker.pack(
            listOf(
                overlay("outer", 0, 5000),
                overlay("inner1", 1000, 2000),
                overlay("inner2", 2100, 3000),
            ),
        )
        assertEquals(0, packed.first { it.overlay.id == "outer" }.lane)
        assertEquals(1, packed.first { it.overlay.id == "inner1" }.lane)
        assertEquals(1, packed.first { it.overlay.id == "inner2" }.lane)
    }

    @Test
    fun `packing is order independent (stable tie-breaking)`() {
        val a = listOf(overlay("b", 1000, 2000), overlay("a", 1000, 2000))
        val b = listOf(overlay("a", 1000, 2000), overlay("b", 1000, 2000))
        val pa = LanePacker.pack(a)
        val pb = LanePacker.pack(b)
        assertEquals(
            pa.map { it.overlay.id to it.lane },
            pb.map { it.overlay.id to it.lane },
        )
        // Identical intervals: tie-break by id -> 'a' gets the first lane.
        assertEquals(0, pa.first { it.overlay.id == "a" }.lane)
        assertEquals(1, pa.first { it.overlay.id == "b" }.lane)
    }

    @Test
    fun `zero and negative intervals are ignored`() {
        val packed = LanePacker.pack(
            listOf(
                overlay("zero", 1000, 1000),
                overlay("negative", 2000, 1000),
                overlay("valid", 0, 500),
            ),
        )
        assertEquals(1, packed.size)
        assertEquals("valid", packed[0].overlay.id)
        assertEquals(0, LanePacker.laneCount(listOf(overlay("zero", 5, 5))))
    }

    @Test
    fun `lane count matches deepest lane`() {
        val overlays = listOf(
            overlay("a", 0, 3000),
            overlay("b", 0, 3000, zOrder = 1),
            overlay("c", 0, 3000, zOrder = 2),
        )
        assertEquals(3, LanePacker.laneCount(overlays))
    }

    @Test
    fun `packing never changes temporal values`() {
        val overlays = listOf(overlay("a", 700, 3200), overlay("b", 0, 1500))
        val packed = LanePacker.pack(overlays)
        assertTrue(packed.all { assignment ->
            val original = overlays.first { it.id == assignment.overlay.id }
            original.startMs == assignment.overlay.startMs &&
                original.endMs == assignment.overlay.endMs
        })
    }
}

package com.onevideog.editor

import com.onevideog.editor.data.AudioClipItem
import com.onevideog.editor.editor.AudioLanePacker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.8.0 (P2) — the audio lane-packing contract: overlapping clips land on
 * different lanes (audio OVERLAPS and mixes — the lanes are display-only),
 * non-overlapping clips share lanes, and packing never changes any clip's
 * temporal placement nor depends on input order. The lane is NEVER stored
 * in the model — these tests pin the pure computation the timeline renders.
 */
class AudioLanePackerTest {

    private fun clip(
        id: String,
        startMs: Long,
        durationMs: Long = 1_000,
    ) = AudioClipItem(
        id = id, uri = "u", displayName = id, sourceDurationMs = durationMs,
        startMs = startMs, trimStartMs = 0, trimEndMs = durationMs,
    )

    @Test
    fun `empty list packs to zero lanes`() {
        assertTrue(AudioLanePacker.pack(emptyList()).isEmpty())
        assertEquals(0, AudioLanePacker.laneCount(emptyList()))
    }

    @Test
    fun `non-overlapping clips share one lane`() {
        val assignments = AudioLanePacker.pack(
            listOf(clip("a", 0, 1_000), clip("b", 1_000, 1_000), clip("c", 5_000, 1_000)),
        )
        assertEquals(setOf(0), assignments.map { it.lane }.toSet())
        assertEquals(1, AudioLanePacker.laneCount(assignments.map { it.clip }))
    }

    @Test
    fun `touching clips (end equals start) share the lane`() {
        val assignments = AudioLanePacker.pack(
            listOf(clip("a", 0, 1_000), clip("b", 1_000, 1_000)),
        )
        assertTrue(assignments.all { it.lane == 0 })
    }

    @Test
    fun `overlapping clips get different lanes`() {
        val assignments = AudioLanePacker.pack(
            listOf(clip("a", 0, 2_000), clip("b", 1_000, 2_000)),
        )
        assertEquals(0, assignments.first { it.clip.id == "a" }.lane)
        assertEquals(1, assignments.first { it.clip.id == "b" }.lane)
        assertEquals(2, AudioLanePacker.laneCount(assignments.map { it.clip }))
    }

    @Test
    fun `a lane is reused after its clip ends`() {
        val assignments = AudioLanePacker.pack(
            listOf(
                clip("a", 0, 1_000),
                clip("b", 500, 1_000), // overlaps a → lane 1
                clip("c", 1_500, 1_000), // after a AND b → lane 0 (reused)
            ),
        )
        assertEquals(0, assignments.first { it.clip.id == "a" }.lane)
        assertEquals(1, assignments.first { it.clip.id == "b" }.lane)
        assertEquals(0, assignments.first { it.clip.id == "c" }.lane)
    }

    @Test
    fun `packing is independent of input order`() {
        val a = listOf(clip("a", 0, 2_000), clip("b", 1_000, 2_000), clip("c", 2_500, 1_000))
        val b = listOf(clip("c", 2_500, 1_000), clip("b", 1_000, 2_000), clip("a", 0, 2_000))
        val pa = AudioLanePacker.pack(a).associate { it.clip.id to it.lane }
        val pb = AudioLanePacker.pack(b).associate { it.clip.id to it.lane }
        assertEquals(pa, pb)
    }

    @Test
    fun `packing never changes temporal placement`() {
        val clips = listOf(clip("a", 0, 2_000), clip("b", 1_000, 2_000))
        AudioLanePacker.pack(clips).forEach { assignment ->
            assertEquals(assignment.clip, clips.first { it.id == assignment.clip.id })
        }
    }

    @Test
    fun `zero-length clips are ignored`() {
        val assignments = AudioLanePacker.pack(
            listOf(clip("a", 0, 0), clip("b", 100, 1_000)),
        )
        assertEquals(listOf("b"), assignments.map { it.clip.id })
    }

    @Test
    fun `three-way overlap needs three lanes`() {
        val assignments = AudioLanePacker.pack(
            listOf(
                clip("a", 0, 3_000),
                clip("b", 1_000, 3_000),
                clip("c", 2_000, 3_000),
            ),
        )
        assertEquals(3, AudioLanePacker.laneCount(assignments.map { it.clip }))
    }
}

package com.onevideog.editor

import com.onevideog.editor.data.AudioClipItem
import com.onevideog.editor.editor.AudioCrossfade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.8.0 (P4) — the pure crossfade rule: the button/gesture creates the
 * overlap and sets BOTH fades; the overlap region then crossfades
 * automatically through the linear fade envelopes (the simplified, documented
 * model — no equal-power curves in this version).
 */
class AudioCrossfadeTest {

    private fun clip(
        id: String,
        startMs: Long,
        durationMs: Long = 4_000,
        fadeInMs: Long = 0,
        fadeOutMs: Long = 0,
    ) = AudioClipItem(
        id = id, uri = "u", displayName = id, sourceDurationMs = durationMs,
        startMs = startMs, trimStartMs = 0, trimEndMs = durationMs,
        fadeInMs = fadeInMs, fadeOutMs = fadeOutMs,
    )

    // ------------------------------------------------------------- neighbour

    @Test
    fun `neighbour prefers the clip before`() {
        val a = clip("a", 0)
        val b = clip("b", 5_000)
        assertEquals(a, AudioCrossfade.neighbour(listOf(a, b), b))
    }

    @Test
    fun `neighbour falls back to the clip after`() {
        val a = clip("a", 0)
        val b = clip("b", 5_000)
        assertEquals(b, AudioCrossfade.neighbour(listOf(a, b), a))
    }

    @Test
    fun `a single clip has no neighbour`() {
        val a = clip("a", 0)
        assertNull(AudioCrossfade.neighbour(listOf(a), a))
    }

    @Test
    fun `neighbour picks the CLOSEST clip before`() {
        val far = clip("far", 0, durationMs = 1_000)
        val near = clip("near", 3_000, durationMs = 1_000)
        val selected = clip("sel", 6_000)
        assertEquals(near, AudioCrossfade.neighbour(listOf(far, near, selected), selected))
    }

    // ------------------------------------------------------------------ plan

    @Test
    fun `plan creates the overlap and sets both fades`() {
        val earlier = clip("a", 0, durationMs = 4_000) // 0..4000
        val later = clip("b", 6_000, durationMs = 4_000) // 6000..10000
        val plan = AudioCrossfade.plan(earlier, later, requestedMs = 800)
        assertEquals(800, plan.overlapMs)
        assertEquals(800, plan.earlier.fadeOutMs)
        assertEquals(800, plan.later.fadeInMs)
        // The later clip moved so its start sits 800 ms before the earlier end.
        assertEquals(4_000 - 800, plan.later.startMs)
        assertEquals(0, plan.earlier.startMs)
    }

    @Test
    fun `an existing larger overlap is kept`() {
        val earlier = clip("a", 0, durationMs = 4_000)
        val later = clip("b", 3_000, durationMs = 4_000) // overlaps by 1000 already
        val plan = AudioCrossfade.plan(earlier, later, requestedMs = 800)
        assertEquals(800, plan.overlapMs)
        assertEquals(3_000, plan.later.startMs) // not moved (overlap >= requested)
    }

    @Test
    fun `the overlap never exceeds the shorter clip`() {
        val earlier = clip("a", 0, durationMs = 4_000)
        val later = clip("b", 4_500, durationMs = 200)
        val plan = AudioCrossfade.plan(earlier, later, requestedMs = 800)
        assertTrue(plan.overlapMs <= 200)
        assertEquals(200, plan.overlapMs)
        // The later clip was pulled to end exactly with the earlier one.
        assertEquals(4_000 - 200, plan.later.startMs)
    }

    @Test
    fun `the later clip never moves before zero`() {
        val earlier = clip("a", 0, durationMs = 300)
        val later = clip("b", 1_000, durationMs = 4_000)
        val plan = AudioCrossfade.plan(earlier, later, requestedMs = 800)
        // Overlap clamped to the earlier clip's 300 ms duration.
        assertEquals(300, plan.overlapMs)
        assertEquals(0, plan.later.startMs)
        assertEquals(300, plan.earlier.fadeOutMs)
        assertEquals(300, plan.later.fadeInMs)
    }

    @Test
    fun `plan replaces manual fades on both clips`() {
        val earlier = clip("a", 0, fadeOutMs = 2_000)
        val later = clip("b", 5_000, fadeInMs = 2_000)
        val plan = AudioCrossfade.plan(earlier, later, requestedMs = 500)
        assertEquals(500, plan.earlier.fadeOutMs)
        assertEquals(500, plan.later.fadeInMs)
    }

    @Test
    fun `the resulting overlap crossfades through the envelope math`() {
        // The P4 rule: overlapping clips with fades crossfade automatically —
        // the sum of both envelopes is NOT constant-power (documented linear
        // simplification) but both sides ARE the shared AudioGainMath.
        val overlap = 1_000L
        val earlier = clip("a", 0, durationMs = 4_000)
        val later = clip("b", 3_000, durationMs = 4_000)
        val plan = AudioCrossfade.plan(earlier, later, requestedMs = overlap)
        // Mid-overlap (project time 3_500 → earlier local 3_500, later local 500):
        val earlierGain = com.onevideog.editor.editor.AudioGainMath.fadeEnvelope(
            3_500, plan.earlier.trimmedDurationMs, plan.earlier.fadeInMs, plan.earlier.fadeOutMs,
        )
        val laterGain = com.onevideog.editor.editor.AudioGainMath.fadeEnvelope(
            500, plan.later.trimmedDurationMs, plan.later.fadeInMs, plan.later.fadeOutMs,
        )
        assertEquals(0.5f, earlierGain, 1e-3f)
        assertEquals(0.5f, laterGain, 1e-3f)
        // Linear dips below 1 mid-overlap — the accepted simplification.
        assertTrue(earlierGain + laterGain < 1.2f)
    }
}

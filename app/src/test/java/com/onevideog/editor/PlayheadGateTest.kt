package com.onevideog.editor

import com.onevideog.editor.editor.PlayheadGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.4 regression tests for the preview playhead staleness gate.
 *
 * Device evidence (realme RMX3624): SCRUB_START playheadMs=10163 and
 * SCRUB_END playheadMs=120, after which the playhead snapped back to the
 * pre-gesture position. Mechanism: endScrub resumes playback while the async
 * seek is still pending, so the frame publisher read the OLD decoder
 * position (~10163 ms) and wrote it over the user's playhead (120 ms). The
 * gate must skip publishes whose mapped player position is far from the
 * authoritative playhead and resume once the seek lands.
 */
class PlayheadGateTest {

    @Test
    fun `the device failure pair is rejected - stale pre-seek position`() {
        // Authoritative playhead left by the user's finger: 120 ms.
        // Player still reports the pre-seek decoder position ~10163 ms.
        assertFalse(PlayheadGate.shouldPublish(authoritativePlayheadMs = 120L, playerMappedMs = 10_163L))
    }

    @Test
    fun `post-seek position publishes`() {
        assertTrue(PlayheadGate.shouldPublish(authoritativePlayheadMs = 120L, playerMappedMs = 121L))
        assertTrue(PlayheadGate.shouldPublish(authoritativePlayheadMs = 120L, playerMappedMs = 150L))
    }

    @Test
    fun `steady playback drift publishes at start middle and end`() {
        // Per-frame drift is ~33 ms at the 30 fps publish cadence; all three
        // representative positions must publish without any seek in flight.
        for (pair in listOf(0L to 20L, 50_000L to 50_030L, 101_000L to 101_030L)) {
            assertTrue(
                "authoritative=${pair.first} player=${pair.second}",
                PlayheadGate.shouldPublish(pair.first, pair.second),
            )
        }
    }

    @Test
    fun `gap at exactly the tolerance boundary still publishes`() {
        assertTrue(
            PlayheadGate.shouldPublish(
                authoritativePlayheadMs = 1_000L,
                playerMappedMs = 1_000L + PlayheadGate.MAX_TRUST_DELTA_MS,
            ),
        )
        assertTrue(
            PlayheadGate.shouldPublish(
                authoritativePlayheadMs = 1_000L,
                playerMappedMs = 1_000L - PlayheadGate.MAX_TRUST_DELTA_MS,
            ),
        )
    }

    @Test
    fun `gap beyond the tolerance is rejected in both directions`() {
        val delta = PlayheadGate.MAX_TRUST_DELTA_MS + 1
        assertFalse(PlayheadGate.shouldPublish(1_000L, 1_000L + delta))
        assertFalse(PlayheadGate.shouldPublish(1_000L, 1_000L - delta))
    }

    @Test
    fun `clip transition jump is rejected until the next item seeks`() {
        // Publisher auto-advance moved the playhead to the next clip start
        // while the decoder is still finishing the previous item (~1.6 s
        // behind — bigger than the trust delta).
        assertFalse(PlayheadGate.shouldPublish(authoritativePlayheadMs = 60_000L, playerMappedMs = 58_400L))
        // After the transition lands, publishing resumes.
        assertTrue(PlayheadGate.shouldPublish(authoritativePlayheadMs = 60_000L, playerMappedMs = 60_010L))
    }
}

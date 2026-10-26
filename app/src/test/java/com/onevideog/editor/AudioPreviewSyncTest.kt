package com.onevideog.editor.editor

import com.onevideog.editor.data.AudioClipItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.4 (BUG 2 — device op=pvw-8c2a1a5d): the preview audio sync contract,
 * pinned at the pure level with an injected clock.
 *
 * Spec tests:
 *  - a 0 -> 20 s seek leaves an offset <= 150 ms IMMEDIATELY after the
 *    commit (one decision, one seek — no wait for the periodic tick);
 *  - drift during playback never exceeds 150 ms without a correction;
 *  - AUDIO_SYNC evidence only on correction or residual drift > 100 ms.
 *  - the stale-window bug (edit without player rebuild) is locked: a window
 *    signature mismatch orders the rebuild.
 */
class AudioPreviewSyncTest {

    private fun clip(
        startMs: Long = 0,
        trimStart: Long = 0,
        trimEnd: Long = 30_000,
        loop: Boolean = false,
    ) = AudioClipItem(
        id = "a",
        uri = "content://audio",
        displayName = "d",
        sourceDurationMs = 60_000,
        startMs = startMs,
        trimStartMs = trimStart,
        trimEndMs = trimEnd,
        loop = loop,
    )

    private fun snapshot(
        positionMs: Long,
        clip: AudioClipItem = clip(),
        isPlaying: Boolean = false,
    ) = AudioPreviewSync.PlayerSnapshot(
        windowSignature = AudioPreviewSync.windowSignatureOf(clip),
        positionMs = positionMs,
        isPlaying = isPlaying,
    )

    // ------------------------------------------------------------ spec tests

    @Test
    fun `seek 0 to 20s leaves offset within 150ms immediately after the commit`() {
        val target = 20_000L
        val decision = AudioPreviewSync.decide(
            clip(), target, videoDurationMs = 60_000,
            playing = false, snapshot = snapshot(0L),
        )
        // ONE decision corrects fully: the seek lands exactly on the target.
        assertEquals(target, decision.seekToMs)
        assertEquals(target, decision.driftMs) // correction evidence
        // Applying the decision: the offset right after the commit:
        val applied = AudioPreviewSync.decide(
            clip(), target, 60_000, false, snapshot(decision.seekToMs!!),
        )
        val residualOffset = kotlin.math.abs(decision.seekToMs - target)
        assertTrue(residualOffset <= AudioPreviewSync.DRIFT_CORRECT_MS)
        assertNull(applied.seekToMs) // no second correction pending
    }

    @Test
    fun `device case seek to 20690 with player at 10608 corrects in one pass`() {
        val decision = AudioPreviewSync.decide(
            clip(), 20_690L, 60_000, false, snapshot(10_608L),
        )
        assertEquals(20_690L, decision.seekToMs)
        assertEquals(10_082L, decision.driftMs)
    }

    @Test
    fun `playback drift above 150ms is corrected immediately`() {
        val decision = AudioPreviewSync.decide(
            clip(), 20_000L, 60_000, playing = true,
            snapshot = snapshot(20_151L, isPlaying = true),
        )
        assertEquals(20_000L, decision.seekToMs)
        assertNull(decision.play) // both playing — leave the play state alone
    }

    @Test
    fun `playback drift of exactly 150ms is tolerated (no churn)`() {
        val decision = AudioPreviewSync.decide(
            clip(), 20_000L, 60_000, playing = true,
            snapshot = snapshot(20_150L, isPlaying = true),
        )
        assertNull(decision.seekToMs) // threshold is strict (> 150 corrects)
        assertEquals(150L, decision.driftMs) // residual drift evidence only
    }

    // ------------------------------------------------------- logging contract

    @Test
    fun `residual drift above 100ms is evidence, below stays silent`() {
        val loud = AudioPreviewSync.decide(
            clip(), 20_000L, 60_000, false, snapshot(20_120L),
        )
        assertEquals(120L, loud.driftMs) // no correction (<= 150), but logged
        val quiet = AudioPreviewSync.decide(
            clip(), 20_000L, 60_000, false, snapshot(20_090L),
        )
        assertNull(quiet.driftMs)
        assertNull(quiet.seekToMs)
    }

    @Test
    fun `injected-clock rate limit gates the residual evidence only`() {
        assertTrue(AudioPreviewSync.shouldLogAudioSync(3_000L, 0L))
        assertFalse(AudioPreviewSync.shouldLogAudioSync(2_999L, 0L))
        assertFalse(AudioPreviewSync.shouldLogAudioSync(3_500L, 1_000L))
        assertTrue(AudioPreviewSync.shouldLogAudioSync(4_001L, 1_000L))
    }

    // --------------------------------------------------- stale window (BUG 2)

    @Test
    fun `edited clip window orders a rebuild with the correct position`() {
        // The user trimmed the clip from 0..30000 to 5000..30000 — the live
        // player still carries the OLD window.
        val edited = clip(trimStart = 5_000)
        val oldSig = AudioPreviewSync.windowSignatureOf(clip())
        val decision = AudioPreviewSync.decide(
            edited, 10_000L, 60_000, false,
            AudioPreviewSync.PlayerSnapshot(
                windowSignature = oldSig,
                positionMs = 10_000L,
                isPlaying = false,
            ),
        )
        assertTrue(decision.rebuildWindow)
        // Window-local target for the NEW window at playhead 10 s = 10_000.
        assertEquals(10_000L, decision.seekToMs)
        // The rebuild IS a correction -> evidence (position drift is 0; the
        // CONTENT was offset by the stale window, not the clock).
        assertEquals(0L, decision.driftMs)
    }

    @Test
    fun `same window never rebuilds`() {
        val decision = AudioPreviewSync.decide(
            clip(), 1_000L, 60_000, false, snapshot(1_000L),
        )
        assertFalse(decision.rebuildWindow)
        assertNull(decision.seekToMs)
    }

    @Test
    fun `looping clip rebuilds when the loop flag flips`() {
        val looped = clip(loop = true)
        val decision = AudioPreviewSync.decide(
            looped, 1_000L, 60_000, false,
            AudioPreviewSync.PlayerSnapshot(
                windowSignature = AudioPreviewSync.windowSignatureOf(clip()),
                positionMs = 1_000L,
                isPlaying = false,
            ),
        )
        assertTrue(decision.rebuildWindow)
    }

    // ------------------------------------------------------ play/pause paths

    @Test
    fun `silent window pauses without seeking`() {
        // Before the clip start -> silent. An AUDIBLE player must pause.
        val decision = AudioPreviewSync.decide(
            clip(startMs = 5_000), 1_000L, 60_000, true,
            snapshot(0L, isPlaying = true),
        )
        assertNull(decision.seekToMs)
        assertEquals(false, decision.play)
    }

    @Test
    fun `audio never sounds past the video end (v0_2_5 contract intact)`() {
        val decision = AudioPreviewSync.decide(
            clip(), 61_000L, videoDurationMs = 60_000,
            playing = true, snapshot = snapshot(30_000L, isPlaying = true),
        )
        assertNull(decision.seekToMs)
        assertEquals(false, decision.play)
    }

    @Test
    fun `fresh player decision starts playing when the preview plays`() {
        val decision = AudioPreviewSync.decide(
            clip(), 4_000L, 60_000, playing = true, snapshot = null,
        )
        assertEquals(4_000L, decision.seekToMs)
        assertEquals(true, decision.play)
        assertFalse(decision.rebuildWindow)
    }
}

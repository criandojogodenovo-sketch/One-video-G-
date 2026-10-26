package com.onevideog.editor

import androidx.media3.common.Player
import com.onevideog.editor.editor.OverlayDrawGate
import com.onevideog.editor.editor.PLAYER_RECOVERY_MAX_ATTEMPTS
import com.onevideog.editor.editor.PlaybackErrorInfo
import com.onevideog.editor.editor.PlaybackErrorPolicy
import com.onevideog.editor.editor.PreviewProcessingPolicy
import com.onevideog.editor.editor.PreviewRecoveryBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.1 — the pure preview-resilience policies (device-reported bugs on
 * realme RMX3624 / Mali-G57, Android 13):
 *
 *  - BUG B: ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED (7001) surfaced with
 *    the generic "file missing / undecodable" message — the classification
 *    must separate the GL/video-frame-processing family (7000/7001) from
 *    every other code;
 *  - BUG B: the recovery budget drained forever ("editor morto até reabrir
 *    o projeto") — a manual reload must re-arm it;
 *  - BUG C: the overlay gate resumed drawing on a DEAD player
 *    (playerState=IDLE at 21:41:42.905 in the device report) — IDLE must
 *    suppress exactly like BUFFERING.
 *
 * v0.9.3 — the processing-fault policy (the v0.9.2 report: the same blur
 * chain failed 3x and the recovery churn then threw the detach TIMEOUT —
 * a generic code — leaving the player dead in IDLE):
 *
 *  - the 1st processing failure gets exactly ONE quick retry;
 *  - the REPEAT suspends the preview GL effects immediately (no third
 *    attempt with the same configuration);
 *  - a TIMEOUT while a processing fault is suspected suspends too;
 *  - a READY (or a manual reload) resets the counter.
 */
class PreviewResilienceTest {

    // ------------------------------------------------ PlaybackErrorPolicy

    @Test
    fun `the video frame processing family maps to its own category`() {
        assertEquals(
            PlaybackErrorPolicy.Kind.EFFECT_PROCESSING,
            PlaybackErrorPolicy.classify(7001), // ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED
        )
        assertEquals(
            PlaybackErrorPolicy.Kind.EFFECT_PROCESSING,
            PlaybackErrorPolicy.classify(7000), // ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED
        )
        assertEquals(
            PlaybackErrorPolicy.Kind.EFFECT_PROCESSING,
            PlaybackErrorPolicy.classify(PlaybackErrorInfo(7001, "ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED")),
        )
    }

    @Test
    fun `every other error keeps the generic category`() {
        // Decode/source errors (the "file missing / undecodable" family),
        // runtime and unspecified codes all stay generic.
        for (code in listOf(
            2001, // ERROR_CODE_IO_UNSPECIFIED family (2000..2999)
            3002, // decoding family
            1000, // ERROR_CODE_UNSPECIFIED family
            1002, // ERROR_CODE_REMOTE_ERROR
            -1,   // defensive: unknown negative
        )) {
            assertEquals(
                "code $code must be GENERIC",
                PlaybackErrorPolicy.Kind.GENERIC,
                PlaybackErrorPolicy.classify(code),
            )
        }
    }

    // ---------------------------------------------------- OverlayDrawGate

    @Test
    fun `gate suppresses on IDLE even with video geometry (BUG C)`() {
        // The device evidence: gate resumed with playerState=IDLE on the
        // dead player — no real frame exists behind the overlays.
        assertFalse(
            OverlayDrawGate.shouldDraw(
                hasClips = true,
                hasVideoGeometry = true,
                playerState = Player.STATE_IDLE,
            ),
        )
    }

    @Test
    fun `gate suppresses on BUFFERING and without geometry`() {
        assertFalse(
            OverlayDrawGate.shouldDraw(true, true, Player.STATE_BUFFERING),
        )
        assertFalse(
            OverlayDrawGate.shouldDraw(true, false, Player.STATE_READY),
        )
    }

    @Test
    fun `gate draws on READY and ENDED with geometry`() {
        // READY: the live frame is real. ENDED: the LAST real frame is still
        // on screen — overlays remain legitimate there.
        assertTrue(
            OverlayDrawGate.shouldDraw(true, true, Player.STATE_READY),
        )
        assertTrue(
            OverlayDrawGate.shouldDraw(true, true, Player.STATE_ENDED),
        )
    }

    @Test
    fun `clip-less projects always draw (no video player involved)`() {
        assertTrue(
            OverlayDrawGate.shouldDraw(false, false, Player.STATE_IDLE),
        )
        assertTrue(
            OverlayDrawGate.shouldDraw(false, false, Player.STATE_BUFFERING),
        )
    }

    // ---------------------------------------------- PreviewRecoveryBudget

    @Test
    fun `budget is bounded and blocks after max attempts`() {
        val budget = PreviewRecoveryBudget()
        assertEquals(PLAYER_RECOVERY_MAX_ATTEMPTS, 3)
        for (i in 0 until PLAYER_RECOVERY_MAX_ATTEMPTS) {
            assertTrue("attempt ${i + 1} must be allowed", budget.canAttempt())
            budget.onAttempt()
        }
        assertFalse(budget.canAttempt())
        assertEquals(PLAYER_RECOVERY_MAX_ATTEMPTS, budget.attempts)
    }

    @Test
    fun `READY refunds the budget (healthy session never drains)`() {
        val budget = PreviewRecoveryBudget()
        budget.onAttempt()
        budget.onAttempt()
        budget.onReady()
        assertTrue(budget.canAttempt())
        assertEquals(0, budget.attempts)
    }

    @Test
    fun `manual reload re-arms the drained budget (BUG B)`() {
        val budget = PreviewRecoveryBudget()
        repeat(PLAYER_RECOVERY_MAX_ATTEMPTS) { budget.onAttempt() }
        assertFalse(budget.canAttempt())
        // The "Recarregar pré-visualização" action re-arms the automatic
        // budget: the editor can never stay dead until the project is
        // reopened again.
        budget.onManualReload()
        assertTrue(budget.canAttempt())
        assertEquals(0, budget.attempts)
    }

    // ------------------------------------------- PreviewProcessingPolicy

    @Test
    fun `first processing failure allows ONE retry and the repeat suspends (v0.9.3)`() {
        val policy = PreviewProcessingPolicy()
        assertFalse(policy.hasProcessingFault)
        // 1st failure: the quick retry — never suspend yet.
        assertFalse(policy.onProcessingError())
        assertTrue(policy.hasProcessingFault)
        assertFalse(policy.shouldSuspend)
        // The REPEAT: suspend immediately — the same configuration is never
        // re-prepared a third time (the v0.9.2 report burned the budget on
        // exactly this loop).
        assertTrue(policy.onProcessingError())
        assertTrue(policy.shouldSuspend)
    }

    @Test
    fun `timeout during a processing fault suspends (the detach-timeout path)`() {
        val policy = PreviewProcessingPolicy()
        // The poisoned chain failed once; the recovery churn then produced
        // ERROR_CODE_TIMEOUT ("Detaching surface timed out") — a GENERIC
        // code that used to skip the degrade path and leave the player dead.
        assertFalse(policy.onProcessingError())
        assertTrue(policy.hasProcessingFault)
        assertTrue(policy.onTimeoutDuringProcessingFault())
        assertTrue(policy.shouldSuspend)
    }

    @Test
    fun `timeout WITHOUT a processing fault does not suspend`() {
        val policy = PreviewProcessingPolicy()
        assertFalse(policy.hasProcessingFault)
        assertFalse(policy.onTimeoutDuringProcessingFault())
        // A lone timeout (e.g. a normal detach during navigation) is not an
        // effect-chain failure — the budget path handles it.
        assertFalse(policy.shouldSuspend)
    }

    @Test
    fun `READY resets the processing fault counter (healthy session)`() {
        val policy = PreviewProcessingPolicy()
        // Transient GL hiccup: one failure, one quick retry, then READY —
        // the pipeline proved itself, the next failure is a NEW stretch.
        policy.onProcessingError()
        policy.onPipelineProven()
        assertFalse(policy.hasProcessingFault)
        assertFalse(policy.onProcessingError())
        assertFalse(policy.shouldSuspend)
    }

    @Test
    fun `manual reload proves the pipeline again`() {
        val policy = PreviewProcessingPolicy()
        policy.onProcessingError()
        assertTrue(policy.hasProcessingFault)
        policy.onPipelineProven()
        assertFalse(policy.hasProcessingFault)
    }
}

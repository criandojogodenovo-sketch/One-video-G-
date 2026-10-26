package com.onevideog.editor.editor

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player

/**
 * v0.9.1 — pure policy objects for the preview playback error paths
 * (device-reported: realme RMX3624 / Mali-G57, Android 13). All three live
 * in ONE file because they describe the SAME lifecycle: the preview player
 * errors, the budget governs automatic recovery, the error code decides
 * WHAT the user sees, and the draw gate keeps ghost overlays off a dead
 * player. Pure and JVM-tested — the composable only wires them.
 */

/** v0.5.7 (ponto 4): bounded onPlayerError recovery attempts before giving up. */
const val PLAYER_RECOVERY_MAX_ATTEMPTS = 3

/**
 * The sanitized playback error handed from the preview player listener to
 * the UI (PlaybackException itself is not unit-testable on the JVM).
 */
data class PlaybackErrorInfo(
    val code: Int,
    val codeName: String,
)

/**
 * v0.9.1 (BUG B): error-code → user-facing category.
 *
 * Device evidence: ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED surfaced with
 * the generic "file missing / undecodable" message — wrong category, wrong
 * remedy. Media3 1.4.1 defines the GL/video-frame-processing family as
 * 7000 (PROCESSOR_INIT_FAILED) and 7001 (PROCESSING_FAILED) — verified in
 * the 1.4.1 sources (PlaybackException). Those get the dedicated
 * "preview failed to process the effects" dialog WITH a reload action;
 * everything else keeps the existing generic message.
 */
object PlaybackErrorPolicy {

    enum class Kind { EFFECT_PROCESSING, GENERIC }

    fun classify(errorCode: Int): Kind = when (errorCode) {
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
        -> Kind.EFFECT_PROCESSING
        else -> Kind.GENERIC
    }

    fun classify(error: PlaybackErrorInfo): Kind = classify(error.code)
}

/**
 * v0.9.1 (BUG C): the overlay-draw gate as a pure decision.
 *
 * Device evidence: the gate resumed drawing with playerState=IDLE (the
 * dead player after the v0.9.0 blur crash) — no real frame exists behind
 * the overlays in IDLE, so drawing them is exactly the ghost geometry the
 * v0.5.9 gate exists to prevent. Suppressed states: BUFFERING (frame on
 * screen is stale) and IDLE (no frame at all) whenever video geometry is
 * required; ENDED keeps drawing (the last real frame is still on screen);
 * projects without clips keep drawing ALWAYS (the clip-less clock drives
 * the playhead; no video player is involved).
 */
object OverlayDrawGate {

    fun shouldDraw(
        hasClips: Boolean,
        hasVideoGeometry: Boolean,
        playerState: Int,
    ): Boolean = !hasClips ||
        (
            hasVideoGeometry &&
                playerState != Player.STATE_BUFFERING &&
                playerState != Player.STATE_IDLE
            )
}

/**
 * v0.9.1 (BUG B): the bounded auto-recovery budget as a testable object.
 *
 * Rules (v0.5.7 base, made explicit):
 *  - every automatic attempt consumes one unit of the budget;
 *  - STATE_READY proves the pipeline works again and refunds the budget;
 *  - a MANUAL reload ("Recarregar pré-visualização") re-arms the budget —
 *    the user explicitly re-entered the loop, so the automatic path gets a
 *    fresh bounded budget instead of staying exhausted forever (the
 *    device-reported "editor morto até reabrir o projeto").
 */
class PreviewRecoveryBudget(private val maxAttempts: Int = PLAYER_RECOVERY_MAX_ATTEMPTS) {

    var attempts: Int = 0
        private set

    fun canAttempt(): Boolean = attempts < maxAttempts

    fun onAttempt() {
        attempts += 1
    }

    /** A READY pipeline works — the healthy-session refund (v0.5.7). */
    fun onReady() {
        attempts = 0
    }

    /** A manual reload re-arms the automatic recovery budget. */
    fun onManualReload() {
        attempts = 0
    }
}

/**
 * v0.9.3 — the processing-fault policy (v0.9.2 device report, realme
 * RMX3624 / Mali-G57): a poisoned GL effect chain failed with
 * ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED and the old policy re-prepared
 * THE SAME configuration three times (burning the whole budget on a
 * deterministically failing setup) while each re-prepare churned the
 * surface — the churn itself then produced "Detaching surface timed out"
 * (a GENERIC code), which skipped the degrade path and left the player
 * dead in IDLE.
 *
 * New policy (user prescription): the SAME error must NOT be retried three
 * times. Consecutive processing failures WITHOUT an intervening READY
 * prove the configuration is poisoned:
 *  - the 1st failure gets ONE quick retry (transient GL context losses do
 *    exist — a single no-churn re-prepare catches them);
 *  - the 2nd (the REPEAT) suspends the preview GL effects immediately —
 *    the honest banner takes over and the player keeps working;
 *  - a TIMEOUT that lands while a processing fault is suspected (the
 *    recovery churn of the poisoned pipeline) suspends too — it is the
 *    same root failure wearing a generic code;
 *  - any READY resets the counter (the pipeline proved itself again).
 *
 * Pure and JVM-tested — the composable only wires it.
 */
class PreviewProcessingPolicy(private val retriesBeforeSuspend: Int = 1) {

    /** Consecutive processing failures without an intervening READY. */
    var processingFaults: Int = 0
        private set

    /** At least one processing failure is awaiting proof (READY/recovery). */
    val hasProcessingFault: Boolean get() = processingFaults > 0

    /**
     * True when the effect chain must be suspended NOW: the repeat failure
     * (faults > retriesBeforeSuspend) or any later failure of the same
     * stretch.
     */
    val shouldSuspend: Boolean get() = processingFaults > retriesBeforeSuspend

    /** Registers one EFFECT_PROCESSING failure; returns [shouldSuspend]. */
    fun onProcessingError(): Boolean {
        processingFaults += 1
        return shouldSuspend
    }

    /**
     * A TIMEOUT observed while a processing fault is suspected — the
     * poisoned-pipeline recovery churn (surface detach storm) is the same
     * root failure. Suspends exactly like a repeated processing error.
     */
    fun onTimeoutDuringProcessingFault(): Boolean {
        // Jump straight past the retry allowance: the repeat already happened.
        processingFaults = maxOf(processingFaults, retriesBeforeSuspend + 1)
        return shouldSuspend
    }

    /** READY (or a manual reload) — the pipeline proved itself again. */
    fun onPipelineProven() {
        processingFaults = 0
    }
}

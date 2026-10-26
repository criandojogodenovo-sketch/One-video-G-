package com.onevideog.editor.editor

import com.onevideog.editor.data.AudioClipItem

/**
 * v0.9.4 (BUG 2 — device report op=pvw-8c2a1a5d): pure sync decisions for
 * the preview audio players. Extracted from [AudioPreviewController] so the
 * contract is JVM-testable (AudioPreviewSyncTest) with an injected clock.
 *
 * Two device-proven problems live here:
 *
 *  1. STALE WINDOW: the preview player was created ONCE per clip with a
 *     `ClippingConfiguration` snapshot. Editing the clip (trim/move) changed
 *     the model but never the player's window, so the audio CONTENT stayed
 *     offset by the edit delta (the reported ~10 s audio-video offset) while
 *     the position math looked aligned — the drift corrector seeked INSIDE
 *     the wrong window forever. [decide] compares the player's window
 *     signature against the clip's CURRENT one and orders a window rebuild
 *     (setMediaItem + prepare, ONE re-init per edit commit) when they differ.
 *
 *  2. TICK-BOUND CORRECTION: the corrector ran on the 100 ms scheduler tick
 *     with a 250 ms tolerance, so an aggressive seek left up to a 250 ms+
 *     offset until the next tick. [decide] now corrects at
 *     [DRIFT_CORRECT_MS] (150 ms) — and the controller is driven by the
 *     commit restarts (seek token / audioClips / videoDurationMs keys), so
 *     every seek/scrub/trim commit syncs IMMEDIATELY without waiting the
 *     periodic tick.
 *
 * Logging contract (end of the AUDIO_SYNC spam): an event is emitted only on
 * a correction, or when the residual drift exceeds [LOG_DRIFT_MS] (rate
 * limited by [shouldLogAudioSync] — injected clock, pure).
 *
 * Untouched contracts: AudioGainMath stays the ONLY gain math (the gain
 * processor profile swap/anchor lives in the controller); audio never
 * extends the project duration (AudioTimeline.windowPositionAt already
 * clamps); the window-local position model (Media3 rebases item timestamps
 * at the clipping start) is unchanged.
 */
object AudioPreviewSync {

    /** Correct (seek) when |drift| exceeds this — playback AND paused. */
    const val DRIFT_CORRECT_MS = 150L

    /** Log AUDIO_SYNC when |drift| exceeds this (even without correction). */
    const val LOG_DRIFT_MS = 100L

    /** Rate limit for the non-correction drift evidence, per clip. */
    const val LOG_INTERVAL_MS = 3_000L

    /** What the controller knows about one live audio player. */
    data class PlayerSnapshot(
        /**
         * The window signature the player's MediaItem was BUILT with
         * (uri|trimStart|trimEnd|loop). A mismatch with the clip's current
         * signature means the user edited the clip — rebuild required.
         */
        val windowSignature: String,
        /** Player clock, window-local ms (same domain as the seek target). */
        val positionMs: Long,
        val isPlaying: Boolean,
    )

    /** What the controller must do for one clip on this sync pass. */
    data class Decision(
        /** Rebuild the MediaItem with the clip's CURRENT window + prepare. */
        val rebuildWindow: Boolean,
        /** Window-local ms to seek to (null = leave the position alone). */
        val seekToMs: Long?,
        /** true = play, false = pause, null = leave as-is. */
        val play: Boolean?,
        /** Non-null -> emit one AUDIO_SYNC evidence event with this drift. */
        val driftMs: Long?,
    )

    /** Identity of the fields the player's MediaItem is built from. */
    fun windowSignatureOf(clip: AudioClipItem): String =
        "${clip.uri}|${clip.trimStartMs}|${clip.trimEndMs}|${clip.loop}"

    /**
     * The sync decision for [clip] at project time [playheadMs].
     *
     * @param playing the desired playback state (the preview play button).
     * @param snapshot null = the player does not exist yet (it will be
     *   created with the CURRENT window, so only play/seek remain).
     */
    fun decide(
        clip: AudioClipItem,
        playheadMs: Long,
        videoDurationMs: Long,
        playing: Boolean,
        snapshot: PlayerSnapshot?,
    ): Decision {
        val windowPos = AudioTimeline.windowPositionAt(clip, playheadMs, videoDurationMs)
        if (windowPos == null) {
            // Silent here (outside the clip window / past the video end):
            // pause if audible, never seek (the v0.2.5/v0.3.0 contracts).
            val pause = snapshot?.isPlaying == true
            return Decision(false, null, if (pause) false else null, null)
        }
        if (snapshot == null) {
            return Decision(false, windowPos, playing, null)
        }
        val rebuild = snapshot.windowSignature != windowSignatureOf(clip)
        val drift = kotlin.math.abs(snapshot.positionMs - windowPos)
        val seek = if (rebuild || drift > DRIFT_CORRECT_MS) windowPos else null
        val play = when {
            playing && !snapshot.isPlaying -> true
            !playing && snapshot.isPlaying -> false
            else -> null
        }
        val driftLog = when {
            seek != null -> drift            // every correction is evidence
            drift > LOG_DRIFT_MS -> drift    // residual drift evidence (rate-limited)
            else -> null
        }
        return Decision(rebuild, seek, play, driftLog)
    }

    /** Injected-clock rate limit for the residual-drift evidence. */
    fun shouldLogAudioSync(nowMs: Long, lastAtMs: Long): Boolean =
        nowMs - lastAtMs >= LOG_INTERVAL_MS
}

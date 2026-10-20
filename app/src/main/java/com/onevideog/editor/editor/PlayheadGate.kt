package com.onevideog.editor.editor

import kotlin.math.abs

/**
 * Staleness gate for the preview playhead publisher (v0.2.4).
 *
 * Device evidence (realme RMX3624, 20/10/2026): a scrub ended at 120 ms and
 * the playhead snapped back to the pre-gesture position (~10163 ms). The
 * mechanism: `endScrub()` seeks the player AND resumes playback; between
 * "playWhenReady = true" and the async seek actually landing on the decoder,
 * `player.isPlaying` is already true while `player.currentPosition` still
 * reports the OLD position — the frame publisher then publishes that stale
 * position back over the user's playhead. The old `!player.isPlaying` guard
 * cannot close this window on slow devices.
 *
 * Fix: only trust the player position when it is CONSISTENT with the
 * authoritative playhead. During steady playback the mapped player position
 * tracks the playhead within a few dozen ms; a transition (seek/clip change)
 * leaves a large gap that the gate skips until the seek lands. Pure math —
 * JVM-tested for start/middle/end and cross-clip cases.
 */
object PlayheadGate {

    /**
     * Max tolerated |Δ| between the authoritative playhead and the mapped
     * player position for a publish to be trusted. Larger gaps mean a seek
     * is still in flight and the position is stale.
     */
    const val MAX_TRUST_DELTA_MS = 1500L

    /**
     * True when [playerMappedMs] (player position mapped to project time) is
     * close enough to [authoritativePlayheadMs] to be published.
     */
    fun shouldPublish(authoritativePlayheadMs: Long, playerMappedMs: Long): Boolean =
        abs(playerMappedMs - authoritativePlayheadMs) <= MAX_TRUST_DELTA_MS
}

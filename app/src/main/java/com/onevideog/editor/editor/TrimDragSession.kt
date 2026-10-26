package com.onevideog.editor.editor

import com.onevideog.editor.data.Clip
import kotlin.math.roundToLong

/**
 * v0.9.4 (BUG 1 — device report op=pvw-27375504): pure state machine for the
 * CapCut-style trim-peg drags on video clip blocks.
 *
 * The v0.9.3 pegs seeked the preview player AT EVERY DRAG FRAME (the
 * boundary-frame "peek"): each frame bumped the ViewModel seek token, the
 * preview stage issued `player.seekTo`, and the decoder sat in permanent
 * BUFFERING (40+ "ACTIVATION seek applied" events in one gesture, screen
 * flicker/black, the overlay gate flapping). This session replaces that:
 *
 *  - [update] only advances the LIVE trim values (the orange frame, the pegs
 *    and the duration chip are pure Compose drawing — they run at 60 fps
 *    without touching the player);
 *  - it NEVER produces a seek during the gesture — [seekEvents] stays empty
 *    until the drag ends (the test pins this: N movements -> zero seeks);
 *  - [commit] is the gesture's SINGLE outcome: the final trim (one
 *    `EditorViewModel.trimClip` call -> one undo step) plus EXACTLY ONE seek
 *    target — the project time of the dragged edge's frame in the NEW window
 *    (the preview lands on the boundary the user cut to).
 *
 * The frozen-frame contract lives in the composable + ViewModel: the drag
 * pauses playback once (SurfaceView keeps showing the last decoded frame —
 * never cleared, never seeked) and resumes after the commit. The math is
 * unchanged from v0.9.3 (px -> source ms through the shared [TimelineScale],
 * speed-scaled, clamped to the same bounds), so committed trims are
 * byte-identical with the previous release.
 *
 * Pure — JVM-tested by TrimHandleDragTest (no Android, no Compose).
 */
class TrimDragSession(
    private val clip: Clip,
    private val peg: Peg,
    private val scale: TimelineScale,
    /** Absolute project start of the clip (stable during the gesture — the
     *  project itself never changes while the drag holds its live state). */
    private val clipStartMs: Long,
) {

    /** Which edge the user is dragging. */
    enum class Peg { START, END }

    /** One committed gesture: the new trim plus its single boundary seek. */
    data class Commit(
        val trimStartMs: Long,
        val trimEndMs: Long,
        /** Project-time ms the preview must seek to ONCE, after the commit. */
        val seekProjectMs: Long,
    )

    /** Live (uncommitted) trim values — what the chip and frame render. */
    var liveTrimStartMs: Long = clip.trimStartMs
        private set
    var liveTrimEndMs: Long = clip.trimEndMs
        private set

    /** Seeks this session requested. Empty until [commit] (contract). */
    val seekEvents: MutableList<Long> = mutableListOf()

    /** True once [commit] produced a change (one gesture = at most one). */
    var committed: Boolean = false
        private set

    private val minClipMs = TimelineLogic.MIN_CLIP_SOURCE_MS

    /**
     * One pointer movement. Advances ONLY the live values — never a seek.
     * Math identical to the v0.9.3 per-frame code (minus its seek).
     */
    fun update(deltaPx: Float) {
        val deltaSource = scale.pxToTime(deltaPx) * clip.speed
        when (peg) {
            Peg.START -> {
                val currentStart = liveTrimStartMs
                val maxStart = (clip.trimEndMs - minClipMs).coerceAtLeast(0L)
                liveTrimStartMs = (currentStart + deltaSource).roundToLong()
                    .coerceIn(0L, maxStart)
                liveTrimEndMs = clip.trimEndMs
            }
            Peg.END -> {
                val currentEnd = liveTrimEndMs
                val minEnd = (clip.trimStartMs + minClipMs)
                    .coerceAtMost(clip.sourceDurationMs)
                liveTrimEndMs = (currentEnd + deltaSource).roundToLong()
                    .coerceIn(minEnd, clip.sourceDurationMs)
                liveTrimStartMs = clip.trimStartMs
            }
        }
    }

    /**
     * The gesture's single commit: the new trim and the ONE boundary seek.
     * Null when the drag ended without an actual change (tap, jitter) —
     * no undo step, no seek, matching the v0.9.3 no-op guard.
     */
    fun commit(): Commit? {
        if (committed) return null
        val s = liveTrimStartMs
        val e = liveTrimEndMs
        if (s == clip.trimStartMs && e == clip.trimEndMs) return null
        committed = true
        // Boundary seek: the first frame of the NEW window (START drag) or
        // its last frame (END drag), mapped through the SAME pure math the
        // preview stage uses (ProjectMath handles speed/ramps).
        val newClip = clip.withTrim(s, e)
        val boundaryLocal = if (peg == Peg.START) {
            0L
        } else {
            ((e - s) - 1L).coerceAtLeast(0L)
        }
        val seek = clipStartMs + ProjectMath.itemLocalToProjectMs(newClip, boundaryLocal)
        seekEvents.add(seek)
        return Commit(trimStartMs = s, trimEndMs = e, seekProjectMs = seek)
    }

    /** Gesture cancelled: drop the live values (no commit, no seek). */
    fun cancel() {
        liveTrimStartMs = clip.trimStartMs
        liveTrimEndMs = clip.trimEndMs
    }
}

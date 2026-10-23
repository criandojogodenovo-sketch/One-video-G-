package com.onevideog.editor.editor

import com.onevideog.editor.data.Clip
import com.onevideog.editor.data.ClipTransition
import com.onevideog.editor.data.OvgProject

/**
 * v0.6.0 (Fase H) — THE pure transition planner. One implementation for the
 * timeline UI, the preview effect list and the export composition.
 *
 * Rules (documented contract, locked by TransitionPlannerTest):
 *  - a transition may only exist between clips ADJACENT in the sequence
 *    (betweenClipIds = (clips[i].id, clips[i+1].id)); a clip removal, move
 *    or split that breaks adjacency makes the transition invalid;
 *  - durationMs must sit in [MIN_DURATION_MS, MAX_DURATION_MS] and be
 *    <= 50% of the previous clip's effectiveDurationMs AND <= 50% of the
 *    next clip's — invalid transitions are DISCARDED (never clamped into
 *    existence), logged as TRANSITION_INVALID by the callers;
 *  - [sanitize] is the automatic orphan cleanup (H6): it drops every
 *    invalid transition, so a stale document can never break the preview
 *    or the export;
 *  - transitions RENDER ON the existing content (out window on the tail of
 *    the previous clip, in window on the head of the next clip); they never
 *    change the sequence length, so ProjectMath.projectDurationMs and the
 *    v0.3.0 duration contract are untouched.
 *
 * Media3 1.4.1 note: there is no Transition API in 1.4.1 (verified against
 * the 1.4.1 sources), so the rendered windows are per-clip GLSL effects —
 * see [TransitionEffects]. The PLAN below is renderer-agnostic and is the
 * single reference both pipelines share.
 */
object TransitionPlanner {

    /** Slider range of the TransitionSheet (snap 100 ms at the UI level). */
    const val MIN_DURATION_MS = 200L
    const val MAX_DURATION_MS = 1000L

    /** Why a transition was rejected (evidence for TRANSITION_INVALID). */
    enum class InvalidReason {
        /** One of the two clip ids is not in the sequence. */
        CLIP_MISSING,
        /** The two clips exist but are not consecutive in the sequence. */
        NOT_ADJACENT,
        /** durationMs outside [MIN_DURATION_MS, MAX_DURATION_MS]. */
        DURATION_OUT_OF_RANGE,
        /** durationMs exceeds 50% of the previous or the next clip. */
        DURATION_EXCEEDS_HALF,
    }

    /**
     * One renderable transition: the window on the TAIL of the previous
     * clip ([windowStartLocalMsOnA] .. duration end of clip A) and the
     * mirrored window on the HEAD of the next clip (0 .. durationMs).
     * Item-local ms — identical in project-local units after
     * SpeedChangeEffect (verified contract, see ClipEffectsFactory docs).
     */
    data class TransitionPlan(
        val transition: ClipTransition,
        val indexA: Int,
        val indexB: Int,
        val clipA: Clip,
        val clipB: Clip,
        /** Local ms on clip A where the out-window starts (tail). */
        val windowStartLocalMsOnA: Long,
        /** Out/in window length on each side (the stored durationMs). */
        val durationMs: Long,
    )

    /**
     * Largest duration the TransitionSheet may offer for this pair (the
     * slider max): the 50%-of-both-clips rule applied upfront, so whatever
     * the user commits is always valid.
     */
    fun maxLegalDurationMs(clipA: Clip, clipB: Clip): Long =
        minOf(
            MAX_DURATION_MS,
            clipA.effectiveDurationMs / 2,
            clipB.effectiveDurationMs / 2,
        )

    /** Pure legal-duration check used by the planner, the UI and the tests. */
    fun validate(
        clips: List<Clip>,
        transition: ClipTransition,
    ): InvalidReason? {
        val indexA = clips.indexOfFirst { it.id == transition.betweenClipIds.first }
        val indexB = clips.indexOfFirst { it.id == transition.betweenClipIds.second }
        if (indexA < 0 || indexB < 0) return InvalidReason.CLIP_MISSING
        if (indexB != indexA + 1) return InvalidReason.NOT_ADJACENT
        if (transition.durationMs < MIN_DURATION_MS) {
            return InvalidReason.DURATION_OUT_OF_RANGE
        }
        // The 50%-of-both-clips rule is checked BEFORE the MAX cap so a
        // duration that cannot physically fit the clips reports
        // DURATION_EXCEEDS_HALF (the physical reason) rather than the UI
        // range reason.
        val a = clips[indexA]
        val b = clips[indexB]
        if (transition.durationMs * 2 > a.effectiveDurationMs ||
            transition.durationMs * 2 > b.effectiveDurationMs
        ) {
            return InvalidReason.DURATION_EXCEEDS_HALF
        }
        if (transition.durationMs > MAX_DURATION_MS) {
            return InvalidReason.DURATION_OUT_OF_RANGE
        }
        return null
    }

    /**
     * Every renderable plan, in transition-list order. Invalid entries are
     * silently skipped here — callers log them via [invalidTransitions] so
     * the planner stays free of Android/logging dependencies (pure, JVM).
     */
    fun plansFor(
        clips: List<Clip>,
        transitions: List<ClipTransition>,
    ): List<TransitionPlan> = transitions.mapNotNull { transition ->
        if (validate(clips, transition) != null) return@mapNotNull null
        val indexA = clips.indexOfFirst { it.id == transition.betweenClipIds.first }
        val a = clips[indexA]
        TransitionPlan(
            transition = transition,
            indexA = indexA,
            indexB = indexA + 1,
            clipA = a,
            clipB = clips[indexA + 1],
            windowStartLocalMsOnA = (a.effectiveDurationMs - transition.durationMs)
                .coerceAtLeast(0),
            durationMs = transition.durationMs,
        )
    }

    /** The rejected transitions with their reasons (for TRANSITION_INVALID). */
    fun invalidTransitions(
        clips: List<Clip>,
        transitions: List<ClipTransition>,
    ): List<Pair<ClipTransition, InvalidReason>> = transitions.mapNotNull { transition ->
        validate(clips, transition)?.let { transition to it }
    }

    /** Plan for ONE clip pair, or null when the transition is invalid. */
    fun planFor(
        clips: List<Clip>,
        transition: ClipTransition?,
    ): TransitionPlan? {
        transition ?: return null
        return plansFor(clips, listOf(transition)).firstOrNull()
    }

    /**
     * H6 — drops every invalid transition from the project (clip removed,
     * moved, split, or a duration that no longer fits). Idempotent; keeps
     * the order of the surviving list. Called after every sequence edit and
     * on project open, so a stale document self-heals.
     */
    fun sanitize(project: OvgProject): OvgProject {
        if (project.transitions.isEmpty()) return project
        val kept = project.transitions.filter { validate(project.clips, it) == null }
        if (kept.size == project.transitions.size) return project
        return project.copy(transitions = kept)
    }
}

package com.onevideog.editor.editor

import com.onevideog.editor.data.Clip
import com.onevideog.editor.data.ClipTransition
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.TransitionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.6.0 (Fase H) — the transition validity contract (H2) and the automatic
 * orphan cleanup (H6), locked on the JVM:
 *  - a transition only exists between clips ADJACENT in the sequence;
 *  - durationMs sits in [MIN_DURATION_MS, MAX_DURATION_MS] and is <= 50% of
 *    BOTH neighbouring clips' effective durations;
 *  - clip removal/move/split that breaks adjacency invalidates the
 *    transition, and [TransitionPlanner.sanitize] drops it (the document
 *    self-heals — an invalid transition can never reach the pipelines);
 *  - transitions never change the sequence length (they render ON the
 *    content; the v0.3.0 duration contract is untouched).
 */
class TransitionPlannerTest {

    private fun clip(id: String, durMs: Long) = Clip(
        id = id, uri = "u", displayName = "d", sourceDurationMs = durMs,
        trimStartMs = 0, trimEndMs = durMs,
    )

    private fun clips(): List<Clip> = listOf(
        clip("a", 10_000),
        clip("b", 8_000),
        clip("c", 20_000),
    )

    private fun transition(
        a: String = "a",
        b: String = "b",
        kind: TransitionKind = TransitionKind.CROSSFADE,
        durationMs: Long = 500,
    ) = ClipTransition(id = "t_$a$b", betweenClipIds = a to b, kind = kind, durationMs = durationMs)

    // ------------------------------------------------------------ H2 validity

    @Test
    fun `adjacent valid transition yields a plan`() {
        val plans = TransitionPlanner.plansFor(clips(), listOf(transition()))
        assertEquals(1, plans.size)
        val plan = plans[0]
        assertEquals(0, plan.indexA)
        assertEquals(1, plan.indexB)
        assertEquals("a", plan.clipA.id)
        assertEquals("b", plan.clipB.id)
    }

    @Test
    fun `plan window starts 50 percent aware on the previous clip tail`() {
        // clip a = 10 s, durationMs = 500 -> the OUT window is the tail
        // [9500, 10000) of clip a's local timeline.
        val plan = TransitionPlanner.plansFor(clips(), listOf(transition(durationMs = 500)))[0]
        assertEquals(9_500L, plan.windowStartLocalMsOnA)
        assertEquals(500L, plan.durationMs)
    }

    @Test
    fun `window uses the effective duration when speed changes it`() {
        val fast = clip("a", 10_000).copy(speed = 2f) // effective 5 s
        val list = listOf(fast, clip("b", 8_000))
        val plan = TransitionPlanner.plansFor(list, listOf(transition(durationMs = 1_000)))[0]
        // 5 s effective -> half = 2.5 s >= 1 s -> valid; window = 5000 - 1000.
        assertEquals(4_000L, plan.windowStartLocalMsOnA)
    }

    @Test
    fun `non adjacent pair is rejected`() {
        val reason = TransitionPlanner.validate(clips(), transition(a = "a", b = "c"))
        assertEquals(TransitionPlanner.InvalidReason.NOT_ADJACENT, reason)
        assertTrue(TransitionPlanner.plansFor(clips(), listOf(transition(a = "a", b = "c"))).isEmpty())
    }

    @Test
    fun `missing clip is rejected`() {
        assertEquals(
            TransitionPlanner.InvalidReason.CLIP_MISSING,
            TransitionPlanner.validate(clips(), transition(a = "x", b = "b")),
        )
    }

    @Test
    fun `duration below 200 or above 1000 is rejected`() {
        assertEquals(
            TransitionPlanner.InvalidReason.DURATION_OUT_OF_RANGE,
            TransitionPlanner.validate(clips(), transition(durationMs = 100)),
        )
        assertEquals(
            TransitionPlanner.InvalidReason.DURATION_OUT_OF_RANGE,
            TransitionPlanner.validate(clips(), transition(durationMs = 1_500)),
        )
    }

    @Test
    fun `duration exceeding half of either clip is rejected`() {
        // 50% of clip b (8 s) = 4 s; 5 s > 4 s -> invalid even though clip a
        // (10 s) could hold it. The physical rule is reported BEFORE the UI
        // range cap (which only ever sees UI-sourced values anyway).
        assertEquals(
            TransitionPlanner.InvalidReason.DURATION_EXCEEDS_HALF,
            TransitionPlanner.validate(clips(), transition(durationMs = 5_000)),
        )
        // Exactly 50% is VALID (<= rule) — 1 s clips hosting a 500 ms
        // window (inside the 200..1000 UI range).
        assertNull(
            TransitionPlanner.validate(
                listOf(clip("a", 1_000), clip("b", 1_000)),
                transition(durationMs = 500),
            ),
        )
    }

    @Test
    fun `maxLegalDurationMs caps the slider at MAX and half of both clips`() {
        // H5 slider range 200..1000: for long clips the MAX cap binds.
        assertEquals(1_000L, TransitionPlanner.maxLegalDurationMs(clip("a", 10_000), clip("b", 8_000)))
        assertEquals(
            TransitionPlanner.MAX_DURATION_MS,
            TransitionPlanner.maxLegalDurationMs(clip("a", 60_000), clip("b", 60_000)),
        )
        // Short clips: the 50%-of-both rule binds below the MAX cap.
        assertEquals(400L, TransitionPlanner.maxLegalDurationMs(clip("a", 1_000), clip("b", 800)))
        // Tiny clips cannot host any legal transition.
        assertTrue(
            TransitionPlanner.maxLegalDurationMs(clip("a", 300), clip("b", 300)) <
                TransitionPlanner.MIN_DURATION_MS,
        )
    }

    @Test
    fun `all five kinds are accepted by the planner`() {
        for (kind in TransitionKind.entries) {
            assertNull(TransitionPlanner.validate(clips(), transition(kind = kind)))
        }
    }

    // ------------------------------------------------ H6 automatic cleanup

    @Test
    fun `sanitize keeps valid transitions untouched`() {
        val project = OvgProject(
            id = "p", name = "n", createdAtEpochMs = 0, updatedAtEpochMs = 0,
        ).copy(clips = clips(), transitions = listOf(transition()))
        assertEquals(project, TransitionPlanner.sanitize(project))
    }

    @Test
    fun `removing a clip drops the orphaned transition automatically`() {
        val project = OvgProject(
            id = "p", name = "n", createdAtEpochMs = 0, updatedAtEpochMs = 0,
        ).copy(clips = clips(), transitions = listOf(transition(a = "b", b = "c")))
        val removed = project.copy(clips = clips().filterNot { it.id == "c" })
        val sanitized = TransitionPlanner.sanitize(removed)
        assertTrue(sanitized.transitions.isEmpty())
    }

    @Test
    fun `moving a clip breaks adjacency and sanitize drops the transition`() {
        // Sequence b, a, c: the a->b transition is no longer adjacent
        // (b comes FIRST now).
        val moved = clips().toMutableList().apply {
            removeAt(0)
            add(1, clip("a", 10_000))
        } // b, a, c
        val project = OvgProject(
            id = "p", name = "n", createdAtEpochMs = 0, updatedAtEpochMs = 0,
        ).copy(clips = moved, transitions = listOf(transition(a = "a", b = "b")))
        assertTrue(TransitionPlanner.sanitize(project).transitions.isEmpty())
    }

    @Test
    fun `splitting a clip inserts a new id and breaks the old adjacency`() {
        // a (10 s) split at 5 s -> a (5 s) + new clip (5 s), then b.
        val splitList = listOf(clip("a", 5_000), clip("a2", 5_000), clip("b", 8_000), clip("c", 20_000))
        val project = OvgProject(
            id = "p", name = "n", createdAtEpochMs = 0, updatedAtEpochMs = 0,
        ).copy(clips = splitList, transitions = listOf(transition(a = "a", b = "b")))
        assertTrue(TransitionPlanner.sanitize(project).transitions.isEmpty())
    }

    @Test
    fun `a trimmed clip that can no longer host the window drops the transition`() {
        val shrunk = clips().map { if (it.id == "a") clip("a", 800) else it } // 50% = 400 < 500
        val project = OvgProject(
            id = "p", name = "n", createdAtEpochMs = 0, updatedAtEpochMs = 0,
        ).copy(clips = shrunk, transitions = listOf(transition(durationMs = 500)))
        assertTrue(TransitionPlanner.sanitize(project).transitions.isEmpty())
    }

    @Test
    fun `sanitize is idempotent and keeps the surviving order`() {
        val project = OvgProject(
            id = "p", name = "n", createdAtEpochMs = 0, updatedAtEpochMs = 0,
        ).copy(
            clips = clips(),
            transitions = listOf(
                transition(a = "b", b = "c", durationMs = 400),
                transition(a = "a", b = "c"), // invalid — non adjacent
            ),
        )
        val once = TransitionPlanner.sanitize(project)
        assertEquals(listOf("t_bc"), once.transitions.map { it.id })
        assertEquals(once, TransitionPlanner.sanitize(once))
    }

    @Test
    fun `plans for two chained transitions carry correct windows`() {
        val transitions = listOf(transition(a = "a", b = "b", durationMs = 1_000), transition(a = "b", b = "c", durationMs = 600))
        val plans = TransitionPlanner.plansFor(clips(), transitions)
        assertEquals(2, plans.size)
        assertEquals(9_000L, plans[0].windowStartLocalMsOnA)
        assertEquals(7_400L, plans[1].windowStartLocalMsOnA) // 8000 - 600
    }

    @Test
    fun `transition ids stay stable when revalidated`() {
        val project = OvgProject(
            id = "p", name = "n", createdAtEpochMs = 0, updatedAtEpochMs = 0,
        ).copy(clips = clips(), transitions = listOf(transition()))
        val plan = TransitionPlanner.planFor(project.clips, project.transitions[0])
        assertNotEquals(null, plan)
        assertEquals("t_ab", plan?.transition?.id)
    }
}

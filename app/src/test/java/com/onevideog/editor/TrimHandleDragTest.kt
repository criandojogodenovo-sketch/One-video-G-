package com.onevideog.editor.editor

import com.onevideog.editor.data.Clip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.4 (BUG 1) — the trim-peg drag contract, pinned at the pure
 * UI-logic level (TrimDragSession):
 *
 *  - N drag movements produce ZERO seeks (the session never emits one
 *    during [TrimDragSession.update] — the v0.9.3 per-frame peek is gone);
 *  - exactly ONE commit + ONE seek at gesture end;
 *  - the live duration (what the chip renders) tracks the live trim;
 *  - the commit is a single trim operation -> one undo step by
 *    construction (EditorViewModel.trimClip commits once).
 *
 * Scale arithmetic: TimelineScale maps density design px — with density 1
 * one design px = 60 ms (DESIGN_MS_PER_PX); with density 2 one REAL px =
 * 30 ms.
 */
class TrimHandleDragTest {

    /** 60 s source with the window parked at 0..30 s — both edges can move. */
    private fun clip(
        trimStart: Long = 0L,
        trimEnd: Long = 30_000L,
        speed: Float = 1f,
    ) = Clip(
        id = "c1",
        uri = "u",
        displayName = "d",
        sourceDurationMs = 60_000,
        trimStartMs = trimStart,
        trimEndMs = trimEnd,
        speed = speed,
    )

    @Test
    fun `drag of N movements produces zero intermediate seeks`() {
        val session = TrimDragSession(clip(), TrimDragSession.Peg.END, TimelineScale(2f, 1f), 0L)
        session.update(10f)
        session.update(-4f)
        session.update(30f)
        session.update(-1f)
        assertEquals(0, session.seekEvents.size)
        assertFalse(session.committed)
    }

    @Test
    fun `commit produces exactly one seek and it is the dragged boundary`() {
        val session = TrimDragSession(clip(), TrimDragSession.Peg.END, TimelineScale(2f, 1f), 0L)
        session.update(10f) // 10 real px x 30 ms/px = 300 ms
        val commit = session.commit()
        assertNotNull(commit)
        assertEquals(30_300L, commit!!.trimEndMs)
        assertEquals(1, session.seekEvents.size)
        assertEquals(commit.seekProjectMs, session.seekEvents.single())
        // END drag: the seek lands on the LAST frame of the new window.
        assertEquals(30_299L, commit.seekProjectMs)
        // A second commit is refused (one gesture, one outcome).
        assertNull(session.commit())
    }

    @Test
    fun `start peg commit seeks the first frame of the new window`() {
        val session = TrimDragSession(clip(), TrimDragSession.Peg.START, TimelineScale(1f, 1f), 0L)
        session.update(500f) // 500 design px x 60 ms = 30_000 ms — clamps to the min-clip bound
        val commit = session.commit()
        assertNotNull(commit)
        assertEquals(29_900L, commit!!.trimStartMs)
        // START drag: the seek lands on the FIRST frame of the new window.
        assertEquals(0L, commit.seekProjectMs)
    }

    @Test
    fun `boundary seek is absolute in project time (clip starts later)`() {
        val session = TrimDragSession(
            clip(), TrimDragSession.Peg.END, TimelineScale(2f, 1f), 12_000L,
        )
        session.update(10f) // +300 ms
        val commit = session.commit()
        assertEquals(12_000L + 30_299L, commit!!.seekProjectMs)
    }

    @Test
    fun `live duration chip mirrors the live trim`() {
        val session = TrimDragSession(clip(), TrimDragSession.Peg.END, TimelineScale(2f, 1f), 0L)
        session.update(10f)
        val liveOutput = SpeedRampPlanner.outputDurationMs(
            clip().withTrim(session.liveTrimStartMs, session.liveTrimEndMs),
        )
        // Chip value BEFORE the commit — the live 60 fps rendering source.
        assertEquals(30_300L, liveOutput)
    }

    @Test
    fun `cancel drops the live values without commit or seek`() {
        val session = TrimDragSession(clip(), TrimDragSession.Peg.START, TimelineScale(2f, 1f), 0L)
        session.update(20f)
        session.cancel()
        assertEquals(0L, session.liveTrimStartMs)
        assertEquals(30_000L, session.liveTrimEndMs)
        assertNull(session.commit())
        assertTrue(session.seekEvents.isEmpty())
        assertFalse(session.committed)
    }

    @Test
    fun `speed scales the drag delta like v0_9_3`() {
        val session = TrimDragSession(
            clip(speed = 2f), TrimDragSession.Peg.END, TimelineScale(2f, 1f), 0L,
        )
        session.update(10f) // 300 ms source x2 speed = 600 ms
        val commit = session.commit()
        assertEquals(30_600L, commit!!.trimEndMs)
    }

    @Test
    fun `unchanged drag commits nothing`() {
        val session = TrimDragSession(clip(), TrimDragSession.Peg.END, TimelineScale(2f, 1f), 0L)
        assertNull(session.commit())
        assertFalse(session.committed)
        assertTrue(session.seekEvents.isEmpty())
    }
}

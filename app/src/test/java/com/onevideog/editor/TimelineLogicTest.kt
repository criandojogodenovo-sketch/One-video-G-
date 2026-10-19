package com.onevideog.editor

import com.onevideog.editor.data.Clip
import com.onevideog.editor.editor.TimelineLogic
import com.onevideog.editor.editor.UndoRedoManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Timeline math: trim, split, reorder, delete, durations, undo/redo. */
class TimelineLogicTest {

    private fun clip(
        id: String,
        durationMs: Long,
        trimStart: Long = 0,
        trimEnd: Long = durationMs,
        speed: Float = 1f,
    ) = Clip(
        id = id,
        uri = "content://media/$id",
        displayName = id,
        sourceDurationMs = durationMs,
        trimStartMs = trimStart,
        trimEndMs = trimEnd,
        speed = speed,
    )

    @Test
    fun `effective duration respects speed`() {
        val c = clip("a", 2000, trimStart = 500, trimEnd = 1500, speed = 2f)
        assertEquals(500L, c.effectiveDurationMs)
    }

    @Test
    fun `total duration sums effective durations`() {
        val clips = listOf(clip("a", 1000), clip("b", 2000, speed = 2f))
        assertEquals(2000L, TimelineLogic.totalDurationMs(clips))
    }

    @Test
    fun `clip index at position finds right clip`() {
        val clips = listOf(clip("a", 1000), clip("b", 1000), clip("c", 1000))
        assertEquals(0, TimelineLogic.clipIndexAt(clips, 0))
        assertEquals(0, TimelineLogic.clipIndexAt(clips, 999))
        assertEquals(1, TimelineLogic.clipIndexAt(clips, 1000))
        assertEquals(2, TimelineLogic.clipIndexAt(clips, 2500))
        assertEquals(-1, TimelineLogic.clipIndexAt(emptyList(), 0))
    }

    @Test
    fun `clip start positions are cumulative`() {
        val clips = listOf(clip("a", 1000), clip("b", 500, speed = 2f), clip("c", 1000))
        assertEquals(0L, TimelineLogic.clipStartMs(clips, 0))
        assertEquals(1000L, TimelineLogic.clipStartMs(clips, 1))
        assertEquals(1250L, TimelineLogic.clipStartMs(clips, 2))
    }

    @Test
    fun `split produces two clips with correct trims`() {
        val clips = listOf(clip("a", 2000))
        val result = TimelineLogic.split(clips, 1000)
        assertNotNull(result)
        result!!
        assertEquals(2, result.size)
        assertEquals(0L, result[0].trimStartMs)
        assertEquals(1000L, result[0].trimEndMs)
        assertEquals(1000L, result[1].trimStartMs)
        assertEquals(2000L, result[1].trimEndMs)
        assertTrue(result[0].id != result[1].id)
    }

    @Test
    fun `split maps timeline position through speed`() {
        val clip = clip("a", 4000, trimStart = 0, trimEnd = 4000, speed = 2f)
        // effective duration is 2000; timeline position 1000 is source 2000
        val result = TimelineLogic.split(listOf(clip), 1000)
        assertNotNull(result)
        assertEquals(2000L, result!![0].trimEndMs)
        assertEquals(2000L, result[1].trimStartMs)
    }

    @Test
    fun `split too close to edges is rejected`() {
        val clips = listOf(clip("a", 1000))
        assertNull(TimelineLogic.split(clips, 50))
        assertNull(TimelineLogic.split(clips, 980))
    }

    @Test
    fun `trim clamps to source duration`() {
        val c = clip("a", 1000)
        val trimmed = c.withTrim(-100, 5000)
        assertEquals(0L, trimmed.trimStartMs)
        assertEquals(1000L, trimmed.trimEndMs)
    }

    @Test
    fun `move reorders clips`() {
        val clips = listOf(clip("a", 1000), clip("b", 1000), clip("c", 1000))
        val moved = TimelineLogic.move(clips, "a", 2)!!
        assertEquals("b", moved[0].id)
        assertEquals("c", moved[1].id)
        assertEquals("a", moved[2].id)
        // no-op moves return null
        assertNull(TimelineLogic.move(clips, "a", 0))
        assertNull(TimelineLogic.move(clips, "a", -5))
    }

    @Test
    fun `remove deletes only target`() {
        val clips = listOf(clip("a", 1000), clip("b", 1000))
        val removed = TimelineLogic.remove(clips, "a")!!
        assertEquals(listOf("b"), removed.map { it.id })
        assertNull(TimelineLogic.remove(clips, "zzz"))
    }

    @Test
    fun `undo redo restores snapshots`() {
        val manager = UndoRedoManager<Int>(0)
        assertEquals(0, manager.value)
        assertFalse(manager.canUndo)

        manager.commit(1)
        manager.commit(2)
        assertEquals(2, manager.value)
        assertTrue(manager.canUndo)

        assertEquals(1, manager.undo())
        assertTrue(manager.canRedo)
        assertEquals(2, manager.redo())

        assertEquals(1, manager.undo())
        assertEquals(0, manager.undo())
        assertFalse(manager.canUndo)
        assertNull(manager.undo())
    }

    @Test
    fun `commit clears redo stack`() {
        val manager = UndoRedoManager<Int>(0)
        manager.commit(1)
        manager.commit(2)
        manager.undo()
        assertTrue(manager.canRedo)
        manager.commit(3)
        assertFalse(manager.canRedo)
        assertEquals(3, manager.value)
    }

    @Test
    fun `undo stack is bounded`() {
        val manager = UndoRedoManager<Int>(0, capacity = 3)
        for (i in 1..10) manager.commit(i)
        assertEquals(3, countUndoable(manager))
    }

    private fun countUndoable(manager: UndoRedoManager<Int>): Int {
        var count = 0
        while (manager.undo() != null) count++
        return count
    }
}

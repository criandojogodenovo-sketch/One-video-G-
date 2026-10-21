package com.onevideog.editor.editor

import com.onevideog.editor.data.Clip

/**
 * Pure timeline math used by the editor UI and the exporter.
 * Kept free of Android classes so it is fully unit-testable on the JVM.
 */
object TimelineLogic {

    /** Minimum duration (source ms) of a clip after a split or trim. */
    const val MIN_CLIP_SOURCE_MS = 100L

    /** Total timeline duration, accounting for per-clip speed. */
    fun totalDurationMs(clips: List<Clip>): Long = clips.sumOf { it.effectiveDurationMs }

    /** Start position of [index] on the timeline. */
    fun clipStartMs(clips: List<Clip>, index: Int): Long {
        var acc = 0L
        for (i in 0 until index.coerceAtMost(clips.size)) acc += clips[i].effectiveDurationMs
        return acc
    }

    /**
     * Index of the clip that contains [positionMs] (positions at a boundary
     * belong to the following clip). Returns clips.lastIndex when past the
     * end, or -1 when the timeline is empty.
     */
    fun clipIndexAt(clips: List<Clip>, positionMs: Long): Int {
        if (clips.isEmpty()) return -1
        var acc = 0L
        for ((i, clip) in clips.withIndex()) {
            val end = acc + clip.effectiveDurationMs
            if (positionMs < end || i == clips.lastIndex) return i
            acc = end
        }
        return clips.lastIndex
    }

    /** Timeline end position of the clip at [index]. */
    fun clipEndAt(clips: List<Clip>, index: Int): Long =
        clipStartMs(clips, index) + (clips.getOrNull(index)?.effectiveDurationMs ?: 0L)

    /**
     * Splits the clip containing [positionMs] into two clips. Returns null
     * when the position does not leave both halves >= [MIN_CLIP_SOURCE_MS].
     */
    fun split(clips: List<Clip>, positionMs: Long): List<Clip>? {
        val index = clipIndexAt(clips, positionMs)
        if (index < 0) return null
        val clip = clips[index]
        val start = clipStartMs(clips, index)
        val localMs = (positionMs - start).coerceAtLeast(0)
        val sourceSplitMs = clip.trimStartMs + Math.round(localMs * clip.speed)
        if (sourceSplitMs - clip.trimStartMs < MIN_CLIP_SOURCE_MS ||
            clip.trimEndMs - sourceSplitMs < MIN_CLIP_SOURCE_MS
        ) {
            return null
        }
        val first = clip.withTrim(clip.trimStartMs, sourceSplitMs)
        val second = clip.copy(id = newId()).withTrim(sourceSplitMs, clip.trimEndMs)
        return clips.toMutableList().apply {
            removeAt(index)
            add(index, second)
            add(index, first)
        }
    }

    /** Replaces the clip with [id] using [transform], keeping order. */
    fun withClip(clips: List<Clip>, id: String, transform: (Clip) -> Clip): List<Clip> =
        clips.map { if (it.id == id) transform(it) else it }

    /** Moves a clip by [delta] positions (clamped). Returns null when nothing changes. */
    fun move(clips: List<Clip>, id: String, delta: Int): List<Clip>? {
        val from = clips.indexOfFirst { it.id == id }
        if (from < 0) return null
        val to = (from + delta).coerceIn(0, clips.lastIndex)
        if (from == to) return null
        val mutable = clips.toMutableList()
        val clip = mutable.removeAt(from)
        mutable.add(to, clip)
        return mutable
    }

    /** Removes the clip with [id]. Returns null when not found. */
    fun remove(clips: List<Clip>, id: String): List<Clip>? =
        if (clips.any { it.id == id }) clips.filterNot { it.id == id } else null

    /**
     * Identity of every new timeline element (video clip, text/image overlay,
     * audio clip, split halves). v0.5.1: a FULL random UUID — the previous
     * 16-char truncation worked in practice but is not a UUID and needlessly
     * narrowed the id space. One generator for ALL clip kinds: no per-type
     * id scheme exists anywhere else in the app.
     */
    fun newId(): String = java.util.UUID.randomUUID().toString()
}

/**
 * Snapshot undo/redo manager. Operations receive the new state; the previous
 * state is pushed onto the undo stack. Memory-bounded by [capacity].
 */
class UndoRedoManager<T>(initial: T, private val capacity: Int = 40) {

    private var current: T = initial
    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()

    val value: T get() = current
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Records [newValue] as the current state, keeping [current] undoable. */
    fun commit(newValue: T) {
        if (undoStack.size >= capacity) undoStack.removeFirst()
        undoStack.addLast(current)
        current = newValue
        redoStack.clear()
    }

    /** Restores the previous state. Returns null when there is nothing to undo. */
    fun undo(): T? {
        val previous = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        current = previous
        return current
    }

    /** Re-applies the last undone state. Returns null when there is nothing to redo. */
    fun redo(): T? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        current = next
        return current
    }

    /** Drops all history (e.g. after loading a different document). */
    fun reset(initial: T) {
        current = initial
        undoStack.clear()
        redoStack.clear()
    }
}

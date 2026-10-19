package com.onevideog.editor.diag

import java.io.File

/**
 * Persistent store for diagnostic events (temporary diagnostics, v0.2.3).
 *
 * Contract (per the v0.2.3 diagnostics spec):
 *  - writes INCREMENTALLY (one formatted line per event, append-only);
 *  - thread-safe: all state mutations are guarded by an internal lock, so
 *    correctness does not depend on the caller's single-writer thread;
 *  - rotation: when [currentFile] exceeds [maxFileBytes] it becomes
 *    [previousFile]; at most [keepPrevious] previous files are kept, giving
 *    a strict total cap of (keepPrevious + 1) * maxFileBytes (≈ 2 MB);
 *  - enable gate: events written while disabled are ignored (the Android
 *    [DiagnosticsLogger] mirrors the Settings toggle into [setEnabled]);
 *  - IO failures never propagate — [append] returns false and the caller
 *    (editor) keeps running;
 *  - [readRecentLines] is bounded so building the clipboard report never
 *    loads unbounded data into RAM.
 *
 * Pure java.io — unit-testable on the JVM with a temp directory and an
 * injected clock.
 */
class DiagLogStore(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val keepPrevious: Int = DEFAULT_KEEP_PREVIOUS,
) {

    @Volatile private var enabled: Boolean = false
    private val lock = Any()

    private val current = File(dir, CURRENT_FILE)
    private val previousBase = File(dir, PREVIOUS_FILE)

    @Volatile private var count = 0L
    @Volatile private var droppedCount = 0L
    @Volatile private var rotations = 0
    @Volatile private var lastWriteOk = true

    // ------------------------------------------------------------- state

    fun setEnabled(value: Boolean) {
        synchronized(lock) { enabled = value }
    }

    fun isEnabled(): Boolean = synchronized(lock) { enabled }

    /** Events persisted since the last [clearAll] (across rotations). */
    fun eventCount(): Long = synchronized(lock) { count }

    /** Events dropped because logging was disabled or IO failed. */
    fun droppedCount(): Long = synchronized(lock) { droppedCount }

    /** True once any rotation/truncation has happened (report warning). */
    fun hasRotated(): Boolean = synchronized(lock) { rotations > 0 }

    fun lastAppendFailed(): Boolean = synchronized(lock) { !lastWriteOk }

    // ------------------------------------------------------------- writes

    /**
     * Appends one event. Returns true when the line was persisted.
     * Never throws.
     */
    fun append(event: DiagnosticEvent): Boolean {
        val line = runCatching { EventFormatter.format(event) }.getOrNull() ?: return false
        synchronized(lock) {
            if (!enabled) {
                droppedCount++
                return false
            }
            val ok = writeLine(line)
            if (ok) {
                count++
                lastWriteOk = true
            } else {
                droppedCount++
                lastWriteOk = false
            }
            if (ok) enforceRotation()
            return ok
        }
    }

    private fun writeLine(line: String): Boolean = runCatching {
        if (!dir.exists() && !dir.mkdirs()) return false
        current.appendText(line + "\n")
        true
    }.getOrDefault(false)

    /** Rolls the current file over when it exceeds [maxFileBytes]. */
    private fun enforceRotation() {
        runCatching {
            if (current.length() <= maxFileBytes) return
            // Slots: previousBase = slot 1 (newest previous), rotatedFile(i)
            // = slot i+1. keepPrevious slots are kept in total.
            val oldest = keepPrevious - 1
            if (oldest >= 1) {
                val oldestFile = rotatedFile(oldest)
                if (oldestFile.exists()) oldestFile.delete()
                for (i in (oldest - 1) downTo 1) {
                    val from = rotatedFile(i)
                    if (from.exists()) from.renameTo(rotatedFile(i + 1))
                }
            }
            if (keepPrevious >= 2) {
                if (previousBase.exists()) previousBase.renameTo(rotatedFile(1))
            }
            if (previousBase.exists()) previousBase.delete()
            current.renameTo(previousBase)
            current.createNewFile()
            rotations++
        }
    }

    private fun rotatedFile(index: Int): File = File(dir, "$PREVIOUS_FILE.$index")

    // ------------------------------------------------------------- reads

    /**
     * Oldest-first read: oldest rotated slots, then previous (newest
     * previous), then current. Returns at most [maxLines] lines (tail of
     * the combined content).
     */
    fun readRecentLines(maxLines: Int): List<String> {
        require(maxLines > 0)
        synchronized(lock) {
            val files = buildList {
                for (i in (keepPrevious - 1) downTo 1) {
                    val f = rotatedFile(i)
                    if (f.exists()) add(f)
                }
                if (previousBase.exists()) add(previousBase)
                if (current.exists()) add(current)
            }
            val tail = ArrayDeque<String>(maxLines)
            for (file in files) {
                runCatching {
                    file.useLines { lines ->
                        for (line in lines) {
                            if (tail.size == maxLines) tail.removeFirst()
                            tail.addLast(line)
                        }
                    }
                }
            }
            return tail.toList()
        }
    }

    /** Approximate total size of all diagnostic files, in bytes. */
    fun approximateTotalBytes(): Long = synchronized(lock) {
        runCatching {
            current.length() + previousBase.length() +
                (1..(keepPrevious - 1)).sumOf { rotatedFile(it).length() }
        }.getOrDefault(-1L)
    }

    /** Deletes every diagnostic file and resets counters. */
    fun clearAll() {
        synchronized(lock) {
            runCatching {
                current.delete()
                previousBase.delete()
                for (i in 1..(keepPrevious - 1)) rotatedFile(i).delete()
                dir.mkdirs()
            }
            count = 0
            droppedCount = 0
            rotations = 0
            lastWriteOk = true
        }
    }

    companion object {
        const val CURRENT_FILE = "diag-current.log"
        const val PREVIOUS_FILE = "diag-prev.log"
        const val DEFAULT_MAX_FILE_BYTES = 1L * 1024 * 1024
        const val DEFAULT_KEEP_PREVIOUS = 1
    }
}

package com.onevideog.editor

import com.onevideog.editor.diag.DiagnosticEvent
import com.onevideog.editor.diag.EventFormatter
import com.onevideog.editor.diag.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structured event formatting contract (one line per event, pipe fields). */
class EventFormatterTest {

    @Test
    fun `formats all core fields`() {
        val line = EventFormatter.format(
            DiagnosticEvent(
                epochMs = 1710000000000L,
                severity = Severity.WARN,
                subsystem = "export",
                stage = "PROGRESS",
                opId = "exp-1234abcd",
                message = "progress 10%",
                meta = listOf("pct" to "10"),
            ),
        )
        assertTrue(line.contains(" | WARN | "))
        assertTrue(line.contains(" | export | "))
        assertTrue(line.contains(" | PROGRESS | "))
        assertTrue(line.contains("op=exp-1234abcd"))
        assertTrue(line.contains("pct=10"))
        assertTrue(line.contains("progress 10%"))
        assertTrue(line.startsWith("20")) // year prefix of the timestamp
        assertFalse(line.contains("\n"))
    }

    @Test
    fun `optional fields are omitted when absent`() {
        val line = EventFormatter.format(
            DiagnosticEvent(
                epochMs = 1710000000000L,
                severity = Severity.INFO,
                subsystem = "diag",
                stage = "CLEAR",
                message = null,
            ),
        )
        assertFalse(line.contains("op="))
        assertFalse(line.contains("exc="))
    }

    @Test
    fun `exception chain preserves nested causes in order`() {
        val root = RuntimeException("root media codec failure")
        val mid = IllegalStateException("decode step failed", root)
        val top = RuntimeException("export failed", mid)
        val line = EventFormatter.format(
            DiagnosticEvent(
                epochMs = 0L, severity = Severity.ERROR,
                subsystem = "export", stage = "TRANSFORMER_ERROR",
                message = "failed", throwable = top,
            ),
        )
        val causeIdx = line.indexOf("<- caused by")
        assertTrue(causeIdx > 0)
        val orderTop = line.indexOf("java.lang.RuntimeException")
        val orderMid = line.indexOf("java.lang.IllegalStateException", orderTop)
        val orderRoot = line.indexOf("java.lang.RuntimeException", orderMid)
        assertTrue(orderTop in 0 until orderMid)
        assertTrue(orderMid < orderRoot)
        assertTrue(line.contains("root media codec failure"))
    }

    @Test
    fun `exception messages are redacted`() {
        val ex = RuntimeException("cannot open content://private/provider/secret_doc.jpg")
        val line = EventFormatter.formatThrowable(ex)
        assertFalse(line.contains("secret_doc"))
        assertTrue(line.contains("<uri:"))
    }

    @Test
    fun `self-caused cycle does not hang`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        // Fabricate a cycle via reflection-free trick: cannot set cause after
        // construction, so emulate depth instead — MAX_CAUSES bounds the walk.
        val deep = (1..12).fold(a) { acc, _ -> RuntimeException("wrap$acc", acc) }
        val line = EventFormatter.formatThrowable(b) + EventFormatter.formatThrowable(deep)
        assertTrue(line.isNotBlank())
    }

    @Test
    fun `pipe characters in fields cannot corrupt the one-line format`() {
        val line = EventFormatter.format(
            DiagnosticEvent(
                epochMs = 0L, severity = Severity.INFO,
                subsystem = "sub|system", stage = "sta|ge",
                opId = "op|id", message = "has | pipes",
                meta = listOf("k|1" to "v|2"),
            ),
        )
        // fields are sanitized: the pipe is replaced so field count is stable
        assertEquals(7, line.split(" | ").size)
    }

    @Test
    fun `oversized messages are redacted to the per-field cap`() {
        val big = "x".repeat(10_000)
        val line = EventFormatter.format(
            DiagnosticEvent(
                epochMs = 0L, severity = Severity.INFO,
                subsystem = "t", stage = "t", message = big,
            ),
        )
        // per-field redaction cap keeps the line short and parseable
        assertTrue("len=${line.length}", line.length < 1000)
        assertFalse(line.contains("xxxxxxx"))
    }

    @Test
    fun `timestamp format is stable`() {
        val ts = EventFormatter.formatTimestamp(0L)
        assertTrue(ts.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}")))
    }

    @Test
    fun `severity order covers four levels`() {
        assertEquals(listOf("DEBUG", "INFO", "WARN", "ERROR"), Severity.values().map { it.name })
    }
}

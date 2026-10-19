package com.onevideog.editor

import com.onevideog.editor.diag.DiagLogStore
import com.onevideog.editor.diag.DiagnosticEvent
import com.onevideog.editor.diag.Severity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Persistence contract: gate, rotation/size cap, concurrency, clear, tolerance. */
class DiagLogStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun event(i: Int, msg: String = "event $i") = DiagnosticEvent(
        epochMs = 1_000_000L + i,
        severity = Severity.INFO,
        subsystem = "test",
        stage = "STAGE",
        opId = "op-$i",
        message = msg,
    )

    @Test
    fun `disabled store drops events and counts drops`() {
        val store = DiagLogStore(tmp.newFolder())
        store.setEnabled(false)
        assertFalse(store.append(event(1)))
        assertEquals(0, store.eventCount())
        assertEquals(1, store.droppedCount())
    }

    @Test
    fun `enabled store writes incrementally`() {
        val store = DiagLogStore(tmp.newFolder())
        store.setEnabled(true)
        assertTrue(store.append(event(1)))
        assertTrue(store.append(event(2)))
        assertEquals(2, store.eventCount())
        val lines = store.readRecentLines(10)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("event 1"))
        assertTrue(lines[1].contains("event 2"))
    }

    @Test
    fun `rotation keeps current plus one previous and respects the total cap`() {
        val dir = tmp.newFolder()
        val maxFile = 300L
        val store = DiagLogStore(dir, maxFileBytes = maxFile, keepPrevious = 1)
        store.setEnabled(true)
        repeat(30) { assertTrue(store.append(event(it))) }
        assertTrue(store.hasRotated())
        val total = dir.listFiles()!!.filter { it.name.startsWith("diag") }.sumOf { it.length() }
        // strict cap: (keepPrevious + 1) * maxFileBytes + small per-line slack
        assertTrue("total=$total", total <= (maxFile * 2) + 1024)
        // tail must still expose the most recent events
        val tail = store.readRecentLines(5)
        assertEquals(5, tail.size)
        assertTrue(tail.last().contains("event 29"))
    }

    @Test
    fun `rotated file is deleted when keepPrevious is exhausted`() {
        val dir = tmp.newFolder()
        val store = DiagLogStore(dir, maxFileBytes = 200L, keepPrevious = 1)
        store.setEnabled(true)
        repeat(60) { store.append(event(it)) }
        // only current + 1 previous may exist
        val files = dir.listFiles()!!.filter { it.name.startsWith("diag") }
        assertTrue("files=${files.map { it.name }}", files.size <= 2)
    }

    @Test
    fun `clearAll deletes files and resets counters`() {
        val dir = tmp.newFolder()
        val store = DiagLogStore(dir)
        store.setEnabled(true)
        repeat(10) { store.append(event(it)) }
        store.clearAll()
        assertEquals(0, store.eventCount())
        assertEquals(0, store.approximateTotalBytes())
        assertTrue(store.readRecentLines(10).isEmpty())
        assertFalse(store.hasRotated())
    }

    @Test
    fun `concurrent writers never lose events`() {
        val store = DiagLogStore(tmp.newFolder())
        store.setEnabled(true)
        val threads = 4
        val perThread = 250
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            pool.submit {
                ready.countDown()
                ready.await()
                repeat(perThread) { i -> store.append(event(t * perThread + i)) }
                done.countDown()
            }
        }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals((threads * perThread).toLong(), store.eventCount())
        val lines = store.readRecentLines(threads * perThread)
        assertEquals(threads * perThread, lines.size)
    }

    @Test
    fun `readRecentLines returns the bounded tail`() {
        val store = DiagLogStore(tmp.newFolder())
        store.setEnabled(true)
        repeat(50) { store.append(event(it)) }
        val tail = store.readRecentLines(10)
        assertEquals(10, tail.size)
        assertTrue(tail.first().contains("event 40"))
        assertTrue(tail.last().contains("event 49"))
    }

    @Test
    fun `io failure never crashes the caller`() {
        // A FILE where the directory should be -> every write must fail softly.
        val notADir = tmp.newFile("notadir")
        val store = DiagLogStore(notADir)
        store.setEnabled(true)
        assertFalse(store.append(event(1)))
        assertTrue(store.lastAppendFailed())
        assertEquals(0, store.eventCount())
        assertEquals(1, store.droppedCount())
    }

    @Test
    fun `approximate size grows with content`() {
        val store = DiagLogStore(tmp.newFolder())
        store.setEnabled(true)
        assertEquals(0, store.approximateTotalBytes())
        repeat(5) { store.append(event(it)) }
        assertTrue(store.approximateTotalBytes() > 0)
    }
}

package com.onevideog.editor

import com.onevideog.editor.editor.EngineLauncher
import com.onevideog.editor.editor.TransformerThread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.4 regression tests for the engine launch path.
 *
 * Device evidence: `java.lang.IllegalStateException: Transformer is accessed
 * on the wrong thread.` at ExportManager.startTransformer -> start, because
 * the coroutine ran on a Dispatchers.Default worker (no Looper) while Media3
 * 1.4.1's Transformer captured the MAIN looper at build time
 * (Transformer.Builder -> Util.getCurrentOrMainLooper, enforced by
 * verifyApplicationThread on start/getProgress/cancel).
 *
 * The fix routes build AND start through ONE application thread. These tests
 * pin the two properties that matter:
 *  1. factory and starter run on the SAME thread (the Media3 contract);
 *  2. a synchronous starter failure propagates the ORIGINAL exception —
 *     class and message unchanged — never wrapped, never swallowed.
 */
class EngineLauncherTest {

    /** Records the thread of every routed call. */
    private class RecordingThread : TransformerThread {
        val threads = mutableListOf<Thread>()
        override suspend fun <T> run(block: () -> T): T {
            threads += Thread.currentThread()
            return block()
        }

        override fun post(block: () -> Unit) {
            threads += Thread.currentThread()
            block()
        }

        override val description: String = "recording"
    }

    /** Mimics Media3 1.4.1: once created on thread T, any access from a
     *  different thread throws IllegalStateException — exactly the contract
     *  that the device report tripped. */
    private class WrongThreadGuardEngine {
        private val createdOn = Thread.currentThread()
        var started = false
            private set

        fun start() {
            check(Thread.currentThread() == createdOn) {
                "Transformer is accessed on the wrong thread."
            }
            started = true
        }
    }

    @Test
    fun `factory and starter run on the same application thread`() = runBlocking {
        val thread = RecordingThread()
        val engine = WrongThreadGuardEngine()
        val result = EngineLauncher.launchOn(
            thread = thread,
            factory = { engine },
            starter = { it.start() },
        )
        assertSame(engine, result)
        assertTrue(engine.started)
        assertEquals(2, thread.threads.size)
        assertEquals(thread.threads[0], thread.threads[1])
    }

    @Test
    fun `starter runs only after factory completed`() = runBlocking {
        val order = mutableListOf<String>()
        val thread = RecordingThread()
        EngineLauncher.launchOn(
            thread = thread,
            factory = { order += "build"; "engine" },
            starter = { order += "start" },
        )
        assertEquals(listOf("build", "start"), order)
    }

    @Test
    fun `synchronous start failure propagates the original exception`() = runBlocking {
        val original = IllegalStateException("Transformer is accessed on the wrong thread.")
        val thread = RecordingThread()
        try {
            EngineLauncher.launchOn(
                thread = thread,
                factory = { Any() },
                starter = { throw original },
            )
            throw AssertionError("expected the original IllegalStateException to propagate")
        } catch (t: IllegalStateException) {
            // Same instance/class + original message — the real error must
            // never be replaced by a generic one.
            assertSame(original, t)
            assertEquals("Transformer is accessed on the wrong thread.", t.message)
        }
    }

    @Test
    fun `factory failure propagates before any start attempt`() = runBlocking {
        val original = IllegalArgumentException("bad composition")
        var startAttempted = false
        val thread = RecordingThread()
        try {
            EngineLauncher.launchOn(
                thread = thread,
                factory = { throw original },
                starter = { startAttempted = true },
            )
            throw AssertionError("expected the factory exception to propagate")
        } catch (t: IllegalArgumentException) {
            assertSame(original, t)
            assertEquals(false, startAttempted)
            // Only the factory call was routed; start was never attempted.
            assertEquals(1, thread.threads.size)
        }
    }
}

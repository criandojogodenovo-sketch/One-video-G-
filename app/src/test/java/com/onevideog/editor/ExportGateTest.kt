package com.onevideog.editor

import com.onevideog.editor.editor.ExportGate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.4 regression tests for the single-export gate. Device evidence: two
 * near-simultaneous export requests (exp-b7bf477d / exp-b96c0125) started two
 * Transformers because the old `if (isRunning) return` check raced — the job
 * was only assigned after slow main-thread preflight work. The gate must
 * allow exactly ONE owner at a time, under contention too.
 */
class ExportGateTest {

    @Test
    fun `first acquire wins and second is rejected`() {
        val gate = ExportGate()
        assertTrue(gate.tryBegin())
        assertTrue(gate.isActive)
        assertFalse(gate.tryBegin())
        assertFalse(gate.tryBegin())
    }

    @Test
    fun `release allows a new owner`() {
        val gate = ExportGate()
        assertTrue(gate.tryBegin())
        gate.end()
        assertFalse(gate.isActive)
        assertTrue(gate.tryBegin())
    }

    @Test
    fun `end is idempotent`() {
        val gate = ExportGate()
        assertTrue(gate.tryBegin())
        gate.end()
        gate.end()
        assertFalse(gate.isActive)
        assertTrue(gate.tryBegin())
    }

    @Test
    fun `concurrent acquirers produce exactly one winner`() {
        val gate = ExportGate()
        val threads = 8
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val winners = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                if (gate.tryBegin()) winners.incrementAndGet()
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(1, winners.get())
        assertTrue(gate.isActive)
    }

    @Test
    fun `sequential owners after release each win once`() {
        val gate = ExportGate()
        val winners = AtomicInteger(0)
        repeat(4) {
            if (gate.tryBegin()) {
                winners.incrementAndGet()
                gate.end()
            }
        }
        assertEquals(4, winners.get())
        assertFalse(gate.isActive)
    }
}

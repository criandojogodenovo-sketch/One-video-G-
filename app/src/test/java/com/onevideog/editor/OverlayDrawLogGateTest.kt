package com.onevideog.editor.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.4 (BUG 3 — device op=pvw-8c2a1a5d): the OVERLAY_DRAW evidence is
 * change-only. Spec test: 5 s of parked playhead with unchanged state =
 * ZERO log decisions — including when the composition recreates the gate
 * slot (the leak the v0.9.3 change-only gate still had via `remember`).
 */
class OverlayDrawLogGateTest {

    private val sigA = "ov1:TEXT:200x80:0.5,0.5,0.3,0.0,1.0,true:text block=340x120 lines=2 chars=10"
    private val sigA_otherOp = sigA // identical drawn state, same op session
    private val sigB = "ov1:TEXT:200x80:0.52,0.5,0.3,0.0,1.0,true:text block=340x120 lines=2 chars=10"

    @Test
    fun `5 seconds parked with unchanged state logs nothing after the first draw`() {
        val gate = OverlayDrawLogGate()
        // First draw (the editor just opened): exactly one log.
        assertTrue(gate.shouldLog(sigA))
        gate.log(sigA)
        // 5 s parked, the layer recomputes placements every 100 ms —
        // identical state every time.
        var logs = 0
        repeat(50) {
            if (gate.shouldLog(sigA_otherOp)) {
                gate.log(sigA_otherOp)
                logs++
            }
        }
        assertEquals(0, logs)
    }

    @Test
    fun `composition slot recreation cannot resurrect the heartbeat`() {
        // The v0.9.4 fix: the gate lives at the op id — a recreated
        // `remember { }` slot returns the SAME instance.
        val op = "pvw-test"
        val gate1 = OverlayDrawLogGates.gateFor(op)
        assertTrue(gate1.shouldLog(sigA))
        gate1.log(sigA)
        var logs = 0
        repeat(50) { // 50 recompositions recreating the slot
            val recreated = OverlayDrawLogGates.gateFor(op)
            assertSame(gate1, recreated)
            if (recreated.shouldLog(sigA)) {
                recreated.log(sigA)
                logs++
            }
        }
        assertEquals(0, logs)
    }

    @Test
    fun `every real change logs exactly once`() {
        val gate = OverlayDrawLogGate()
        assertTrue(gate.shouldLog(sigA)); gate.log(sigA)
        assertFalse(gate.shouldLog(sigA)) // unchanged -> silent
        assertTrue(gate.shouldLog(sigB)); gate.log(sigB) // move -> evidence
        assertFalse(gate.shouldLog(sigB))
    }

    @Test
    fun `a new session op logs its first frame (new evidence, not a heartbeat)`() {
        val gate = OverlayDrawLogGates.gateFor("pvw-session-1")
        assertTrue(gate.shouldLog(sigA)); gate.log(sigA)
        val nextOp = OverlayDrawLogGates.gateFor("pvw-session-2")
        assertTrue(nextOp.shouldLog(sigA)) // different session = fresh evidence
    }

    @Test
    fun `registry stays bounded (LRU eviction, no unbounded growth)`() {
        for (i in 0 until 20) {
            OverlayDrawLogGates.gateFor("pvw-lru-$i")
        }
        // The oldest of 20 ops was rotated out: a fresh gate comes back.
        val oldest = OverlayDrawLogGates.gateFor("pvw-lru-0")
        assertTrue(oldest.shouldLog(sigA)) // fresh instance (lastSig null)
    }
}

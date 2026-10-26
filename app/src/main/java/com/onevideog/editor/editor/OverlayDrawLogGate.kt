package com.onevideog.editor.editor

/**
 * v0.9.4 (BUG 3 — device report op=pvw-8c2a1a5d): change-only gate for the
 * OVERLAY_DRAW evidence.
 *
 * The v0.9.3 gate was change-only, but its state lived in a plain
 * `remember { }` inside the composable: whenever the layer's composition
 * slot is recreated, `lastSig` resets to null and the SAME drawn state logs
 * again — the surviving ~1 Hz duplicate heartbeat in the device report (the
 * values were identical, so the only way past `sig != lastSig` was a reset
 * gate). [OverlayDrawLogGates] pins the gate to the preview op id (the
 * per-session correlation id): a recreated composition gets the SAME gate
 * instance back, so identical drawn state can never log twice — no timer,
 * no reset path, mathematically change-only.
 *
 * Pure — JVM-tested by OverlayDrawLogGateTest (5 s parked + state unchanged
 * = zero log decisions, even with a gate-recreation storm).
 */
class OverlayDrawLogGate {
    var lastSig: String? = null
        private set

    fun shouldLog(sig: String): Boolean = sig != lastSig

    fun log(sig: String) {
        lastSig = sig
    }
}

/**
 * Process-lifetime registry of draw gates, keyed by the preview op id.
 * Bounded (the last [MAX_SESSIONS] sessions stay) so a long app run cannot
 * accumulate unbounded state; a rotated-out session simply logs its first
 * frame again — a new session, new evidence, never a heartbeat.
 */
object OverlayDrawLogGates {
    private const val MAX_SESSIONS = 8
    private val gates = LinkedHashMap<String, OverlayDrawLogGate>()

    @Synchronized
    fun gateFor(opId: String): OverlayDrawLogGate {
        gates.remove(opId)?.let { gate ->
            gates[opId] = gate // refresh LRU position
            return gate
        }
        val fresh = OverlayDrawLogGate()
        gates[opId] = fresh
        while (gates.size > MAX_SESSIONS) {
            gates.remove(gates.keys.first())
        }
        return fresh
    }
}

package com.onevideog.editor.editor

import kotlin.math.abs
import kotlin.math.exp

/**
 * v0.8.0 (Q4) — simple TIME-DOMAIN noise gate (no FFT, no ML).
 *
 * Purpose: attenuate constant background hiss between speech phrases in
 * narration tracks. An envelope follower tracks the signal level; when the
 * level is below [threshold] the gate gain decays towards the floor
 * (practically silence), when it rises above, the gate opens with a fast
 * attack so consonants are not clipped. The gain itself is smoothed
 * exponentially (attack ~2 ms / release ~120 ms) so the transition never
 * clicks.
 *
 * Honest scoping (same pattern as K5's "no pitch adjust"): this is NOT
 * spectral subtraction and NOT ML denoising — it removes low-level
 * constant background between phrases; noise DURING speech stays (the gate
 * is open). Advanced reduction is a future phase (documented in
 * PREVIEW_EXPORT_PARITY.md).
 *
 * Pure float math (no Android types) — JVM-tested via
 * GainAutomationAudioProcessorTest. One instance per processor (stateful);
 * [reset] on processor flush.
 */
class NoiseGate(
    sampleRate: Int,
    /** Open threshold on the normalized level (|sample|, 0..1). ~-34 dBFS. */
    private val threshold: Float = 0.02f,
    /** Gate-open attack in ms. */
    attackMs: Float = 2f,
    /** Gate-close release in ms. */
    releaseMs: Float = 120f,
) {
    private val attackCoef: Float = exp(-1f / (attackMs.coerceAtLeast(0.1f) * 0.001f * sampleRate))
    private val releaseCoef: Float = exp(-1f / (releaseMs.coerceAtLeast(0.1f) * 0.001f * sampleRate))
    private var level = 0f
    private var gateGain = 0f

    /** Processes ONE normalized sample (16-bit paths convert to float first). */
    fun process(input: Float): Float {
        val x = abs(input)
        // Envelope follower: fast up (attack), slow down (release).
        val coef = if (x > level) attackCoef else releaseCoef
        level += (x - level) * (1f - coef)
        // Gate target: open above the threshold, closed below it; the gain
        // itself glides with attack/release so the transition is click-free.
        val target = if (level >= threshold) 1f else 0f
        val gainCoef = if (target > gateGain) attackCoef else releaseCoef
        gateGain += (target - gateGain) * (1f - gainCoef)
        return input * gateGain
    }

    /** Clears the envelope and gate state (processor flush / seek). */
    fun reset() {
        level = 0f
        gateGain = 0f
    }
}

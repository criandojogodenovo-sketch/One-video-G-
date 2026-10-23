package com.onevideog.editor.editor

import com.onevideog.editor.data.AudioClipItem
import com.onevideog.editor.data.AudioKeyframe
import kotlin.math.abs

/**
 * v0.8.0 (Fases P+Q) — THE single audio-gain math, shared by preview and
 * export (parity by construction, the PREVIEW_EXPORT_PARITY.md rule):
 *
 *   gainAt(clip, t) = clip.volume × keyframeAt(keyframes, t) × fadeEnvelope(t)
 *
 * Both consumers instantiate the SAME processor class
 * ([GainAutomationAudioProcessor]) which reads its gain EXCLUSIVELY through
 * this object — there is no second gain implementation anywhere:
 *  - preview: one GainAutomationAudioProcessor per audio clip, injected into
 *    the clip ExoPlayer's DefaultAudioSink (AudioPreviewController);
 *  - export: one GainAutomationAudioProcessor per audio EditedMediaItem
 *    (CompositionFactory.buildAudioSequence).
 *
 * Time basis: t is CLIP-LOCAL ms (0 = the first sample after the trim start)
 * — the same basis the preview players use (Media3 rebases item timestamps
 * to 0 at the clipping start) and the export items use (the processor's
 * sample counter restarts at each item).
 *
 * Pure — no Android types — JVM-tested by AudioGainMathTest.
 */
object AudioGainMath {

    /** Multiplier range of one keyframe (and of the committed clip volume). */
    const val MAX_VOLUME = 2f

    /** Hard cap on automation points per clip (symmetric to speed ramps). */
    const val MAX_KEYFRAMES = 16

    /**
     * Linear gain envelope from the clip fades, in [0, 1]:
     *  - [fadeInMs]: 0 → 1 across the first fadeInMs;
     *  - [fadeOutMs]: 1 → 0 across the last fadeOutMs;
     *  - overlapping fades MULTIPLY (a clip shorter than its fades fades in
     *    AND out simultaneously — documented, tested edge case);
     *  - a fade longer than the clip simply never completes (the envelope
     *    rises monotonically towards 1 but stays below it — documented);
     *  - outside [0, durationMs] the envelope is 0 (silence).
     *
     * @param tMs clip-local time (0 = after trim start)
     * @param durationMs clip-local duration (the envelope domain)
     */
    fun fadeEnvelope(tMs: Long, durationMs: Long, fadeInMs: Long, fadeOutMs: Long): Float {
        if (tMs < 0 || tMs >= durationMs) return 0f
        var gain = 1f
        if (fadeInMs > 0 && tMs < fadeInMs) {
            gain = tMs.toFloat() / fadeInMs.toFloat()
        }
        if (fadeOutMs > 0) {
            val fadeOutStart = durationMs - fadeOutMs
            if (tMs > fadeOutStart) {
                gain *= (durationMs - tMs).toFloat() / fadeOutMs.toFloat()
            }
        }
        return gain.coerceIn(0f, 1f)
    }

    /**
     * Linear interpolation between the automation points at clip-local
     * [tMs]; 1.0 when there are no keyframes. Before the first point the
     * FIRST point's value holds; after the last point the LAST point's
     * value holds (standard automation-curve behaviour). Points are sorted
     * defensively (an unsorted document must not corrupt the curve); equal
     * timestamps keep the first (deterministic).
     */
    fun keyframeAt(keyframes: List<AudioKeyframe>, tMs: Long): Float {
        if (keyframes.isEmpty()) return 1f
        val sorted = keyframes.sortedBy { it.timeMs }
        if (tMs <= sorted.first().timeMs) return sorted.first().volume.coerceIn(0f, MAX_VOLUME)
        if (tMs >= sorted.last().timeMs) return sorted.last().volume.coerceIn(0f, MAX_VOLUME)
        for (i in 0 until sorted.lastIndex) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (tMs in a.timeMs..b.timeMs) {
                val span = (b.timeMs - a.timeMs).coerceAtLeast(1)
                val f = (tMs - a.timeMs).toFloat() / span.toFloat()
                val va = a.volume.coerceIn(0f, MAX_VOLUME)
                val vb = b.volume.coerceIn(0f, MAX_VOLUME)
                return va + (vb - va) * f
            }
        }
        // Unreachable for a sorted list covering tMs inside the range.
        return 1f
    }

    /**
     * THE gain at clip-local [tMs] for [clip]: the clip volume × the
     * automation curve × the fade envelope. Can exceed 1.0 (up to
     * volume × keyframe = 4.0) — amplification is intentional and matches
     * the export PCM coercion; the preview sink multiplies the same number.
     */
    fun gainAt(clip: AudioClipItem, tMs: Long): Float =
        gainAt(
            volume = clip.volume,
            keyframes = clip.gainKeyframes,
            durationMs = clip.trimmedDurationMs,
            fadeInMs = clip.fadeInMs,
            fadeOutMs = clip.fadeOutMs,
            tMs = tMs,
        )

    /**
     * The SAME formula over raw components — the single implementation the
     * shared [GainAutomationAudioProcessor] consumes (its [GainAutomationAudioProcessor.Profile]
     * carries exactly these fields, so preview and export call THIS function,
     * never a copy of it).
     */
    fun gainAt(
        volume: Float,
        keyframes: List<AudioKeyframe>,
        durationMs: Long,
        fadeInMs: Long,
        fadeOutMs: Long,
        tMs: Long,
    ): Float =
        volume.coerceIn(0f, MAX_VOLUME) *
            keyframeAt(keyframes, tMs) *
            fadeEnvelope(tMs, durationMs, fadeInMs, fadeOutMs)

    /**
     * Commit-time validation for automation points (v0.8.0): drops points
     * outside the clip-local domain [0, durationMs], drops non-finite
     * volumes, coerces volumes into 0..2, sorts by time and de-duplicates
     * equal timestamps (first wins), then caps at [MAX_KEYFRAMES]. Callers
     * log AUDIO_KEYFRAME_INVALID / AUDIO_KEYFRAME_LIMIT with the dropped
     * counts (DiagnosticsLogger stays OUT of this pure object).
     *
     * @return the valid points plus the number of dropped entries.
     */
    fun sanitizeKeyframes(
        keyframes: List<AudioKeyframe>,
        durationMs: Long,
    ): Pair<List<AudioKeyframe>, Int> {
        if (keyframes.isEmpty()) return emptyList<AudioKeyframe>() to 0
        var dropped = 0
        val valid = mutableListOf<AudioKeyframe>()
        for (kf in keyframes) {
            val timeOk = kf.timeMs in 0..durationMs
            val volumeOk = kf.volume.isFinite()
            if (!timeOk || !volumeOk) {
                dropped++
                continue
            }
            valid += kf.copy(volume = kf.volume.coerceIn(0f, MAX_VOLUME))
        }
        val sorted = valid.sortedBy { it.timeMs }
        val deduped = mutableListOf<AudioKeyframe>()
        for (kf in sorted) {
            if (deduped.lastOrNull()?.timeMs == kf.timeMs) dropped++ else deduped += kf
        }
        if (deduped.size > MAX_KEYFRAMES) {
            dropped += deduped.size - MAX_KEYFRAMES
            return deduped.take(MAX_KEYFRAMES) to dropped
        }
        return deduped to dropped
    }

    /**
     * The gain profile this math produces for [clip] at [tMs] — the ONE
     * number the shared processor applies. Exposed for tests and for the UI
     * envelope preview (the canvas draws THIS function, not a copy).
     */
    fun envelopePreview(clip: AudioClipItem, samples: Int = 64): List<Float> {
        val duration = clip.trimmedDurationMs
        if (duration <= 0L) return List(samples) { 0f }
        return List(samples) { i ->
            val t = (i.toLong() * (duration - 1)) / (samples - 1).coerceAtLeast(1)
            gainAt(clip, t)
        }
    }

    /** True when the clip has NO automation (constant gain = volume). */
    fun isIdentity(clip: AudioClipItem): Boolean = !clip.hasGainAutomation && abs(clip.volume - 1f) < 1e-6f
}

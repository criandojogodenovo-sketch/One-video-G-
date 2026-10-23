package com.onevideog.editor

import com.onevideog.editor.data.AudioClipItem
import com.onevideog.editor.data.AudioKeyframe
import com.onevideog.editor.editor.AudioGainMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.8.0 (Q1) — THE audio-gain math contract. This object is the single
 * gain implementation shared by preview (GainAutomationAudioProcessor in
 * each audio ExoPlayer's sink) and export (the same processor class in each
 * audio EditedMediaItem) — these tests pin the numbers both pipelines get.
 */
class AudioGainMathTest {

    private fun clip(
        durationMs: Long = 10_000,
        volume: Float = 1f,
        fadeInMs: Long = 0,
        fadeOutMs: Long = 0,
        keyframes: List<AudioKeyframe> = emptyList(),
        noiseGate: Boolean = false,
    ) = AudioClipItem(
        id = "a", uri = "u", displayName = "d", sourceDurationMs = durationMs,
        startMs = 0, trimStartMs = 0, trimEndMs = durationMs, volume = volume,
        fadeInMs = fadeInMs, fadeOutMs = fadeOutMs,
        gainKeyframes = keyframes, noiseGate = noiseGate,
    )

    // ------------------------------------------------------------ fadeEnvelope

    @Test
    fun `no fades means unit envelope inside the clip`() {
        for (t in longArrayOf(0, 1, 5_000, 9_999)) {
            assertEquals(1f, AudioGainMath.fadeEnvelope(t, 10_000, 0, 0), 1e-6f)
        }
    }

    @Test
    fun `fade in ramps linearly from zero`() {
        assertEquals(0f, AudioGainMath.fadeEnvelope(0, 10_000, 2_000, 0), 1e-6f)
        assertEquals(0.25f, AudioGainMath.fadeEnvelope(500, 10_000, 2_000, 0), 1e-4f)
        assertEquals(0.5f, AudioGainMath.fadeEnvelope(1_000, 10_000, 2_000, 0), 1e-4f)
        assertEquals(1f, AudioGainMath.fadeEnvelope(2_000, 10_000, 2_000, 0), 1e-6f)
        assertEquals(1f, AudioGainMath.fadeEnvelope(5_000, 10_000, 2_000, 0), 1e-6f)
    }

    @Test
    fun `fade out ramps linearly to zero at the end`() {
        assertEquals(1f, AudioGainMath.fadeEnvelope(7_999, 10_000, 0, 2_000), 1e-4f)
        assertEquals(0.5f, AudioGainMath.fadeEnvelope(9_000, 10_000, 0, 2_000), 1e-4f)
        // 1 ms before the end the ramp is at 1/2000.
        assertEquals(0.0005f, AudioGainMath.fadeEnvelope(9_999, 10_000, 0, 2_000), 1e-5f)
        // At the domain edge (t = durationMs) the envelope is silence.
        assertEquals(0f, AudioGainMath.fadeEnvelope(10_000, 10_000, 0, 2_000), 1e-6f)
    }

    @Test
    fun `outside the clip the envelope is silence`() {
        assertEquals(0f, AudioGainMath.fadeEnvelope(-1, 10_000, 0, 0), 1e-6f)
        assertEquals(0f, AudioGainMath.fadeEnvelope(10_000, 10_000, 0, 0), 1e-6f)
        assertEquals(0f, AudioGainMath.fadeEnvelope(20_000, 10_000, 0, 0), 1e-6f)
    }

    @Test
    fun `overlapping fades multiply`() {
        // fadeIn 6s + fadeOut 6s on a 10s clip: fades overlap between 4s and 6s.
        val g = AudioGainMath.fadeEnvelope(5_000, 10_000, 6_000, 6_000)
        // fadeIn at 5s = 5/6; fadeOut at 5s = 5/6; product = 25/36 ≈ 0.694.
        assertEquals(25f / 36f, g, 1e-3f)
    }

    @Test
    fun `fades longer than the clip never complete but stay monotonic`() {
        val envelope = (0..9).map { i ->
            AudioGainMath.fadeEnvelope(i.toLong() * 1_000, 10_000, 15_000, 0)
        }
        // Monotonically rising, starts silent, never reaches 1 within the
        // clip (at 9 s of a 15 s fade the envelope is 0.6).
        assertEquals(0f, envelope.first(), 1e-6f)
        assertTrue(envelope.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(envelope.last() < 1f)
        assertTrue(envelope.last() > 0.5f)
    }

    // ------------------------------------------------------------- keyframeAt

    @Test
    fun `no keyframes means unit curve`() {
        assertEquals(1f, AudioGainMath.keyframeAt(emptyList(), 0), 1e-6f)
        assertEquals(1f, AudioGainMath.keyframeAt(emptyList(), 5_000), 1e-6f)
    }

    @Test
    fun `single keyframe holds its value everywhere`() {
        val kfs = listOf(AudioKeyframe(2_000, 0.5f))
        assertEquals(0.5f, AudioGainMath.keyframeAt(kfs, 0), 1e-6f)
        assertEquals(0.5f, AudioGainMath.keyframeAt(kfs, 2_000), 1e-6f)
        assertEquals(0.5f, AudioGainMath.keyframeAt(kfs, 9_999), 1e-6f)
    }

    @Test
    fun `keyframes interpolate linearly between points`() {
        val kfs = listOf(
            AudioKeyframe(0, 1f),
            AudioKeyframe(1_000, 0.5f),
            AudioKeyframe(3_000, 1.5f),
        )
        assertEquals(1f, AudioGainMath.keyframeAt(kfs, 0), 1e-6f)
        assertEquals(0.75f, AudioGainMath.keyframeAt(kfs, 500), 1e-4f)
        assertEquals(0.5f, AudioGainMath.keyframeAt(kfs, 1_000), 1e-6f)
        // Between 1s (0.5) and 3s (1.5): at 2s the midpoint is 1.0.
        assertEquals(1f, AudioGainMath.keyframeAt(kfs, 2_000), 1e-4f)
        assertEquals(1.5f, AudioGainMath.keyframeAt(kfs, 3_000), 1e-6f)
        // After the last point the last value holds.
        assertEquals(1.5f, AudioGainMath.keyframeAt(kfs, 8_000), 1e-6f)
    }

    @Test
    fun `unsorted keyframes are handled deterministically`() {
        val kfs = listOf(
            AudioKeyframe(3_000, 1.5f),
            AudioKeyframe(0, 1f),
            AudioKeyframe(1_000, 0.5f),
        )
        assertEquals(0.5f, AudioGainMath.keyframeAt(kfs, 1_000), 1e-6f)
        // Midpoint of the 1s..3s segment is exactly 1.0.
        assertEquals(1f, AudioGainMath.keyframeAt(kfs, 2_000), 1e-4f)
        assertEquals(0.9995f, AudioGainMath.keyframeAt(kfs, 1_999), 1e-4f)
    }

    @Test
    fun `keyframe volumes are coerced into 0 to 2`() {
        val kfs = listOf(AudioKeyframe(0, 9f), AudioKeyframe(1_000, -3f))
        assertEquals(AudioGainMath.MAX_VOLUME, AudioGainMath.keyframeAt(kfs, 0), 1e-6f)
        assertEquals(0f, AudioGainMath.keyframeAt(kfs, 1_000), 1e-6f)
    }

    // ------------------------------------------------------------------ gainAt

    @Test
    fun `gainAt combines volume keyframes and envelope`() {
        // volume 0.8, keyframe 0.5 at t, fadeIn half done (0.5):
        // 0.8 × 0.5 × 0.5 = 0.2.
        val c = clip(
            volume = 0.8f,
            fadeInMs = 2_000,
            keyframes = listOf(AudioKeyframe(1_000, 0.5f)),
        )
        assertEquals(0.2f, AudioGainMath.gainAt(c, 1_000), 1e-4f)
    }

    @Test
    fun `gainAt with no automation is exactly the clip volume`() {
        assertEquals(1f, AudioGainMath.gainAt(clip(), 5_000), 1e-6f)
        assertEquals(0.7f, AudioGainMath.gainAt(clip(volume = 0.7f), 0), 1e-6f)
        assertEquals(0.7f, AudioGainMath.gainAt(clip(volume = 0.7f), 9_999), 1e-6f)
    }

    @Test
    fun `gainAt silence outside the clip domain`() {
        assertEquals(0f, AudioGainMath.gainAt(clip(volume = 2f), 10_000), 1e-6f)
        assertEquals(0f, AudioGainMath.gainAt(clip(volume = 2f), -5), 1e-6f)
    }

    // -------------------------------------------------------------- identity

    @Test
    fun `identity detection`() {
        assertTrue(AudioGainMath.isIdentity(clip()))
        assertFalse(AudioGainMath.isIdentity(clip(volume = 1.2f)))
        assertFalse(AudioGainMath.isIdentity(clip(fadeInMs = 100)))
        assertFalse(AudioGainMath.isIdentity(clip(fadeOutMs = 100)))
        assertFalse(
            AudioGainMath.isIdentity(clip(keyframes = listOf(AudioKeyframe(0, 1f)))),
        )
        assertFalse(AudioGainMath.isIdentity(clip(noiseGate = true)))
    }

    // ------------------------------------------------------ sanitizeKeyframes

    @Test
    fun `sanitize drops points outside the clip domain`() {
        val (valid, dropped) = AudioGainMath.sanitizeKeyframes(
            listOf(
                AudioKeyframe(0, 1f),
                AudioKeyframe(5_000, 0.5f),
                AudioKeyframe(10_000, 1f), // == durationMs: the domain is inclusive
                AudioKeyframe(10_001, 1f), // dropped
                AudioKeyframe(-1, 1f), // dropped
            ),
            10_000,
        )
        assertEquals(3, valid.size)
        assertEquals(2, dropped)
    }

    @Test
    fun `sanitize coerces volumes and drops non-finite`() {
        val (valid, dropped) = AudioGainMath.sanitizeKeyframes(
            listOf(
                AudioKeyframe(0, 5f), // coerced to 2
                AudioKeyframe(1_000, Float.NaN), // dropped
                AudioKeyframe(2_000, Float.POSITIVE_INFINITY), // dropped
            ),
            10_000,
        )
        assertEquals(1, valid.size)
        assertEquals(AudioGainMath.MAX_VOLUME, valid[0].volume, 1e-6f)
        assertEquals(2, dropped)
    }

    @Test
    fun `sanitize sorts and dedups equal timestamps`() {
        val (valid, dropped) = AudioGainMath.sanitizeKeyframes(
            listOf(
                AudioKeyframe(2_000, 1.5f),
                AudioKeyframe(0, 0.5f),
                AudioKeyframe(2_000, 0.5f), // duplicate time — dropped (first wins)
            ),
            10_000,
        )
        assertEquals(listOf(0L, 2_000L), valid.map { it.timeMs })
        assertEquals(0.5f, valid[0].volume, 1e-6f)
        assertEquals(1.5f, valid[1].volume, 1e-6f)
        assertEquals(1, dropped)
    }

    @Test
    fun `sanitize caps at MAX_KEYFRAMES`() {
        val points = (0 until 20).map { AudioKeyframe(it.toLong() * 100, 1f) }
        val (valid, dropped) = AudioGainMath.sanitizeKeyframes(points, 10_000)
        assertEquals(AudioGainMath.MAX_KEYFRAMES, valid.size)
        assertEquals(20 - AudioGainMath.MAX_KEYFRAMES, dropped)
    }

    @Test
    fun `sanitize keeps an empty list cheap`() {
        val (valid, dropped) = AudioGainMath.sanitizeKeyframes(emptyList(), 10_000)
        assertTrue(valid.isEmpty())
        assertEquals(0, dropped)
    }

    // -------------------------------------------------------------- envelope

    @Test
    fun `envelope preview samples the same gainAt`() {
        val c = clip(fadeInMs = 5_000, volume = 0.5f)
        val preview = AudioGainMath.envelopePreview(c, samples = 16)
        assertEquals(16, preview.size)
        assertEquals(0f, preview.first(), 1e-4f)
        assertEquals(AudioGainMath.gainAt(c, 7_500), preview[8], 1e-5f)
    }

    @Test
    fun `envelope preview of a zero-length clip is silence`() {
        val preview = AudioGainMath.envelopePreview(clip(durationMs = 0), samples = 8)
        assertEquals(8, preview.size)
        assertTrue(preview.all { it == 0f })
    }
}

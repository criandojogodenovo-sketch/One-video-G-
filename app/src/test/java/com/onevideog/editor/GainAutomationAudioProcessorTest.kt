package com.onevideog.editor

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.onevideog.editor.data.AudioKeyframe
import com.onevideog.editor.editor.AudioGainMath
import com.onevideog.editor.editor.GainAutomationAudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * v0.8.0 (Q2) — the shared gain-automation processor. These JVM tests pin
 * the PARITY contract: the gain the processor applies per frame MUST equal
 * [AudioGainMath.gainAt] at the frame's clip-local time — the exact same
 * number the export pipeline bakes into the file. Also: identity → NOT_SET
 * (zero export cost), alwaysActive → live preview edits, flush restarts the
 * gain timeline (seek semantics), anchorTo re-bases it (preview clock), and
 * the noise gate attenuates only low-level signal.
 */
class GainAutomationAudioProcessorTest {

    private val sampleRate = 48_000
    private val channels = 2

    private fun format(encoding: Int = C.ENCODING_PCM_16BIT) =
        AudioProcessor.AudioFormat(sampleRate, channels, encoding)

    private fun processor(
        profile: GainAutomationAudioProcessor.Profile,
        alwaysActive: Boolean = false,
    ): GainAutomationAudioProcessor {
        val p = GainAutomationAudioProcessor(profile, alwaysActive)
        p.configure(format())
        p.flush()
        return p
    }

    /** Direct native-order buffer of interleaved 16-bit frames. */
    private fun pcm16(vararg frameSamples: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frameSamples.size * channels * 2)
            .order(ByteOrder.nativeOrder())
        for (s in frameSamples) {
            repeat(channels) { buffer.putShort(s.toShort()) }
        }
        buffer.flip()
        return buffer
    }

    /** Direct native-order buffer of interleaved float frames. */
    private fun pcmFloat(vararg frameSamples: Float): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frameSamples.size * channels * 4)
            .order(ByteOrder.nativeOrder())
        for (s in frameSamples) {
            repeat(channels) { buffer.putFloat(s) }
        }
        buffer.flip()
        return buffer
    }

    private fun GainAutomationAudioProcessor.queueAndRead(input: ByteBuffer): ByteBuffer {
        queueInput(input)
        return output
    }

    /**
     * Rewinds the buffer for the next queueInput call. NOTE: the queueInput
     * contract requires a direct buffer in NATIVE byte order — do NOT pass
     * a plain duplicate(): ByteBuffer.duplicate() does NOT preserve the byte
     * order (the duplicate defaults to BIG_ENDIAN, so the processor would
     * read every sample byte-swapped). The helper rewinds the ORIGINAL
     * buffer, which keeps the native order it was created with.
     */
    private fun ByteBuffer.rewindForQueue(): ByteBuffer {
        rewind()
        return this
    }

    private fun ByteBuffer.firstShortOfFrame(): Int = getShort(0).toInt()

    private fun frames(ms: Long): Int = (ms * sampleRate / 1000L).toInt()

    // ---------------------------------------------------------------- config

    private fun assertSameFormatAsInput(configured: AudioProcessor.AudioFormat) {
        // AudioFormat has no equals override — compare by fields.
        assertEquals(sampleRate, configured.sampleRate)
        assertEquals(channels, configured.channelCount)
        assertEquals(C.ENCODING_PCM_16BIT, configured.encoding)
    }

    @Test
    fun `identity profile configures as NOT_SET when not always active - zero export cost`() {
        val p = GainAutomationAudioProcessor(
            GainAutomationAudioProcessor.Profile(durationMs = 10_000),
            alwaysActive = false,
        )
        assertEquals(
            AudioProcessor.AudioFormat.NOT_SET.sampleRate,
            p.configure(format()).sampleRate,
        )
    }

    @Test
    fun `identity profile stays active when alwaysActive - live preview edits`() {
        val p = GainAutomationAudioProcessor(
            GainAutomationAudioProcessor.Profile(durationMs = 10_000),
            alwaysActive = true,
        )
        assertSameFormatAsInput(p.configure(format()))
    }

    @Test
    fun `automation profile configures active - export path`() {
        val p = GainAutomationAudioProcessor(
            GainAutomationAudioProcessor.Profile(
                durationMs = 10_000, fadeInMs = 1_000,
            ),
            alwaysActive = false,
        )
        assertSameFormatAsInput(p.configure(format()))
    }

    @Test
    fun `compressed formats are rejected`() {
        val p = GainAutomationAudioProcessor(
            GainAutomationAudioProcessor.Profile(durationMs = 10_000),
            alwaysActive = true,
        )
        assertThrows(AudioProcessor.UnhandledAudioFormatException::class.java) {
            p.configure(AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_AC3))
        }
    }

    // ------------------------------------------------- fade gain per frame

    @Test
    fun `fade in gain matches AudioGainMath at every checked frame`() {
        val fadeInMs = 1_000L
        val p = processor(
            GainAutomationAudioProcessor.Profile(durationMs = 10_000, fadeInMs = fadeInMs),
        )
        val chunkFrames = frames(100) // 100 ms chunks
        val input = pcm16(*IntArray(chunkFrames) { 10_000 })
        var frame = 0L
        // Queue 1 second of audio in 10 chunks; spot-check frames.
        repeat(10) {
            val out = p.queueAndRead(input.rewindForQueue())
            for (f in 0 until chunkFrames) {
                val expected = AudioGainMath.fadeEnvelope(
                    frame * 1_000_000L / sampleRate / 1000L, 10_000, fadeInMs, 0,
                )
                val got = out.getShort((f * channels * 2)).toInt()
                val want = (10_000 * expected).toInt()
                assertTrue(
                    "frame $frame: got $got want $want",
                    kotlin.math.abs(got - want) <= 2,
                )
                frame++
            }
        }
    }

    @Test
    fun `fade out reaches silence at the end of the clip domain`() {
        val p = processor(
            GainAutomationAudioProcessor.Profile(durationMs = 10_000, fadeOutMs = 1_000),
        )
        // Jump the counter near the end: queue 9.9 s of frames first.
        val totalFrames = frames(9_900)
        val chunk = 4_800
        val input = pcm16(*IntArray(chunk) { 8_000 })
        var queued = 0
        while (queued < totalFrames) {
            p.queueAndRead(input.rewindForQueue())
            queued += chunk
        }
        // Next frame is at ~9_900 ms: gain = (10_000-9_900)/1_000 = 0.1.
        val out = p.queueAndRead(input.rewindForQueue())
        val got = out.firstShortOfFrame()
        val want = 8_000 * 0.1f
        assertTrue("got $got want ~$want", kotlin.math.abs(got - want) <= 2)
    }

    @Test
    fun `keyframe curve gain matches AudioGainMath`() {
        val p = processor(
            GainAutomationAudioProcessor.Profile(
                durationMs = 10_000,
                keyframes = listOf(
                    AudioKeyframe(0, 0.5f),
                    AudioKeyframe(2_000, 1.5f),
                ),
            ),
        )
        // At 1_000 ms the linear interpolation gives exactly 1.0.
        val half = frames(1_000)
        val input = pcm16(*IntArray(half) { 10_000 })
        p.queueAndRead(input.rewindForQueue())
        val out = p.queueAndRead(input.rewindForQueue())
        val got = out.firstShortOfFrame() // t = 1_000 ms
        assertTrue("got $got want ~10_000", kotlin.math.abs(got - 10_000) <= 2)
    }

    @Test
    fun `clip volume multiplies the automation`() {
        val p = processor(
            GainAutomationAudioProcessor.Profile(durationMs = 10_000, volume = 0.5f),
        )
        val input = pcm16(*IntArray(frames(10)) { 10_000 })
        val out = p.queueAndRead(input)
        assertEquals(5_000, out.firstShortOfFrame())
    }

    @Test
    fun `float path applies the same gain`() {
        val fp = GainAutomationAudioProcessor(
            GainAutomationAudioProcessor.Profile(durationMs = 10_000, fadeInMs = 1_000),
            alwaysActive = false,
        )
        fp.configure(format(C.ENCODING_PCM_FLOAT))
        fp.flush()
        val half = frames(500)
        val input = pcmFloat(*FloatArray(half) { 0.25f })
        fp.queueInput(input.rewindForQueue())
        // First chunk read: frames at t = 0..500ms; frame 12_000 is at
        // exactly 250 ms → gain = 250/1000 = 0.25 (fadeIn 1 s).
        val out = fp.output
        val got = out.getFloat(12_000 * channels * 4)
        assertEquals(0.25f * 0.25f, got, 1e-4f)
    }

    // ------------------------------------------------------- flush / anchor

    @Test
    fun `flush restarts the gain timeline`() {
        val p = processor(
            GainAutomationAudioProcessor.Profile(durationMs = 10_000, fadeInMs = 1_000),
        )
        val input = pcm16(*IntArray(frames(10)) { 10_000 })
        p.queueAndRead(input.rewindForQueue())
        p.queueAndRead(input.rewindForQueue())
        // Without a flush the counter is at ~20 ms: gain = 0.02.
        val out = p.queueAndRead(input.rewindForQueue())
        val before = out.firstShortOfFrame()
        assertTrue(kotlin.math.abs(before - 200) <= 2)
        p.flush()
        val after = p.queueAndRead(input.rewindForQueue())
        // Counter restarted: t = 0 → gain 0 (silence).
        assertEquals(0, after.firstShortOfFrame())
    }

    @Test
    fun `anchorTo re-bases the gain timeline - preview clock`() {
        val p = processor(
            GainAutomationAudioProcessor.Profile(durationMs = 10_000, fadeInMs = 1_000),
            alwaysActive = true,
        )
        val input = pcm16(*IntArray(frames(10)) { 10_000 })
        p.queueAndRead(input)
        // The preview player reports 1_000 ms (e.g. after a seek): the next
        // frame must get the gain at t = 1_000 ms (fully faded in).
        p.anchorTo(1_000)
        val out = p.queueAndRead(input.rewindForQueue())
        assertEquals(10_000, out.firstShortOfFrame())
    }

    // -------------------------------------------------------------- gate (Q4)

    @Test
    fun `noise gate attenuates low-level hiss and passes loud signal`() {
        val p = processor(
            GainAutomationAudioProcessor.Profile(
                durationMs = 10_000,
                noiseGate = true,
            ),
        )
        // 100 ms of very quiet noise (below the 0.02 threshold): the gate
        // closes → output collapses towards silence.
        val quiet = pcm16(*IntArray(frames(100)) { 100 }) // ≈ 0.003
        var out = p.queueAndRead(quiet.rewindForQueue())
        var maxTail = 0
        while (out.hasRemaining()) {
            val v = kotlin.math.abs(out.getShort().toInt())
            if (out.position() > frames(50) * channels * 2) maxTail = maxOf(maxTail, v)
        }
        assertTrue("quiet tail should be gated, max=$maxTail", maxTail < 100)

        // 200 ms of loud signal: the gate opens (attack ~2 ms) → passthrough.
        val loud = pcm16(*IntArray(frames(200)) { 20_000 }) // ≈ 0.61
        out = p.queueAndRead(loud.rewindForQueue())
        var maxOpen = 0
        while (out.hasRemaining()) {
            val v = kotlin.math.abs(out.getShort().toInt())
            if (out.position() > frames(100) * channels * 2) maxOpen = maxOf(maxOpen, v)
        }
        assertTrue("loud tail should pass, max=$maxOpen", maxOpen > 19_000)
    }

    @Test
    fun `flush resets the gate state`() {
        val p = processor(
            GainAutomationAudioProcessor.Profile(durationMs = 10_000, noiseGate = true),
        )
        val loud = pcm16(*IntArray(frames(50)) { 20_000 })
        p.queueAndRead(loud)
        p.flush()
        // After the flush the gate is closed again (reset) — the first quiet
        // frames after a seek must not inherit the open gate.
        val quiet = pcm16(*IntArray(frames(10)) { 100 })
        val out = p.queueAndRead(quiet)
        val max = run {
            var m = 0
            while (out.hasRemaining()) m = maxOf(m, kotlin.math.abs(out.getShort().toInt()))
            m
        }
        assertTrue("gate must reset on flush, max=$max", max < 100)
    }

    // --------------------------------------------------------------- profile

    @Test
    fun `profileOf maps the model and sanitizes the keyframes`() {
        val clip = com.onevideog.editor.data.AudioClipItem(
            id = "a", uri = "u", displayName = "d", sourceDurationMs = 10_000,
            trimStartMs = 1_000, trimEndMs = 6_000,
            volume = 4f, // coerced to MAX
            fadeInMs = 500, fadeOutMs = 800,
            gainKeyframes = listOf(
                AudioKeyframe(100, 0.5f),
                AudioKeyframe(99_999, 1f), // outside the domain — dropped
                AudioKeyframe(100, 0.25f), // duplicate time — dropped
            ),
            noiseGate = true,
        )
        val profile = GainAutomationAudioProcessor.profileOf(clip)
        assertEquals(5_000, profile.durationMs)
        assertEquals(500, profile.fadeInMs)
        assertEquals(800, profile.fadeOutMs)
        assertEquals(AudioGainMath.MAX_VOLUME, profile.volume, 1e-6f)
        assertEquals(1, profile.keyframes.size)
        assertEquals(0.5f, profile.keyframes[0].volume, 1e-6f)
        assertTrue(profile.noiseGate)
        assertTrue(!profile.isIdentity)
    }

    @Test
    fun `identity profile detection`() {
        val plain = com.onevideog.editor.data.AudioClipItem(
            id = "a", uri = "u", displayName = "d", sourceDurationMs = 1_000,
        )
        assertTrue(GainAutomationAudioProcessor.profileOf(plain).isIdentity)
    }

    @Test
    fun `processor is a BaseAudioProcessor - the 1_4_1 extension point`() {
        val p = GainAutomationAudioProcessor(
            GainAutomationAudioProcessor.Profile(durationMs = 10_000),
            alwaysActive = false,
        )
        assertTrue(p is BaseAudioProcessor)
    }
}

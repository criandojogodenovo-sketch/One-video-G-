package com.onevideog.editor.editor

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.onevideog.editor.data.AudioClipItem
import com.onevideog.editor.data.AudioKeyframe
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * v0.8.0 (Q2) — the ONE gain-automation processor, used by BOTH pipelines
 * (the parity rule: one implementation, two consumers):
 *
 *  - **Export** ([CompositionFactory.buildAudioSequence]): one instance per
 *    audio EditedMediaItem in every audio sequence — replaces the constant
 *    VolumeAudioProcessor for independent audio clips (video clips keep
 *    VolumeAudioProcessor, v0.7.0 behaviour untouched).
 *  - **Preview** ([AudioPreviewController.createPlayer]): one instance per
 *    audio clip ExoPlayer, injected into the sink via
 *    DefaultAudioSink.Builder.setAudioProcessors (the documented extension
 *    point; DefaultRenderersFactory.buildAudioSink — 1.4.1 3-arg signature,
 *    verified against the 1.4.1 sources).
 *
 * The applied gain comes EXCLUSIVELY from [AudioGainMath] (volume ×
 * keyframe curve × fade envelope) — there is no second gain math anywhere.
 *
 * Time base: an internal frame counter restarted by flush() (the sink
 * flushes on seek/reconfigure), offset by [baseUs]:
 *  - export: baseUs = 0 for the whole item → t=0 is the item's first
 *    sample = the clip-local 0 (after trim) — sample-exact;
 *  - preview: the controller re-anchors baseUs to the player's playback
 *    clock every sync tick ([anchorTo]) — self-healing after seeks; the
 *    known approximation (gain computed at processing time leads/lags what
 *    is heard by the sink buffer depth) is documented in
 *    PREVIEW_EXPORT_PARITY.md. Constant gains are unaffected (timeless).
 *
 * Threading: [updateProfile]/[anchorTo] are called from the MAIN thread
 * (preview sync); all other entry points run on the sink's playback
 * thread. The two shared fields are @Volatile; the profile is an immutable
 * data class swapped atomically.
 *
 * PCM support matches VolumeAudioProcessor: 16-bit int and 32-bit float;
 * the noise gate (Q4) runs on the normalized float domain per sample,
 * AFTER the automation gain.
 */
@UnstableApi
class GainAutomationAudioProcessor(
    initialProfile: Profile,
    /**
     * Preview = true: the processor stays ACTIVE even with an identity
     * profile, so live gain edits never need a player/sink re-configure.
     * Export = false: an identity profile configures as NOT_SET — the
     * zero-cost passthrough VolumeAudioProcessor already had (no export
     * cost for clips without automation).
     */
    private val alwaysActive: Boolean,
) : BaseAudioProcessor() {

    /**
     * The immutable gain profile (a snapshot of the model's audio
     * automation). [of] is the ONE model→profile mapping both pipelines use.
     */
    data class Profile(
        val durationMs: Long,
        val fadeInMs: Long = 0,
        val fadeOutMs: Long = 0,
        val volume: Float = 1f,
        val keyframes: List<AudioKeyframe> = emptyList(),
        val noiseGate: Boolean = false,
    ) {
        val isIdentity: Boolean
            get() = fadeInMs <= 0 && fadeOutMs <= 0 && keyframes.isEmpty() &&
                !noiseGate && volume == 1f
    }

    @Volatile private var profile: Profile = initialProfile
    @Volatile private var baseUs = 0L

    // Playback-thread-only state (guarded by the sink's single-threaded
    // queueInput contract; reset in onFlush).
    private var frameCounter = 0L
    private var gate: NoiseGate? = null

    /** Swaps the gain profile (main thread; preview live edits). */
    fun updateProfile(newProfile: Profile) {
        profile = newProfile
    }

    /**
     * Re-anchors the gain timeline so the NEXT queued frame is treated as
     * being at [positionMs] (clip-local ms — the preview players' window is
     * 0-based at the trim start, exactly the clip-local basis). Main thread.
     */
    fun anchorTo(positionMs: Long) {
        val processedUs = frameCounter * 1_000_000L / sampleRateOrDefault()
        baseUs = positionMs * 1000L - processedUs
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (!alwaysActive && profile.isIdentity) {
            // Zero-cost passthrough — identical to VolumeAudioProcessor's
            // gain==1f rule (no export cost for clips without automation).
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val fmt = inputAudioFormat
        val sampleRate = fmt.sampleRate.coerceAtLeast(1)
        val channelCount = fmt.channelCount.coerceAtLeast(1)
        val p = profile
        val gate = if (p.noiseGate) {
            // Created lazily on first use — needs the configured sample rate.
            this.gate ?: NoiseGate(sampleRate).also { this.gate = it }
        } else {
            null
        }
        val output = replaceOutputBuffer(size)
        val isFloat = fmt.encoding == C.ENCODING_PCM_FLOAT

        while (inputBuffer.hasRemaining()) {
            // Clip-local time of THIS frame: absolute sample count since the
            // last flush + the preview anchor. Computed from the counter (no
            // accumulation drift, error <= 1 µs).
            val tMs = (baseUs + frameCounter * 1_000_000L / sampleRate) / 1000L
            val gain = AudioGainMath.gainAt(
                volume = p.volume,
                keyframes = p.keyframes,
                durationMs = p.durationMs,
                fadeInMs = p.fadeInMs,
                fadeOutMs = p.fadeOutMs,
                tMs = tMs,
            )
            repeat(channelCount) {
                if (isFloat) {
                    val sample = inputBuffer.getFloat()
                    output.putFloat(
                        if (gate != null) gate.process(sample) * gain else sample * gain,
                    )
                } else {
                    val sample = inputBuffer.getShort().toInt()
                    val scaled = if (gate != null) {
                        gate.process(sample / SHORT_SCALE) * gain * SHORT_SCALE
                    } else {
                        sample * gain
                    }
                    output.putShort(
                        scaled.roundToInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            .toShort(),
                    )
                }
            }
            frameCounter++
        }
        output.flip()
        inputBuffer.position(inputBuffer.limit())
    }

    override fun onFlush() {
        // The sink flushes on seek/reconfigure: the gain timeline restarts
        // and the preview controller re-anchors to the new position. The
        // gate state must also restart (a seek cannot inherit the old
        // envelope or it would swallow the first words after the seek).
        frameCounter = 0L
        gate?.reset()
    }

    override fun onReset() {
        frameCounter = 0L
        gate = null
        baseUs = 0L
    }

    private fun sampleRateOrDefault(): Int =
        inputAudioFormat.sampleRate.takeIf { it > 0 } ?: 48_000

    companion object {
        private const val SHORT_SCALE = 32768f

        /**
         * THE model→profile mapping (both pipelines): volume coerced,
         * keyframes sanitized defensively (documents written by older
         * versions or edited externally must not poison the processor —
         * invalid points are dropped silently here; the COMMIT path logs
         * AUDIO_KEYFRAME_INVALID via the ViewModel).
         */
        fun profileOf(clip: AudioClipItem): Profile = Profile(
            durationMs = clip.trimmedDurationMs,
            fadeInMs = clip.fadeInMs.coerceAtLeast(0),
            fadeOutMs = clip.fadeOutMs.coerceAtLeast(0),
            volume = clip.volume.coerceIn(0f, AudioGainMath.MAX_VOLUME),
            keyframes = AudioGainMath.sanitizeKeyframes(
                clip.gainKeyframes,
                clip.trimmedDurationMs,
            ).first,
            noiseGate = clip.noiseGate,
        )
    }
}

package com.onevideog.editor.editor

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Applies a linear gain to PCM audio (16-bit int or 32-bit float).
 *
 * Media3 1.4.1's EditedMediaItem has no setVolume(), so per-clip volume is
 * implemented as an AudioProcessor — the documented extension point.
 * Instances are per-clip: never share one across EditedMediaItems.
 */
@UnstableApi
class VolumeAudioProcessor(private val gain: Float) : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // Inactive (passthrough) when there is nothing to change.
        return if (gain == 1f) AudioProcessor.AudioFormat.NOT_SET else inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            while (inputBuffer.hasRemaining()) {
                output.putFloat(inputBuffer.getFloat() * gain)
            }
        } else {
            while (inputBuffer.hasRemaining()) {
                val sample = inputBuffer.getShort().toInt()
                val scaled = (sample * gain).roundToInt()
                output.putShort(
                    scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort(),
                )
            }
        }
        output.flip()
        inputBuffer.position(inputBuffer.limit())
    }
}

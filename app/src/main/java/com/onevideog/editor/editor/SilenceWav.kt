package com.onevideog.editor.editor

import java.io.File
import java.io.FileOutputStream

/**
 * Generates silent WAV files used as timeline padding inside audio
 * EditedMediaItemSequences (Media3 1.4.1 sequences have no start-offset API,
 * so an audio clip placed at project time T is exported as
 * [silence(T), trimmed audio] inside its own parallel sequence).
 *
 * Files are PCM 16-bit mono 44.1 kHz — small to generate, universally
 * decodable, and re-encoded to AAC by the export pipeline.
 */
object SilenceWav {

    const val SAMPLE_RATE = 44_100
    private const val BYTES_PER_SECOND = SAMPLE_RATE * 2 // mono, 16-bit

    /**
     * Writes (or reuses) a silent WAV of [durationMs] into [dir].
     * Files are named silence_<ms>.wav so identical gaps are deduplicated.
     * Returns null when the duration is too small to matter.
     */
    fun writeOrReuse(dir: File, durationMs: Long): File? {
        if (durationMs < 20) return null
        dir.mkdirs()
        val file = File(dir, "silence_$durationMs.wav")
        if (file.isFile && file.length() > 44) return file
        val dataBytes = (durationMs * BYTES_PER_SECOND / 1000.0).toLong()
        if (dataBytes <= 0) return null
        FileOutputStream(file).use { out ->
            writeHeader(out, dataBytes)
            // Stream zeros in bounded chunks; a 5-minute gap is ~26 MB.
            val chunk = ByteArray(64 * 1024)
            var remaining = dataBytes
            while (remaining > 0) {
                val n = minOf(chunk.size.toLong(), remaining).toInt()
                out.write(chunk, 0, n)
                remaining -= n
            }
            out.flush()
        }
        return file
    }

    /** Deletes every silence_*.wav in [dir]; safe to call repeatedly. */
    fun cleanup(dir: File) {
        dir.listFiles { f -> f.name.startsWith("silence_") && f.name.endsWith(".wav") }
            ?.forEach { it.delete() }
    }

    private fun writeHeader(out: FileOutputStream, dataBytes: Long) {
        val totalLen = 36 + dataBytes
        val le = { v: Long, n: Int ->
            var x = v
            repeat(n) {
                out.write((x and 0xFF).toInt())
                x = x shr 8
            }
        }
        out.write('R'.code); out.write('I'.code); out.write('F'.code); out.write('F'.code)
        le(totalLen, 4)
        out.write('W'.code); out.write('A'.code); out.write('V'.code); out.write('E'.code)
        out.write('f'.code); out.write('m'.code); out.write('t'.code); out.write(' '.code)
        le(16L, 4)            // PCM chunk size
        le(1L, 2)             // PCM format
        le(1L, 2)             // mono
        le(SAMPLE_RATE.toLong(), 4)
        le(BYTES_PER_SECOND.toLong(), 4)
        le(2L, 2)             // block align
        le(16L, 2)            // bits per sample
        out.write('d'.code); out.write('a'.code); out.write('t'.code); out.write('a'.code)
        le(dataBytes, 4)
    }
}

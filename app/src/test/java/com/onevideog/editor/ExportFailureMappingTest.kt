package com.onevideog.editor.editor

import androidx.media3.transformer.ExportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Export error codes map to actionable, user-visible failure categories. */
class ExportFailureMappingTest {

    private fun exception(code: Int): ExportException =
        ExportException.createForAssetLoader(RuntimeException("test"), code)

    @Test
    fun `io codes map to IO_READ`() {
        assertEquals(
            ExportManager.Failure.IO_READ,
            ExportFailure.fromExportException(exception(ExportException.ERROR_CODE_IO_FILE_NOT_FOUND)),
        )
        assertEquals(
            ExportManager.Failure.IO_READ,
            ExportFailure.fromExportException(exception(ExportException.ERROR_CODE_IO_NO_PERMISSION)),
        )
    }

    @Test
    fun `encoder codes map to ENCODING`() {
        assertEquals(
            ExportManager.Failure.ENCODING,
            ExportFailure.fromExportException(exception(ExportException.ERROR_CODE_ENCODING_FAILED)),
        )
        assertEquals(
            ExportManager.Failure.ENCODING,
            ExportFailure.fromExportException(exception(ExportException.ERROR_CODE_ENCODER_INIT_FAILED)),
        )
    }

    @Test
    fun `decoder codes map to DECODING`() {
        assertEquals(
            ExportManager.Failure.DECODING,
            ExportFailure.fromExportException(exception(ExportException.ERROR_CODE_DECODING_FAILED)),
        )
    }

    @Test
    fun `muxer codes map to MUXING`() {
        assertEquals(
            ExportManager.Failure.MUXING,
            ExportFailure.fromExportException(exception(ExportException.ERROR_CODE_MUXING_FAILED)),
        )
        assertEquals(
            ExportManager.Failure.MUXING,
            ExportFailure.fromExportException(exception(ExportException.ERROR_CODE_MUXING_TIMEOUT)),
        )
    }

    @Test
    fun `processing codes map to EXPORT_ERROR`() {
        assertEquals(
            ExportManager.Failure.EXPORT_ERROR,
            ExportFailure.fromExportException(
                exception(ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED),
            ),
        )
        assertEquals(
            ExportManager.Failure.EXPORT_ERROR,
            ExportFailure.fromExportException(
                exception(ExportException.ERROR_CODE_AUDIO_PROCESSING_FAILED),
            ),
        )
    }

    @Test
    fun `unknown codes fall back to EXPORT_ERROR`() {
        assertEquals(
            ExportManager.Failure.EXPORT_ERROR,
            ExportFailure.fromExportException(exception(9999)),
        )
    }

    /**
     * v0.2.4 regression (device evidence): a synchronous `Transformer.start`
     * failure threw IllegalStateException("Transformer is accessed on the
     * wrong thread."). The mapping must categorize it as EXPORT_ERROR so the
     * ORIGINAL exception class/message is preserved in diagnostics and shown
     * to the user — never swallowed, never replaced by a generic one.
     */
    @Test
    fun `wrong-thread IllegalStateException maps to EXPORT_ERROR`() {
        val wrongThread = IllegalStateException("Transformer is accessed on the wrong thread.")
        assertEquals(
            ExportManager.Failure.EXPORT_ERROR,
            ExportFailure.fromThrowable(wrongThread),
        )
        // Nested cause chain is untouched (the caller logs the full chain).
        val nested = RuntimeException("muxer died", wrongThread)
        assertEquals(
            ExportManager.Failure.GENERIC,
            ExportFailure.fromThrowable(nested),
        )
        assertSame(wrongThread, nested.cause)
    }
}

package com.onevideog.editor.editor

import com.onevideog.editor.data.ExportResolutionEnum
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPreflightTest {

    @Test
    fun `required bytes scale with duration and include copy headroom`() {
        val fiveMin = 5 * 60 * 1000L
        val required = ExportPreflight.requiredBytes(ExportResolutionEnum.P1080, ExportConfig.Quality.HIGH, fiveMin)
        val estimate = ExportConfig.estimatedBytes(ExportResolutionEnum.P1080, ExportConfig.Quality.HIGH, fiveMin)
        assertTrue("must cover temp + SAF copy transiently", required > estimate)
        assertTrue(required > estimate * 2)
    }

    @Test
    fun `no duration limit is imposed`() {
        val oneMin = 60 * 1000L
        val fifteenMin = 15 * 60 * 1000L
        val required15 = ExportPreflight.requiredBytes(ExportResolutionEnum.P720, ExportConfig.Quality.MEDIUM, fifteenMin)
        val required1 = ExportPreflight.requiredBytes(ExportResolutionEnum.P720, ExportConfig.Quality.MEDIUM, oneMin)
        // The requirement grows with duration (purely a space check, never a
        // time cap): the 15-minute requirement must cover its own estimate.
        val estimate15 = ExportConfig.estimatedBytes(ExportResolutionEnum.P720, ExportConfig.Quality.MEDIUM, fifteenMin)
        assertTrue(required15 > estimate15 * 2)
        assertTrue(required15 > required1)
        assertTrue(required15 - required1 > ExportConfig.estimatedBytes(ExportResolutionEnum.P720, ExportConfig.Quality.MEDIUM, fourteenMinutes) * 2)
    }

    private val fourteenMinutes = 14 * 60 * 1000L

    @Test
    fun `stale export temp files are cleaned`() {
        val dir = createTempDir("exports")
        val stale1 = File(dir, "export_111.mp4").apply { writeText("x") }
        val stale2 = File(dir, "export_222.mp4").apply { writeText("x") }
        val keep = File(dir, "diagnostics.log").apply { writeText("log") }
        assertEquals(2, ExportPreflight.cleanupStaleFiles(dir))
        assertTrue(!stale1.exists() && !stale2.exists())
        assertTrue(keep.exists())
        assertEquals(0, ExportPreflight.cleanupStaleFiles(dir))
    }
}

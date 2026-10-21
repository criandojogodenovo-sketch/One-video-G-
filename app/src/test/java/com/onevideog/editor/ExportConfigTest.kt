package com.onevideog.editor

import com.onevideog.editor.data.Clip
import com.onevideog.editor.data.ExportResolutionEnum
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.ProjectAspect
import com.onevideog.editor.editor.ExportConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Export configuration: sizes, bitrates, estimates, validation, names. */
class ExportConfigTest {

    @Test
    fun `output sizes are even and correctly oriented`() {
        val landscape = ExportConfig.outputSize(ExportResolutionEnum.P1080, ProjectAspect.R16_9)
        assertEquals(1920, landscape.width)
        assertEquals(1080, landscape.height)

        val portrait = ExportConfig.outputSize(ExportResolutionEnum.P1080, ProjectAspect.R9_16)
        assertEquals(1080, portrait.width)
        assertEquals(1920, portrait.height)

        val square = ExportConfig.outputSize(ExportResolutionEnum.P720, ProjectAspect.R1_1)
        assertEquals(720, square.width)
        assertEquals(720, square.height)

        val fourThree = ExportConfig.outputSize(ExportResolutionEnum.P720, ProjectAspect.R4_3)
        assertEquals(960, fourThree.width)
        assertEquals(720, fourThree.height)
    }

    @Test
    fun `all output dimensions are even`() {
        ProjectAspect.entries.forEach { aspect ->
            ExportResolutionEnum.entries.forEach { res ->
                val size = ExportConfig.outputSize(res, aspect)
                assertEquals(0, size.width % 2)
                assertEquals(0, size.height % 2)
            }
        }
    }

    @Test
    fun `bitrate scales with quality and resolution`() {
        val low = ExportConfig.videoBitrate(ExportResolutionEnum.P720, ExportConfig.Quality.LOW)
        val med = ExportConfig.videoBitrate(ExportResolutionEnum.P720, ExportConfig.Quality.MEDIUM)
        val high = ExportConfig.videoBitrate(ExportResolutionEnum.P1080, ExportConfig.Quality.HIGH)
        assertTrue(low < med)
        assertTrue(med < high)
        assertTrue(low > 1_000_000)
    }

    @Test
    fun `size estimate grows with duration`() {
        val oneMin = ExportConfig.estimatedBytes(ExportResolutionEnum.P720, ExportConfig.Quality.MEDIUM, 60_000)
        val twoMin = ExportConfig.estimatedBytes(ExportResolutionEnum.P720, ExportConfig.Quality.MEDIUM, 120_000)
        assertTrue(oneMin > 0)
        assertTrue(twoMin > oneMin * 2 * 0.95)
    }

    @Test
    fun `file names are sanitized`() {
        assertEquals("my video", ExportConfig.sanitizeFileName("my:video/<>?"))
        assertEquals("a b", ExportConfig.sanitizeFileName("a    b"))
        assertEquals("one-video-g", ExportConfig.sanitizeFileName("   "))
        assertTrue(ExportConfig.sanitizeFileName("x".repeat(200)).length <= 60)
        assertFalse(ExportConfig.sanitizeFileName("a/b\\c").contains("/"))
    }

    @Test
    fun `validation rejects tiny clips and accepts video-less projects (v0_5_1)`() {
        val empty = OvgProject(
            id = "p",
            name = "p",
            createdAtEpochMs = 0,
            updatedAtEpochMs = 0,
        )
        // v0.5.1: a project without base video is EXPORTABLE — the
        // composition renders a generated black background for the 5 s
        // floor duration. The old NO_CLIPS gate made "Projeto vazio"
        // impossible to export.
        assertEquals(null, ExportConfig.validateForExport(empty))

        val tiny = empty.copy(
            clips = listOf(
                Clip(
                    id = "c",
                    uri = "u",
                    displayName = "d",
                    sourceDurationMs = 1000,
                    trimStartMs = 0,
                    trimEndMs = 50,
                ),
            ),
        )
        assertEquals(
            ExportConfig.ExportProblem.CLIP_TOO_SHORT,
            ExportConfig.validateForExport(tiny),
        )

        val valid = tiny.copy(
            clips = listOf(
                Clip(
                    id = "c",
                    uri = "u",
                    displayName = "d",
                    sourceDurationMs = 1000,
                    trimStartMs = 0,
                    trimEndMs = 1000,
                ),
            ),
        )
        assertNotNull(valid)
        assertEquals(null, ExportConfig.validateForExport(valid))
    }
}

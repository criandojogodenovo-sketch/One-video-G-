package com.onevideog.editor

import com.onevideog.editor.data.Clip
import com.onevideog.editor.data.ImageOverlayItem
import com.onevideog.editor.data.MusicTrack
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.ProjectAspect
import com.onevideog.editor.data.TextOverlayItem
import com.onevideog.editor.data.projectJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Project JSON serialization and recovery behaviour. */
class ProjectJsonTest {

    private val project = OvgProject(
        id = "p1",
        name = "Férias 2026",
        createdAtEpochMs = 1_000,
        updatedAtEpochMs = 2_000,
        aspect = ProjectAspect.R9_16.name,
        clips = listOf(
            Clip(
                id = "c1",
                uri = "content://docs/video1.mp4",
                displayName = "video1.mp4",
                sourceDurationMs = 30_000,
                width = 1920,
                height = 1080,
                trimStartMs = 2_500,
                trimEndMs = 28_000,
                volume = 1.4f,
                speed = 1.5f,
                color = com.onevideog.editor.data.ColorAdjustments(0.1f, 1.2f, 0.8f),
            ),
        ),
        textOverlays = listOf(
            TextOverlayItem(id = "t1", text = "Olá", colorArgb = 0xFFF98A0B.toInt()),
        ),
        imageOverlays = listOf(
            ImageOverlayItem(id = "i1", uri = "content://docs/img.png", scale = 0.5f),
        ),
        music = MusicTrack(
            uri = "content://docs/song.mp3",
            displayName = "song.mp3",
            durationMs = 120_000,
            volume = 0.5f,
        ),
    )

    @Test
    fun `roundtrip preserves all fields`() {
        val json = projectJson.encodeToString(project)
        val restored = projectJson.decodeFromString<OvgProject>(json)
        assertEquals(project, restored)
    }

    @Test
    fun `unknown fields from future versions are ignored`() {
        val json = projectJson.encodeToString(project)
            .replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"futureField\":42")
        val restored = projectJson.decodeFromString<OvgProject>(json)
        assertEquals(project.id, restored.id)
    }

    @Test
    fun `defaults apply when fields are missing`() {
        val minimal = """
            {"id":"x","name":"n","createdAtEpochMs":1,"updatedAtEpochMs":2}
        """.trimIndent()
        val restored = projectJson.decodeFromString<OvgProject>(minimal)
        assertEquals(ProjectAspect.SOURCE, restored.aspectEnum)
        assertTrue(restored.clips.isEmpty())
        assertEquals(1f, restored.clips.firstOrNull()?.speed ?: 1f)
    }

    @Test
    fun `malformed json fails cleanly for recovery path`() {
        val result = runCatching {
            projectJson.decodeFromString<OvgProject>("{ not json")
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun `clip list serialization is stable`() {
        val json = projectJson.encodeToString(ListSerializer(Clip.serializer()), project.clips)
        val restored = projectJson.decodeFromString(ListSerializer(Clip.serializer()), json)
        assertEquals(project.clips, restored)
    }
}

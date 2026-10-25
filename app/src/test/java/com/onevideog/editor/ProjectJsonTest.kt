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

    @Test
    fun `v0_3_0 layer state visible-locked round-trips and defaults keep old projects loadable`() {
        // New fields persist.
        val tagged = project.copy(
            overlays = listOf(
                com.onevideog.editor.data.OverlayItem(
                    id = "o1", kind = com.onevideog.editor.data.OverlayKind.TEXT,
                    startMs = 0, endMs = 5_000, text = "x",
                    visible = false, locked = true,
                ),
            ),
        )
        val restored = projectJson
            .decodeFromString<OvgProject>(projectJson.encodeToString(tagged))
        assertEquals(false, restored.overlays[0].visible)
        assertEquals(true, restored.overlays[0].locked)
        // Documents saved by <= v0.2.5 (no visible/locked fields) load with
        // the defaults — existing projects are preserved unchanged.
        val legacyJson = """
            {"id":"x","name":"n","createdAtEpochMs":1,"updatedAtEpochMs":2,
             "overlays":[{"id":"o","kind":"TEXT","startMs":0,"endMs":1000,"text":"hi"}]}
        """.trimIndent()
        val legacy = projectJson.decodeFromString<OvgProject>(legacyJson)
        assertEquals(true, legacy.overlays[0].visible)
        assertEquals(false, legacy.overlays[0].locked)
    }

    @Test
    fun `v0_5_0 TextAnimation round-trips (G1)`() {
        val animated = project.copy(
            overlays = listOf(
                com.onevideog.editor.data.OverlayItem(
                    id = "a1", kind = com.onevideog.editor.data.OverlayKind.TEXT,
                    startMs = 0, endMs = 5_000, text = "Olá mundo",
                    textAnimation = com.onevideog.editor.data.TextAnimation(
                        preset = com.onevideog.editor.data.TextAnimPreset.POP,
                        unit = com.onevideog.editor.data.TextAnimUnit.LETTER,
                        durationMs = 450,
                    ),
                ),
            ),
        )
        val restored = projectJson
            .decodeFromString<OvgProject>(projectJson.encodeToString(animated))
        val anim = restored.overlays[0].textAnimation
        assertEquals(com.onevideog.editor.data.TextAnimPreset.POP, anim.preset)
        assertEquals(com.onevideog.editor.data.TextAnimUnit.LETTER, anim.unit)
        assertEquals(450L, anim.durationMs)
        assertTrue(anim.isEnabled)
    }

    @Test
    fun `documents saved before v0_5_0 (no textAnimation field) load with NONE`() {
        // The exact JSON a v0.4.0 app writes for a text overlay: no
        // textAnimation key at all — it must decode to the NONE default and
        // render exactly as before.
        val v040Json = """
            {"id":"x","name":"n","createdAtEpochMs":1,"updatedAtEpochMs":2,
             "overlays":[{"id":"o","kind":"TEXT","startMs":0,"endMs":1000,
                          "text":"hi","visible":true,"locked":false}]}
        """.trimIndent()
        val legacy = projectJson.decodeFromString<OvgProject>(v040Json)
        assertEquals(
            com.onevideog.editor.data.TextAnimPreset.NONE,
            legacy.overlays[0].textAnimation.preset,
        )
        assertEquals(false, legacy.overlays[0].textAnimation.isEnabled)
        assertEquals(
            com.onevideog.editor.data.TextAnimUnit.WORD,
            legacy.overlays[0].textAnimation.unit,
        )
        assertEquals(600L, legacy.overlays[0].textAnimation.durationMs)
    }

    // ------------------------------------------------ v0.9.0 (schema v6, L)

    @Test
    fun `v0_9_0 style zDepth and camera round-trip`() {
        val tagged = project.copy(
            schemaVersion = com.onevideog.editor.data.OvgProject.SCHEMA_VERSION,
            camera = com.onevideog.editor.data.CameraState(
                panX = 0.1f, panY = -0.05f, zoom = 1.8f,
                keyframes = listOf(
                    com.onevideog.editor.data.CameraKeyframe(0, 0f, 0f, 1f),
                    com.onevideog.editor.data.CameraKeyframe(2_000, 0.2f, -0.1f, 2.4f),
                ),
                interpolation = com.onevideog.editor.data.Interpolation.EASE_IN_OUT,
            ),
            overlays = listOf(
                com.onevideog.editor.data.OverlayItem(
                    id = "s1", kind = com.onevideog.editor.data.OverlayKind.TEXT,
                    startMs = 0, endMs = 5_000, text = "estilo",
                    zDepth = -0.5f,
                    style = com.onevideog.editor.data.OverlayStyle(
                        shadow = com.onevideog.editor.data.StyleShadow(
                            offsetX = 0.008f, offsetY = 0.012f, blur = 0.02f,
                            colorArgb = 0xFF112233.toInt(), opacity = 0.7f,
                        ),
                        glow = com.onevideog.editor.data.StyleGlow(
                            radius = 0.03f, colorArgb = 0xFF00E5FF.toInt(), strength = 0.8f,
                        ),
                    ),
                ),
            ),
        )
        val restored = projectJson
            .decodeFromString<OvgProject>(projectJson.encodeToString(tagged))
        assertEquals(tagged, restored)
        assertEquals(-0.5f, restored.overlays[0].zDepth)
        assertEquals(
            0xFF112233.toInt(),
            restored.overlays[0].style?.shadow?.colorArgb,
        )
        assertEquals(1.8f, restored.camera.zoom)
        assertEquals(2, restored.camera.keyframes.size)
    }

    @Test
    fun `documents saved by v0_8_x (schema v5, no v6 fields) decode to no-style depth-0 identity`() {
        // The exact shape a v0.8.1 app writes: no style/zDepth/camera keys.
        val v5Json = """
            {"schemaVersion":5,"id":"x","name":"n","createdAtEpochMs":1,"updatedAtEpochMs":2,
             "overlays":[{"id":"o","kind":"TEXT","startMs":0,"endMs":1000,"text":"hi"}]}
        """.trimIndent()
        val legacy = projectJson.decodeFromString<OvgProject>(v5Json)
        assertEquals(null, legacy.overlays[0].style)
        assertEquals(0f, legacy.overlays[0].zDepth)
        assertEquals(com.onevideog.editor.data.CameraState(), legacy.camera)
        // And the identity camera IS the identity for the pipeline.
        assertTrue(com.onevideog.editor.editor.CameraMath.isIdentity(legacy.camera))
    }

    @Test
    fun `schema version field is informative and v6 writes round-trip through the v6 model`() {
        val v6 = project.copy(
            schemaVersion = com.onevideog.editor.data.OvgProject.SCHEMA_VERSION,
            overlays = listOf(
                com.onevideog.editor.data.OverlayItem(
                    id = "o", kind = com.onevideog.editor.data.OverlayKind.TEXT,
                    startMs = 0, endMs = 1_000, text = "x",
                    style = com.onevideog.editor.data.OverlayStylePresets.NEON,
                ),
            ),
        )
        val json = projectJson.encodeToString(v6)
        val restored = projectJson.decodeFromString<OvgProject>(json)
        assertEquals(v6, restored)
        // Byte-stability: encoding the restored document produces the same JSON.
        assertEquals(json, projectJson.encodeToString(restored))
    }
}

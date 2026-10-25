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

    // ----------------------------------------------------------------------
    // v0.9.0 (Fase L, L5): schema v6 — OverlayStyle / zDepth / CameraState.
    // ----------------------------------------------------------------------

    @Test
    fun `v0_9_0 style zDepth and camera round-trip (v6)`() {
        val v6 = project.copy(
            schemaVersion = 6,
            overlays = listOf(
                com.onevideog.editor.data.OverlayItem(
                    id = "s1", kind = com.onevideog.editor.data.OverlayKind.TEXT,
                    startMs = 0, endMs = 5_000, text = "neon",
                    style = com.onevideog.editor.data.OverlayStyle(
                        shadow = com.onevideog.editor.data.OverlayShadowStyle(
                            offsetX = 0.01f, offsetY = 0.02f, blur = 0.015f,
                            colorArgb = 0xFF112233.toInt(), opacity = 0.7f,
                        ),
                        glow = com.onevideog.editor.data.OverlayGlowStyle(
                            radius = 0.02f, colorArgb = 0xFF00E5FF.toInt(), strength = 0.85f,
                        ),
                    ),
                    zDepth = -0.5f,
                ),
                com.onevideog.editor.data.OverlayItem(
                    id = "s2", kind = com.onevideog.editor.data.OverlayKind.IMAGE,
                    startMs = 0, endMs = 5_000, uri = "content://img",
                    zDepth = 0.75f,
                ),
            ),
            camera = com.onevideog.editor.data.CameraState(
                panX = 0.25f, panY = -0.5f, zoom = 1.75f,
                keyframes = listOf(
                    com.onevideog.editor.data.CameraKeyframe(
                        timeMs = 250, panX = -0.3f, panY = 0.4f, zoom = 1.2f,
                    ),
                    com.onevideog.editor.data.CameraKeyframe(timeMs = 1_750, zoom = 2.5f),
                ),
                interpolation = com.onevideog.editor.data.Interpolation.EASE_IN_OUT,
            ),
        )
        val restored = projectJson.decodeFromString<OvgProject>(projectJson.encodeToString(v6))
        assertEquals(v6, restored)
        assertEquals(6, restored.schemaVersion)
        val styled = restored.overlays[0]
        assertTrue(styled.hasStyle)
        assertEquals(0.01f, styled.style!!.shadow!!.offsetX, 1e-6f)
        assertEquals(0xFF112233.toInt(), styled.style!!.shadow!!.colorArgb)
        assertEquals(0.85f, styled.style!!.glow!!.strength, 1e-6f)
        assertEquals(-0.5f, styled.zDepth, 1e-6f)
        val cam = restored.camera
        assertEquals(0.25f, cam.panX, 1e-6f)
        assertEquals(2, cam.keyframes.size)
        assertEquals(-0.3f, cam.keyframes[0].panX!!, 1e-6f)
        assertEquals(null, cam.keyframes[1].panX)
    }

    @Test
    fun `v5 documents (schema v5, no style zDepth camera) decode byte-compatible`() {
        // The exact JSON a v0.8.1 app writes: schemaVersion 5, no style /
        // zDepth / camera keys anywhere. Every v6 field decodes to its "off"
        // default and the overlay renders EXACTLY the v0.8.1 behaviour
        // (identity camera, video-plane depth, no bake).
        val v5Json = """
            {"schemaVersion":5,"id":"x","name":"n","createdAtEpochMs":1,"updatedAtEpochMs":2,
             "overlays":[{"id":"o","kind":"TEXT","startMs":0,"endMs":1000,"text":"v5"}]}
        """.trimIndent()
        val legacy = projectJson.decodeFromString<OvgProject>(v5Json)
        assertEquals(5, legacy.schemaVersion)
        val overlay = legacy.overlays[0]
        assertEquals(null, overlay.style)
        assertEquals(false, overlay.hasStyle)
        assertEquals(0f, overlay.zDepth, 1e-6f)
        assertEquals(com.onevideog.editor.data.CameraState(), legacy.camera)
        assertTrue(legacy.camera.isDefault)
        // Re-saving keeps the document stable (no migration pass needed).
        val resaved = projectJson.decodeFromString<OvgProject>(projectJson.encodeToString(legacy))
        assertEquals(legacy, resaved)
    }

    @Test
    fun `default style and camera keep the hardened paths at identity`() {
        // A default-styled overlay at depth 0 under a default camera is the
        // EXACT v0.8.1 model — the identity CameraMath affine (locked here
        // so a schema accident can never re-introduce drift).
        val overlay = com.onevideog.editor.data.OverlayItem(
            id = "p", kind = com.onevideog.editor.data.OverlayKind.TEXT,
        )
        assertTrue(com.onevideog.editor.editor.CameraMath.affineForDepth(
            com.onevideog.editor.editor.CameraMath.evaluate(
                com.onevideog.editor.data.CameraState(), 0L,
            ),
            overlay.zDepth,
        ) == com.onevideog.editor.editor.CameraMath.DepthAffine.IDENTITY)
    }
}

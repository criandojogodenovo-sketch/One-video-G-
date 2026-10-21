package com.onevideog.editor.editor

import com.onevideog.editor.data.MusicTrack
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.TextOverlayItem
import com.onevideog.editor.data.projectJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Schema v1 -> v2 migration keeps old projects fully intact. */
class ProjectMigrationTest {

    @Test
    fun `v1 json with legacy fields migrates to unified model`() {
        val v1Json = """
            {
              "schemaVersion": 1,
              "id": "p1",
              "name": "Legacy",
              "createdAtEpochMs": 1,
              "updatedAtEpochMs": 2,
              "aspect": "R9_16",
              "clips": [],
              "textOverlays": [
                {"id":"t1","text":"Olá","colorArgb":-1,"position":"BOTTOM","startMs":0,"endMs":5000}
              ],
              "imageOverlays": [
                {"id":"i1","uri":"content://img","position":"CENTER","scale":0.5,"startMs":1000,"endMs":4000}
              ],
              "music": {"uri":"content://mp3","displayName":"song.mp3","durationMs":30000,"volume":0.5}
            }
        """.trimIndent()

        val loaded = projectJson.decodeFromString<OvgProject>(v1Json)
        val migrated = loaded.migrated()

        // Text overlay -> unified overlay with transformed position.
        val text = migrated.overlays.first { it.isText }
        assertEquals("Olá", text.text)
        assertEquals(0.5f, text.transform.x, 1e-4f)
        assertEquals(0.84f, text.transform.y, 1e-4f)
        assertEquals(0, text.startMs)
        assertEquals(5_000L, text.endMs)

        // Image overlay keeps scale as width fraction.
        val image = migrated.overlays.first { !it.isText }
        assertEquals("content://img", image.uri)
        assertEquals(0.5f, image.transform.scale, 1e-4f)
        assertEquals(0.5f, image.transform.y, 1e-4f)

        // Music becomes an independent looping audio clip.
        val audio = migrated.audioClips.single()
        assertEquals("content://mp3", audio.uri)
        assertEquals(0.5f, audio.volume, 1e-4f)
        assertTrue(audio.loop)
        assertEquals(0L, audio.startMs)
        assertEquals(30_000L, audio.trimEndMs)

        // Legacy fields are cleared after migration (idempotent).
        assertTrue(migrated.textOverlays.isEmpty())
        assertTrue(migrated.imageOverlays.isEmpty())
        assertNull(migrated.music)
        assertEquals(migrated, migrated.migrated())
    }

    @Test
    fun `v2 project round-trips through json`() {
        val project = OvgProject(
            id = "p2", name = "Modern", createdAtEpochMs = 3, updatedAtEpochMs = 4,
        ).copy(
            audioClips = listOf(
                com.onevideog.editor.data.AudioClipItem(
                    id = "a1", uri = "content://a", displayName = "a.mp3",
                    sourceDurationMs = 10_000, startMs = 1_500,
                    trimStartMs = 200, trimEndMs = 9_000, volume = 1.4f, loop = false,
                ),
            ),
        )
        val json = projectJson.encodeToString(project)
        val decoded = projectJson.decodeFromString<OvgProject>(json)
        assertEquals(project, decoded)
        assertEquals(project, decoded.migrated())
    }

    @Test
    fun `unknown future fields are ignored`() {
        val json = """
            {"id":"p3","name":"F","createdAtEpochMs":0,"updatedAtEpochMs":9,"someFutureField":true}
        """.trimIndent()
        val decoded = projectJson.decodeFromString<OvgProject>(json)
        assertEquals("p3", decoded.id)
    }

    @Test
    fun `migrated music audio gets a fresh UUID id, not a derived one (v0_5_1)`() {
        val v1Json = """
            {
              "schemaVersion": 1,
              "id": "p4",
              "name": "Music Legacy",
              "createdAtEpochMs": 1,
              "updatedAtEpochMs": 2,
              "music": {"uri":"content://mp3","displayName":"song.mp3","durationMs":30000,"volume":0.5}
            }
        """.trimIndent()

        val migrated = projectJson.decodeFromString<OvgProject>(v1Json).migrated()
        val audio = migrated.audioClips.single()

        // v0.5.1: the id is a fresh UUID — NOT the legacy derived
        // "music_<uri-hash>" identity (derived/fixed ids were the bug class
        // this release removes: different documents could carry the same
        // clip id, and any new import could collide with it).
        assertEquals(audio.id, java.util.UUID.fromString(audio.id).toString())
        assertFalse(audio.id.startsWith("music_"))

        // Migration stays idempotent: the legacy field was cleared in the
        // same pass, so the UUID is minted at most once per document.
        assertNull(migrated.music)
        assertEquals(migrated, migrated.migrated())
    }
}

package com.onevideog.editor.editor

import com.onevideog.editor.data.AudioClipItem
import com.onevideog.editor.data.Clip
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.5.1 — the unified clip view ("estrutura única de clipes com tipo").
 * EVERY timeline element of any kind is visible as a [TimelineClip] with
 * unique ids, so selection / creation / row rules are written ONCE. These
 * tests pin the windows: video clips expand to their concatenated timeline
 * windows (the ONLY kind whose windows are derived), audio and overlays keep
 * their absolute windows — the same math preview, export and the timeline UI
 * read through [TimelineLogic] / [ProjectMath].
 */
class TimelineClipsTest {

    private fun video(id: String, durMs: Long, speed: Float = 1f) = Clip(
        id = id, uri = "u$id", displayName = "v$id", sourceDurationMs = durMs,
        trimStartMs = 0, trimEndMs = durMs, speed = speed,
    )

    private fun audio(id: String, start: Long, trimStart: Long, trimEnd: Long) =
        AudioClipItem(
            id = id, uri = "u$id", displayName = "a$id", sourceDurationMs = 60_000,
            startMs = start, trimStartMs = trimStart, trimEndMs = trimEnd,
        )

    private fun overlay(id: String, kind: OverlayKind, start: Long, end: Long) =
        OverlayItem(
            id = id, kind = kind, startMs = start, endMs = end,
            text = if (kind == OverlayKind.TEXT) "Olá" else "",
            uri = if (kind == OverlayKind.IMAGE) "content://img" else "",
        )

    private fun project(
        clips: List<Clip> = emptyList(),
        audio: List<AudioClipItem> = emptyList(),
        overlays: List<OverlayItem> = emptyList(),
    ) = OvgProject(id = "p", name = "n", createdAtEpochMs = 0, updatedAtEpochMs = 0)
        .copy(clips = clips, audioClips = audio, overlays = overlays)

    // ------------------------------------------------------------- windows

    @Test
    fun `video windows concatenate, audio and overlays keep absolute windows`() {
        val p = project(
            clips = listOf(video("v1", 2_000), video("v2", 3_000)),
            audio = listOf(audio("a1", 1_500, 0, 4_000)),
            overlays = listOf(overlay("t1", OverlayKind.TEXT, 500, 2_500)),
        )
        val all = TimelineClips.all(p)

        assertEquals(4, all.size)
        // Video: derived, back-to-back from 0.
        assertEquals("v1", all[0].id)
        assertEquals(0L, all[0].startMs)
        assertEquals(2_000L, all[0].endMs)
        assertEquals("v2", all[1].id)
        assertEquals(2_000L, all[1].startMs)
        assertEquals(5_000L, all[1].endMs)
        // Audio: absolute project window, untouched.
        val a = all.first { it.id == "a1" }
        assertEquals(1_500L, a.startMs)
        assertEquals(5_500L, a.endMs)
        // Overlay: absolute project window, untouched.
        val t = all.first { it.id == "t1" }
        assertEquals(500L, t.startMs)
        assertEquals(2_500L, t.endMs)
    }

    @Test
    fun `kinds and labels map per type`() {
        val p = project(
            clips = listOf(video("v1", 1_000)),
            audio = listOf(audio("a1", 0, 0, 2_000)),
            overlays = listOf(
                overlay("t1", OverlayKind.TEXT, 0, 2_000),
                overlay("i1", OverlayKind.IMAGE, 0, 2_000),
            ),
        )
        val byId = TimelineClips.all(p).associateBy { it.id }
        assertEquals(ClipKind.VIDEO, byId.getValue("v1").kind)
        assertEquals("vv1", byId.getValue("v1").label) // fixture displayName "v$id"
        assertEquals(ClipKind.AUDIO, byId.getValue("a1").kind)
        assertEquals("aa1", byId.getValue("a1").label)
        assertEquals(ClipKind.TEXT, byId.getValue("t1").kind)
        assertEquals("Olá", byId.getValue("t1").label)
        assertEquals(ClipKind.IMAGE, byId.getValue("i1").kind)
        assertEquals("content://img", byId.getValue("i1").label)
    }

    @Test
    fun `speed shrinks the derived video window but not absolute windows`() {
        val p = project(
            clips = listOf(video("v1", 4_000, speed = 2f)),
            overlays = listOf(overlay("t1", OverlayKind.TEXT, 0, 4_000)),
        )
        val byId = TimelineClips.all(p).associateBy { it.id }
        assertEquals(2_000L, byId.getValue("v1").durationMs)
        assertEquals(4_000L, byId.getValue("t1").durationMs)
    }

    @Test
    fun `empty project yields no timeline clips`() {
        assertTrue(TimelineClips.all(project()).isEmpty())
    }

    // ---------------------------------------------------------------- find

    @Test
    fun `find locates any kind by unique id`() {
        val p = project(
            clips = listOf(video("v1", 1_000)),
            audio = listOf(audio("a1", 0, 0, 2_000)),
            overlays = listOf(overlay("t1", OverlayKind.TEXT, 0, 2_000)),
        )
        assertEquals(ClipKind.VIDEO, TimelineClips.find(p, "v1")?.kind)
        assertEquals(ClipKind.AUDIO, TimelineClips.find(p, "a1")?.kind)
        assertEquals(ClipKind.TEXT, TimelineClips.find(p, "t1")?.kind)
        assertEquals(null, TimelineClips.find(p, "missing"))
    }

    // ---------------------------------------------------------- isActiveAt

    @Test
    fun `active at is start inclusive and end exclusive`() {
        val c = TimelineClip(id = "x", kind = ClipKind.TEXT, startMs = 1_000, endMs = 2_000, label = "x")
        with(TimelineClips) {
            assertFalse(c.isActiveAt(999))
            assertTrue(c.isActiveAt(1_000))
            assertTrue(c.isActiveAt(1_999))
            assertFalse(c.isActiveAt(2_000))
        }
    }

    // ---------------------------------------------------- wouldShareLaneWith

    @Test
    fun `lane sharing requires real overlap`() {
        val existing = listOf(
            TimelineClip("a", ClipKind.TEXT, 0, 2_000, "a"),
        )
        // Touching edges (end == start) do NOT collide.
        assertFalse(TimelineClips.wouldShareLaneWith(existing, 2_000, 4_000))
        assertTrue(TimelineClips.wouldShareLaneWith(existing, 1_999, 3_000))
        assertTrue(TimelineClips.wouldShareLaneWith(existing, 0, 2_000))
        assertFalse(TimelineClips.wouldShareLaneWith(existing, 2_001, 3_000))
    }

    // --------------------------------------------- selection wiring (sanity)

    @Test
    fun `clip ids stay unique across kinds in the unified view`() {
        // The unified view indexes EVERYTHING by id (selection, layers,
        // find). A duplicate id of different kinds would silently route
        // selection/gestures to the wrong element — the id generator mints
        // UUIDs for all kinds, so this can only happen through a fixed-id
        // bug; the guard here keeps the contract visible.
        val p = project(
            clips = listOf(video("v1", 1_000)),
            audio = listOf(audio("a1", 0, 0, 2_000)),
            overlays = listOf(
                overlay("t1", OverlayKind.TEXT, 0, 2_000),
                overlay("i1", OverlayKind.IMAGE, 0, 2_000),
            ),
        )
        val ids = TimelineClips.all(p).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}

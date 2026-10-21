package com.onevideog.editor

import com.onevideog.editor.data.Keyframe
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind
import com.onevideog.editor.data.OverlayTransform
import com.onevideog.editor.data.TextAnimPreset
import com.onevideog.editor.data.TextAnimation
import com.onevideog.editor.editor.ExpandedOverlays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.5.5 regression tests for [ExpandedOverlays.build] — the render list BOTH
 * pipelines consume (preview placements + export overlay windows).
 *
 * Regression being locked in (docs/AUDIT_v0.5.4_report_followup.md §1):
 * v0.5.0-v0.5.4 dropped every overlay that needs no expansion (all IMAGE
 * overlays, every TEXT overlay without an entrance animation) from that list —
 * the preview never drew them (no placement, no hit-test, no gizmos) and the
 * export composition built zero overlay textures for them. The passthrough
 * paths below are pure (they return before any android.graphics.Paint use),
 * so they run on plain JVM.
 */
class ExpandedOverlaysTest {

    private fun project(vararg overlays: OverlayItem) = OvgProject(
        id = "p1",
        name = "audit",
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
        overlays = overlays.toList(),
    )

    private fun imageOverlay(
        id: String,
        visible: Boolean = true,
        startMs: Long = 0,
        endMs: Long = 5_000,
    ) = OverlayItem(
        id = id,
        kind = OverlayKind.IMAGE,
        startMs = startMs,
        endMs = endMs,
        transform = OverlayTransform(x = 0.5f, y = 0.5f, scale = 0.4f),
        uri = "file:///overlay_media/img_$id.jpg",
        visible = visible,
    )

    private fun textOverlay(
        id: String,
        visible: Boolean = true,
        animation: TextAnimation = TextAnimation(),
        keyframes: List<Keyframe> = emptyList(),
    ) = OverlayItem(
        id = id,
        kind = OverlayKind.TEXT,
        startMs = 0,
        endMs = 5_000,
        transform = OverlayTransform(x = 0.5f, y = 0.8f, scale = 0.05f),
        text = "abc",
        textAnimation = animation,
        keyframes = keyframes,
        visible = visible,
    )

    // ------------------------------------------------------------ passthrough

    @Test
    fun `image overlay passes through unchanged - same id and fields`() {
        val overlay = imageOverlay("img-1")
        val result = ExpandedOverlays.build(project(overlay))

        assertEquals(1, result.overlays.size)
        val passed = result.overlays.single()
        assertEquals("img-1", passed.id)
        assertEquals(OverlayKind.IMAGE, passed.kind)
        assertEquals(overlay, passed)
        assertFalse(ExpandedOverlays.isSegmentId(passed.id))
    }

    @Test
    fun `text overlay without animation passes through unchanged`() {
        val overlay = textOverlay("txt-1") // default TextAnimation() = preset NONE
        val result = ExpandedOverlays.build(project(overlay))

        assertEquals(1, result.overlays.size)
        assertEquals(overlay, result.overlays.single())
        assertFalse(ExpandedOverlays.isSegmentId(result.overlays.single().id))
    }

    @Test
    fun `mixed image and plain text keep project order and identity`() {
        val image = imageOverlay("img-1")
        val text = textOverlay("txt-1")
        val result = ExpandedOverlays.build(project(image, text))

        assertEquals(listOf("img-1", "txt-1"), result.overlays.map { it.id })
        assertEquals(image, result.overlays[0])
        assertEquals(text, result.overlays[1])
        // Passthrough items map to themselves in the selection map — harmless
        // and consistent with the segment contract.
        assertEquals("img-1", result.parentOfSegment["img-1"])
        assertEquals("txt-1", result.parentOfSegment["txt-1"])
        assertTrue(result.limited.isEmpty())
    }

    // ---------------------------------------------------------------- hidden

    @Test
    fun `hidden image overlay renders nothing in both pipelines`() {
        val result = ExpandedOverlays.build(
            project(imageOverlay("img-hidden", visible = false), imageOverlay("img-ok")),
        )

        assertEquals(listOf("img-ok"), result.overlays.map { it.id })
    }

    @Test
    fun `hidden text overlay renders nothing`() {
        val result = ExpandedOverlays.build(
            project(textOverlay("txt-hidden", visible = false)),
        )

        assertTrue(result.overlays.isEmpty())
    }

    // ------------------------------------------------------------- contracts

    @Test
    fun `segment id helpers keep their contract for passthrough ids`() {
        // UUIDs never contain the segment separator, so a passthrough overlay
        // can never be mistaken for a generated segment.
        val id = "2f0f6e34-9f5a-4a2f-9d0a-1b2c3d4e5f60"
        assertFalse(ExpandedOverlays.isSegmentId(id))
        assertEquals(id, ExpandedOverlays.parentIdOf(id))
        assertEquals("parent#s0", ExpandedOverlays.segmentId("parent", 0))
        assertTrue(ExpandedOverlays.isSegmentId(ExpandedOverlays.segmentId("parent", 0)))
        assertEquals("parent", ExpandedOverlays.parentIdOf("parent#s0"))
    }

    @Test
    fun `passthrough keeps keyframes on the item - evaluator contract untouched`() {
        // A text overlay with authored keyframes but no entrance animation must
        // keep its keyframes exactly: computePlacements/KeyframeEvaluator read
        // them from the SAME item the export windows do.
        val animated = textOverlay(
            "txt-keyframed",
            keyframes = listOf(Keyframe(timeMs = 1_000, opacity = 0.5f)),
        )
        val result = ExpandedOverlays.build(project(animated))

        assertEquals(1, result.overlays.size)
        assertEquals(animated.keyframes, result.overlays.single().keyframes)
    }
}

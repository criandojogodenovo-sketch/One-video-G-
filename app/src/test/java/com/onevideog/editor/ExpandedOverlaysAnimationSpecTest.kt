package com.onevideog.editor

import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind
import com.onevideog.editor.data.OverlayTransform
import com.onevideog.editor.data.TextAnimation
import com.onevideog.editor.data.TextAnimPreset
import com.onevideog.editor.editor.ExpandedOverlays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v0.5.7: the [ExpandedOverlays.animationSpec] gates that decide whether an
 * overlay becomes the ONE [com.onevideog.editor.editor.AnimatedTextOverlay]
 * composite texture (export) or stays on the per-overlay [KeyframedOverlay]
 * path. Pure gates only — everything past [OverlayBitmapRenderer.textMetrics]
 * needs android.graphics.Paint and runs on-device (same split the
 * [ExpandedOverlaysTest] pass-through tests use).
 */
class ExpandedOverlaysAnimationSpecTest {

    private fun imageOverlay() = OverlayItem(
        id = "img-1",
        kind = OverlayKind.IMAGE,
        startMs = 0,
        endMs = 5_000,
        transform = OverlayTransform(x = 0.5f, y = 0.5f, scale = 0.4f),
        uri = "file:///overlay_media/img_1.jpg",
    )

    private fun textOverlay(animation: TextAnimation) = OverlayItem(
        id = "txt-1",
        kind = OverlayKind.TEXT,
        startMs = 0,
        endMs = 5_000,
        transform = OverlayTransform(x = 0.5f, y = 0.8f, scale = 0.05f),
        text = "abc",
        textAnimation = animation,
    )

    @Test
    fun `image overlay never segments - stays on the KeyframedOverlay path`() {
        assertNull(ExpandedOverlays.animationSpec(imageOverlay(), contentAspect = 16f / 9f))
    }

    @Test
    fun `text overlay without entrance animation never segments`() {
        // Default TextAnimation() = preset NONE (disabled).
        assertNull(ExpandedOverlays.animationSpec(textOverlay(TextAnimation()), contentAspect = 16f / 9f))
    }

    @Test
    fun `build keeps v0_5_5 passthrough for the overlays the composite path rejects`() {
        // Contract lock: an IMAGE overlay and a plain TEXT overlay keep their
        // single-item identity in the preview expansion — the same overlays
        // animationSpec rejects above.
        val result = ExpandedOverlays.build(
            OvgProject(
                id = "p1",
                name = "spec",
                createdAtEpochMs = 0L,
                updatedAtEpochMs = 0L,
                overlays = listOf(imageOverlay(), textOverlay(TextAnimation())),
            ),
        )
        assertEquals(listOf("img-1", "txt-1"), result.overlays.map { it.id })
        assertEquals(0, result.overlays.count { it.id.contains("#s") })
    }
}

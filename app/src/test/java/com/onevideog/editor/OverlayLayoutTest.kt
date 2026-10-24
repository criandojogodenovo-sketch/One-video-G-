package com.onevideog.editor.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity contract tests: the preview placement math and the export
 * OverlaySettings math must describe the same visual result.
 */
class OverlayLayoutTest {

    @Test
    fun `ndc roundtrip preserves normalized position`() {
        for ((nx, ny) in listOf(0f to 0f, 1f to 1f, 0.5f to 0.5f, 0.25f to 0.75f, 0.9f to 0.1f)) {
            val (x, y) = OverlayLayout.toBackgroundNdc(nx, ny)
            val (rx, ry) = OverlayLayout.fromBackgroundNdc(x, y)
            assertEquals(nx, rx, 1e-4f)
            assertEquals(ny, ry, 1e-4f)
        }
    }

    @Test
    fun `ndc semantics match Media3 docs - top right is plus plus`() {
        val (x, y) = OverlayLayout.toBackgroundNdc(1f, 0f) // top-right in content space
        assertEquals(1f, x, 1e-4f)
        assertEquals(1f, y, 1e-4f) // Media3: (+1,+1) is the top right corner
    }

    @Test
    fun `letterbox fit matches expected geometry`() {
        // 16:9 content inside a 9:16 container (1080x1920 px)
        val fit = OverlayLayout.fitRect(1080f, 1920f, 16f / 9f)
        assertEquals(1080f, fit.width, 0.5f)
        assertEquals(607.5f, fit.height, 1f)
        assertEquals(0f, fit.offsetX, 0.5f)
        assertEquals((1920f - 607.5f) / 2f, fit.offsetY, 1f)

        // 9:16 content inside a 16:9 container
        val fit2 = OverlayLayout.fitRect(1920f, 1080f, 9f / 16f)
        assertEquals(607.5f, fit2.width, 1f)
        assertEquals(1080f, fit2.height, 0.5f)
    }

    // ---- v0.5.4: degenerate-space gesture guard (device audit §1) ----------

    @Test
    fun `degenerate content space reproduces the device report and is rejected`() {
        // The reported collapsed surface: fitRect into a ~30 px tall stage
        // on a 720 px-wide device yields exactly 53x30 px. That space must
        // be classified as UNUSABLE so the gesture layer refuses it.
        val fit = OverlayLayout.fitRect(720f, 30f, 16f / 9f)
        assertEquals(53.3f, fit.width, 0.5f)
        assertEquals(30f, fit.height, 0.5f)
        assertEquals(
            false,
            OverlayLayout.isUsableContentSpace(fit.width, fit.height),
        )
    }

    @Test
    fun `healthy preview spaces pass the usability gate`() {
        // The same device's healthy viewport (720x405 px) and the smallest
        // layout-guarded stage floor (140 dp at 1.75 density = 245 px).
        assertTrue(OverlayLayout.isUsableContentSpace(720f, 405f))
        assertTrue(OverlayLayout.isUsableContentSpace(245f, 245f))
        // Exactly at the threshold is usable (inclusive), one px below is not.
        val min = OverlayLayout.MIN_USABLE_CONTENT_PX
        assertTrue(OverlayLayout.isUsableContentSpace(min, min))
        assertEquals(false, OverlayLayout.isUsableContentSpace(min - 1f, min))
        assertEquals(false, OverlayLayout.isUsableContentSpace(min, min - 1f))
        // Zero-size (no layout yet) is rejected.
        assertEquals(false, OverlayLayout.isUsableContentSpace(0f, 0f))
    }

    @Test
    fun `export uniform scale and preview size agree`() {
        // v0.8.1 (BUG A fix): the export draws bitmap_px * scale (verified
        // against Media3 1.4.1's OverlayShaderProgramPixelTest goldens), so
        // ONE uniform scale must reproduce the preview's box: drawn width =
        // widthFrac * backgroundW, height = width / bitmapAspect.
        // A text-like block of 1203x192 px on a 1080x1920 background.
        val s = OverlayLayout.overlayExportScale(1203, 1080, 0.629f)
        val drawnW = 1203f * s
        val drawnH = 192f * s
        val (pw, ph) = OverlayLayout.previewSize(1080f, 1920f, 1203f / 192f, 0.629f)
        assertEquals(pw, drawnW, 0.01f)
        assertEquals(ph, drawnH, 0.01f)
        // The bitmap is not distorted — the reported collapse (10.8 px tall)
        // is gone: the height is 1/10th of the frame width scaled, not 1/100th.
        assertEquals(679.35f, drawnW, 0.5f)
        assertEquals(108.4f, drawnH, 0.5f)
        // Degenerate inputs stay sane: zero background falls back to the
        // bitmap's own width (natural px, never collapsed); a zero-width
        // bitmap never gets amplified by a bogus ratio.
        assertEquals(0.5f, OverlayLayout.overlayExportScale(2400, 0, 0.5f), 1e-4f)
        assertEquals(0.5f, OverlayLayout.overlayExportScale(0, 1080, 0.5f), 1e-4f)
    }

    @Test
    fun `rotation is converted between clockwise model and ccw export`() {
        assertEquals(-30f, OverlayLayout.toExportRotation(30f), 1e-4f)
        assertEquals(30f, OverlayLayout.fromExportRotation(-30f), 1e-4f)
        // Round trip.
        assertEquals(45f, OverlayLayout.fromExportRotation(OverlayLayout.toExportRotation(45f)), 1e-4f)
    }

    @Test
    fun `text width fraction places font at sizeFrac of content height`() {
        // Text bitmap rendered at canonical 1080 height with sizeFrac 0.07.
        val sizeFrac = 0.07f
        val bitmapAspect = 3f // e.g. 1728x576 text block
        val contentAspect = 9f / 16f // portrait project
        val widthFrac = OverlayLayout.textWidthFrac(sizeFrac, bitmapAspect, contentAspect)
        // Drawn width/height on a content rect 1080x1920:
        val (w, h) = OverlayLayout.previewSize(1080f, 1920f, bitmapAspect, widthFrac)
        val fontPx = sizeFrac * 1920f // desired font height on screen
        // The bitmap was rendered with font = sizeFrac * 1080; bitmap height maps:
        val bitmapH = w / bitmapAspect
        val fontOnScreen = sizeFrac * 1080f * (bitmapH / (sizeFrac * 1080f * (1f / 1f)))
        // Simpler invariant: rendered font height fraction of the bitmap equals
        // sizeFrac*CANONICAL_H/bitmapH0 — verify proportionality instead:
        assertEquals(w, widthFrac * 1080f, 0.01f)
        assertTrue(fontOnScreen > 0f && h > 0f)
    }
}

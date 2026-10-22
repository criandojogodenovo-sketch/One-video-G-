package com.onevideog.editor

import com.onevideog.editor.data.Interpolation
import com.onevideog.editor.data.Keyframe
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind
import com.onevideog.editor.data.OverlayTransform
import com.onevideog.editor.data.ProjectAspect
import com.onevideog.editor.data.TextAnimation
import com.onevideog.editor.data.TextAnimPreset
import com.onevideog.editor.data.TextAnimUnit
import com.onevideog.editor.editor.AnimatedTextLayout
import com.onevideog.editor.editor.ExpandedOverlays
import com.onevideog.editor.editor.KeyframeEvaluator
import com.onevideog.editor.editor.OverlayBitmapRenderer
import com.onevideog.editor.editor.TextAnimationPlanner
import com.onevideog.editor.editor.TextSegmentPlanner
import org.junit.Assert.assertEquals

import org.junit.Test

/**
 * v0.5.8: PREVIEW ↔ EXPORT letter-for-letter parity for an animated text
 * overlay — the pure proof behind the device report "the animated text looks
 * big and tight in the editor but small with wide letter gaps in the export".
 *
 * Both pipelines consume the SAME shared objects (verified here by using only
 * them in this test):
 *  - planning: [OverlayBitmapRenderer.textMetrics] + [TextSegmentPlanner]
 *    (ONE segmentation for the preview expansion AND the export composite);
 *  - animation: [TextAnimationPlanner] relative keyframes evaluated with
 *    [KeyframeEvaluator] at PROJECT time — the same unit (ms) and the same
 *    reference (the project timeline) in the editor (playhead) and in the
 *    exporter (`clipStartMs + presentationTimeUs/1000`);
 *  - placement: the preview applies [ExpandedOverlays.composeStatic] (via the
 *    composed segment keyframes) and sizes boxes with
 *    `scale × contentWidth`; the export composites with
 *    [AnimatedTextLayout.segmentFrame] and places the block with
 *    `OverlayLayout.overlayScaleSettings(frameAspect, blockAspectW, scale)`.
 *
 * The test evaluates BOTH at a grid of project times and asserts the segment
 * centre / width / opacity agree in NORMALIZED content coordinates — the
 * coordinates that are converted to the output resolution by ONE factor per
 * pipeline (contentWidth on the preview stage, frame width in the export
 * frame). With frameAspect == contentAspect (the fixed contract since the
 * v0.5.8 frameAspect fix) the two conversions are the same conversion.
 *
 * Rotation-free + linear: composeStatic's rotation rides cos/sin of the
 * parent angle and the entrance y-delta rides the block frame (PARITY doc
 * limitation 12) — the documented micro-deltas, not part of this contract.
 */
class AnimatedTextOverlayParityTest {

    // Fake measurer: monospace 10 px per char — the TextSegmentPlanner test
    // pattern (no android.graphics.Paint on the JVM).
    private val measure: (String) -> Float = { it.length * 10f }

    /** The same fake measurer, as the injectable planner interface (v0.5.8). */
    private val fakeMeasurer = TextSegmentPlanner.Measurer { it.length * 10f }

    private fun animatedTextOverlay() = OverlayItem(
        id = "txt-kotlin",
        kind = OverlayKind.TEXT,
        startMs = 0,
        endMs = 4_000,
        transform = OverlayTransform(x = 0.5f, y = 0.35f, scale = 0.4f),
        text = "Kotlin",
        sizeFrac = 0.08f,
        interpolation = Interpolation.LINEAR,
        textAnimation = TextAnimation(
            preset = TextAnimPreset.FADE,
            unit = TextAnimUnit.LETTER,
            durationMs = 600,
        ),
    )

    private fun project(overlay: OverlayItem) = OvgProject(
        id = "p",
        name = "p",
        createdAtEpochMs = 0,
        updatedAtEpochMs = 0,
        aspect = ProjectAspect.R9_16.name, // contentAspect = frameAspect = 9/16
        overlays = listOf(overlay),
    )

    private val contentAspect = 9f / 16f

    /** Canonical metrics of the parent block (the ONE shared layout source). */
    private fun metrics(overlay: OverlayItem) = OverlayBitmapRenderer.textMetrics(overlay)

    /** Segment bitmap size in canonical px (what textBitmap renders for it). */
    private fun segmentBitmapSize(text: String, metrics: OverlayBitmapRenderer.TextMetrics) =
        OverlayBitmapRenderer.blockSizePx(listOf(text), metrics, measure)

    @Test
    fun `preview and export agree on every letter at every animation time`() {
        val overlay = animatedTextOverlay()
        val m = metrics(overlay)

        // ---- EXPORT side: the exact inputs texturesForOverlays builds ----
        val spec = ExpandedOverlays.animationSpec(overlay, contentAspect, measurer = fakeMeasurer)!!
        assertEquals(6, spec.geoms.size) // "Kotlin" -> 6 LETTER segments
        val blockW = spec.frame.widthPx
        val blockH = spec.frame.heightPx

        // ---- PREVIEW side: the exact render list computePlacements walks ----
        val expanded = ExpandedOverlays.build(project(overlay), fakeMeasurer)
        val segments = expanded.overlays.filter { it.id.startsWith("txt-kotlin#s") }
        assertEquals(6, segments.size)

        // Every project time across the overlay window (entrance 0..1150 ms
        // staggered + rest) — the SAME t both pipelines evaluate.
        var t = 0L
        while (t < 4_000L) {
            for ((index, segment) in segments.withIndex()) {
                // PREVIEW: composed segment keyframes evaluated at project t,
                // then computePlacements' mapping to content fractions.
                val eval = KeyframeEvaluator.evaluate(segment, t)

                // EXPORT: parent + relative entrance evaluated separately at
                // the SAME project t (AnimatedTextOverlay.getBitmap), laid
                // out by the shared AnimatedTextLayout, then converted back
                // to content fractions the way OverlayLayout.overlayScaleSettings
                // + the background-frame anchor place them on the frame.
                val parentEval = KeyframeEvaluator.evaluate(overlay, t)
                val parentScale = parentEval.scale
                val heightFrac = AnimatedTextLayout.blockHeightFrac(
                    parentScale, contentAspect, spec.blockAspectW,
                )
                val schedule = spec.schedules.getOrNull(index)!!
                val relative = OverlayItem(
                    id = "rel",
                    kind = OverlayKind.TEXT,
                    transform = TextAnimationPlanner.identityRelative(),
                    keyframes = schedule.keyframes,
                    interpolation = overlay.interpolation,
                )
                val rel = KeyframeEvaluator.evaluate(relative, t)
                val bmpSize = segmentBitmapSize(spec.geoms[index].text, m)
                val frame = AnimatedTextLayout.segmentFrame(
                    centerXFrac = spec.geoms[index].centerXFrac,
                    centerYFrac = spec.geoms[index].centerYFrac,
                    widthFrac = spec.geoms[index].widthFrac,
                    bitmapWidth = bmpSize.first,
                    bitmapHeight = bmpSize.second,
                    relX = rel.x,
                    relY = rel.y,
                    relScale = rel.scale,
                    relOpacity = rel.opacity,
                    parentScale = parentScale,
                    blockHeightFrac = heightFrac,
                    blockWidthPx = blockW,
                    blockHeightPx = blockH,
                )!!
                // Block px -> content fractions (frame == content rect:
                // frameAspect == contentAspect, block width = parentScale).
                val exportXFrac = parentEval.x - parentScale / 2f +
                    frame.centerXpx * parentScale / blockW
                val exportYFrac = parentEval.y - heightFrac / 2f +
                    frame.centerYpx * heightFrac / blockH
                val exportWidthFrac = frame.widthPx * parentScale / blockW
                val exportOpacity = rel.opacity * parentEval.opacity

                assertEquals("seg $index x @ $t ms", eval.x, exportXFrac, 1e-4f)
                assertEquals("seg $index y @ $t ms", eval.y, exportYFrac, 1e-4f)
                assertEquals("seg $index width @ $t ms", eval.scale, exportWidthFrac, 1e-4f)
                assertEquals("seg $index opacity @ $t ms", eval.opacity, exportOpacity, 1e-4f)
            }
            t += 50L
        }
    }

    /**
     * Verification point (2) of the report: the per-letter layout is planned
     * in canonical fractions and converted to the output resolution by ONE
     * factor — at a concrete output resolution the exported letter boxes are
     * the preview boxes times (frameWidth / contentWidth).
     */
    @Test
    fun `letter boxes convert to the output resolution with one shared factor`() {
        val overlay = animatedTextOverlay()
        val m = metrics(overlay)
        val spec = ExpandedOverlays.animationSpec(overlay, contentAspect, measurer = fakeMeasurer)!!
        val blockW = spec.frame.widthPx

        // At rest (t past every entrance) everything is identity-relative.
        val t = 2_000L
        val parentEval = KeyframeEvaluator.evaluate(overlay, t)
        val heightFrac =
            AnimatedTextLayout.blockHeightFrac(parentEval.scale, contentAspect, spec.blockAspectW)

        val frameW = 1080f
        val frameH = frameW / contentAspect // 1920
        for ((index, geom) in spec.geoms.withIndex()) {
            val bmpSize = segmentBitmapSize(geom.text, m)
            val frame = AnimatedTextLayout.segmentFrame(
                    centerXFrac = geom.centerXFrac,
                    centerYFrac = geom.centerYFrac,
                    widthFrac = geom.widthFrac,
                    bitmapWidth = bmpSize.first,
                    bitmapHeight = bmpSize.second,
                    relX = 0f, relY = 0f, relScale = 1f, relOpacity = 1f,
                    parentScale = parentEval.scale,
                    blockHeightFrac = heightFrac,
                    blockWidthPx = blockW,
                    blockHeightPx = spec.frame.heightPx,
                )!!
            // On-frame px (the export resolution conversion).
            val blockWFramePx = parentEval.scale * frameW
            val letterWidthPx = frame.widthPx * blockWFramePx / blockW
            val letterCenterXPx = parentEval.x * frameW - blockWFramePx / 2f +
                frame.centerXpx * blockWFramePx / blockW
            val letterCenterYPx = parentEval.y * frameH - heightFrac * frameH / 2f +
                frame.centerYpx * (blockWFramePx / blockW)

            // The preview draws the same letter at scale*contentW wide and
            // (centre + (frac-0.5)*scale) inside ANY content width — assert
            // the export numbers equal the preview formula at contentW=1080.
            val previewWidthPx = parentEval.scale * geom.widthFrac * frameW
            assertEquals(previewWidthPx, letterWidthPx, 0.01f)
            val previewCenterXPx = (parentEval.x +
                (geom.centerXFrac - 0.5f) * parentEval.scale) * frameW
            assertEquals(previewCenterXPx, letterCenterXPx, 0.01f)
            val previewCenterYPx = (parentEval.y +
                (geom.centerYFrac - 0.5f) * parentEval.scale * contentAspect / spec.blockAspectW
                ) * frameH
            assertEquals(previewCenterYPx, letterCenterYPx, 0.01f)
        }
    }

    /** Layout guard: identity entrance / zero width behave as documented. */
    @Test
    fun `segmentFrame skips non-positive widths and floors degenerate fractions`() {
        val frame = AnimatedTextLayout.segmentFrame(
            centerXFrac = 0.5f, centerYFrac = 0.5f, widthFrac = 0.2f,
            bitmapWidth = 100, bitmapHeight = 20,
            relX = 0f, relY = 0f, relScale = 0f, relOpacity = 1f,
            parentScale = 0.4f, blockHeightFrac = 0.3f,
            blockWidthPx = 500, blockHeightPx = 100,
        )
        assertEquals(null, frame)

        assertEquals(
            0.3f,
            AnimatedTextLayout.blockHeightFrac(0.4f, contentAspect = 0.6f, blockAspectW = 0.8f),
            1e-6f,
        )
    }

    /** The planner's relative keyframes stay RELATIVE (identity base). */
    @Test
    fun `entrance keyframes evaluated on the identity base end at full opacity`() {
        val schedule = TextAnimationPlanner.entranceKeyframes(
            TextAnimPreset.FADE, startMs = 1_000, durationMs = 600,
        )
        val synthetic = OverlayItem(
            id = "rel",
            kind = OverlayKind.TEXT,
            transform = TextAnimationPlanner.identityRelative(),
            keyframes = schedule,
            interpolation = Interpolation.LINEAR,
        )
        val before = KeyframeEvaluator.evaluate(synthetic, 1_000)
        val after = KeyframeEvaluator.evaluate(synthetic, 1_600)
        assertEquals(0f, before.opacity, 1e-6f)
        assertEquals(1f, after.opacity, 1e-6f)
        // Scale factor untouched by FADE — the identity base holds.
        assertEquals(1f, after.scale, 1e-6f)
        // And the last keyframe time feeds the ANIM_TEXT_FRAME log gate.
        assertEquals(1_600L, schedule.maxOf { it.timeMs })
    }
}

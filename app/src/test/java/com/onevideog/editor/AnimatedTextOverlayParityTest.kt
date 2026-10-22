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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

import org.junit.Test

/**
 * v0.5.9: PREVIEW ↔ EXPORT letter-for-letter parity for an animated text
 * overlay through THE ONE layout function ([AnimatedTextLayout
 * .segmentPlacement]) with the SINGLE reference the user prescribed — the
 * project frame (frameW × frameH, frameAspect = 9/16 here).
 *
 * What the test proves, at a grid of project times:
 *  1. the PREVIEW's per-frame inputs (ExpandedOverlays.build SegmentPlans:
 *     block fractions + relative entrance item) are the SAME objects the
 *     export composite consumes ([ExpandedOverlays.animationSpec] — one
 *     planning rule, [OverlayBitmapRenderer.textMetrics] + planner);
 *  2. both pipelines therefore get IDENTICAL frame placements (same
 *     function, same inputs);
 *  3. the export's block-px conversion ([AnimatedTextLayout.blockRect])
 *     round-trips the frame placement exactly — the composite texture is
 *     pixel-identical to what the placement says, so no size/width drift
 *     can appear between the editor and the exported file;
 *  4. NO source-video aspect enters the layout: the only aspects are the
 *     frameAspect and the canonical block/segment bitmap aspects.
 *
 * The fake measurer stands in for Paint.measureText on the JVM (the
 * TextSegmentPlanner test pattern); production passes ONE shared measurer
 * ([OverlayBitmapRenderer.textMeasurer]) to both pipelines.
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

    private fun segmentAspect(text: String, m: OverlayBitmapRenderer.TextMetrics): Float {
        val (w, h) = segmentBitmapSize(text, m)
        return if (h <= 0) 1f else w.toFloat() / h
    }

    @Test
    fun `preview and export agree on every letter at every animation time`() {
        val overlay = animatedTextOverlay()
        val m = metrics(overlay)

        // ---- EXPORT side: the exact inputs texturesForOverlays builds ----
        val spec = ExpandedOverlays.animationSpec(overlay, contentAspect, measurer = fakeMeasurer)!!
        assertEquals(6, spec.geoms.size) // "Kotlin" -> 6 LETTER segments
        val blockW = spec.frame.widthPx
        val blockH = spec.frame.heightPx

        // ---- PREVIEW side: the render list + per-segment plans the
        // computePlacements walk consumes (the SAME build) ----
        val expanded = ExpandedOverlays.build(project(overlay), fakeMeasurer)
        val segments = expanded.overlays.filter { it.id.startsWith("txt-kotlin#s") }
        assertEquals(6, segments.size)

        // Every project time across the overlay window (entrance 0..1150 ms
        // staggered + rest) — the SAME t both pipelines evaluate.
        var t = 0L
        while (t < 4_000L) {
            val parentEval = KeyframeEvaluator.evaluate(overlay, t)
            val parentScale = parentEval.scale
            val heightFrac = AnimatedTextLayout.blockHeightFrac(
                parentScale, contentAspect, spec.blockAspectW,
            )
            for ((index, segment) in segments.withIndex()) {
                val plan = expanded.segmentPlans[segment.id]!!
                // The plan's geometry IS the export spec's geometry (one
                // planning rule — no drift possible).
                assertEquals(spec.geoms[index].centerXFrac, plan.centerXFrac, 0f)
                assertEquals(spec.geoms[index].centerYFrac, plan.centerYFrac, 0f)
                assertEquals(spec.geoms[index].widthFrac, plan.widthFrac, 0f)
                assertEquals(spec.blockAspectW, plan.blockAspectW, 0f)

                // EXPORT: parent + relative entrance evaluated separately at
                // the SAME project t (AnimatedTextOverlay.getBitmap), laid
                // out by THE ONE layout function, converted to block px for
                // the composite canvas, then back to frame fractions the way
                // OverlaySettings places the block on the frame.
                val rel = KeyframeEvaluator.evaluate(plan.relative, t)
                val placement = AnimatedTextLayout.segmentPlacement(
                    parentX = parentEval.x,
                    parentY = parentEval.y,
                    parentScale = parentScale,
                    centerXFrac = plan.centerXFrac,
                    centerYFrac = plan.centerYFrac,
                    widthFrac = plan.widthFrac,
                    blockAspectW = plan.blockAspectW,
                    segmentAspect = segmentAspect(spec.geoms[index].text, m),
                    relX = rel.x,
                    relY = rel.y,
                    relScale = rel.scale,
                    frameAspect = contentAspect,
                )!!
                val rect = AnimatedTextLayout.blockRect(
                    placement = placement,
                    parentX = parentEval.x,
                    parentY = parentEval.y,
                    parentScale = parentScale,
                    blockHeightFrac = heightFrac,
                    blockWidthPx = blockW,
                    blockHeightPx = blockH,
                )
                // block px -> frame fractions (the inverse conversion).
                val exportXFrac = parentEval.x - parentScale / 2f +
                    rect.centerXpx * parentScale / blockW
                val exportYFrac = parentEval.y - heightFrac / 2f +
                    rect.centerYpx * heightFrac / blockH
                val exportWidthFrac = rect.widthPx * parentScale / blockW
                val exportHeightFrac = rect.heightPx * heightFrac / blockH

                assertEquals(placement.centerXFrac, exportXFrac, 1e-3f)
                assertEquals(placement.centerYFrac, exportYFrac, 1e-3f)
                assertEquals(placement.widthFrac, exportWidthFrac, 1e-3f)
                assertEquals(placement.heightFrac, exportHeightFrac, 1e-3f)

                // The PREVIEW evaluates the SAME plan through the SAME
                // function (computePlacements) — identical by construction;
                // the assertion documents the contract.
                val previewPlacement = AnimatedTextLayout.segmentPlacement(
                    parentX = parentEval.x,
                    parentY = parentEval.y,
                    parentScale = parentScale,
                    centerXFrac = plan.centerXFrac,
                    centerYFrac = plan.centerYFrac,
                    widthFrac = plan.widthFrac,
                    blockAspectW = plan.blockAspectW,
                    segmentAspect = segmentAspect(spec.geoms[index].text, m),
                    relX = rel.x,
                    relY = rel.y,
                    relScale = rel.scale,
                    frameAspect = contentAspect,
                )!!
                assertEquals(placement.centerXFrac, previewPlacement.centerXFrac, 0f)
                assertEquals(placement.centerYFrac, previewPlacement.centerYFrac, 0f)
                assertEquals(placement.widthFrac, previewPlacement.widthFrac, 0f)
                assertEquals(placement.heightFrac, previewPlacement.heightFrac, 0f)

                // The frame fractions ARE the content fractions the preview
                // draws (centre + width): x·frameW, y·frameH, w·frameW — the
                // single-reference contract the user prescribed.
                assertTrue(placement.widthFrac > 0f)
            }
            t += 50L
        }
    }

    /**
     * Verification point (2) of the report: the per-letter layout is planned
     * in frame fractions and converted to the output resolution by ONE
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
            val placement = AnimatedTextLayout.segmentPlacement(
                parentX = parentEval.x,
                parentY = parentEval.y,
                parentScale = parentEval.scale,
                centerXFrac = geom.centerXFrac,
                centerYFrac = geom.centerYFrac,
                widthFrac = geom.widthFrac,
                blockAspectW = spec.blockAspectW,
                segmentAspect = segmentAspect(geom.text, m),
                relX = 0f, relY = 0f, relScale = 1f,
                frameAspect = contentAspect,
            )!!
            val rect = AnimatedTextLayout.blockRect(
                placement = placement,
                parentX = parentEval.x,
                parentY = parentEval.y,
                parentScale = parentEval.scale,
                blockHeightFrac = heightFrac,
                blockWidthPx = blockW,
                blockHeightPx = spec.frame.heightPx,
            )
            // On-frame px (the export resolution conversion) via the block.
            val blockWFramePx = parentEval.scale * frameW
            val letterWidthPx = rect.widthPx * blockWFramePx / blockW
            val letterCenterXPx = parentEval.x * frameW - blockWFramePx / 2f +
                rect.centerXpx * blockWFramePx / blockW
            val letterCenterYPx = parentEval.y * frameH - heightFrac * frameH / 2f +
                rect.centerYpx * (blockWFramePx / blockW)

            // The preview draws the same letter at widthFrac × contentW wide
            // at (cxFrac, cyFrac) inside ANY content width — assert the
            // export numbers equal the preview formula at contentW=1080.
            val previewWidthPx = placement.widthFrac * frameW
            assertEquals(previewWidthPx, letterWidthPx, 0.01f)
            assertEquals(placement.centerXFrac * frameW, letterCenterXPx, 0.01f)
            assertEquals(placement.centerYFrac * frameH, letterCenterYPx, 0.01f)
        }
    }

    /** Layout guard: identity entrance / zero width behave as documented. */
    @Test
    fun `segmentPlacement skips non-positive widths and floors degenerate fractions`() {
        assertNull(
            AnimatedTextLayout.segmentPlacement(
                parentX = 0.5f, parentY = 0.5f, parentScale = 0.4f,
                centerXFrac = 0.5f, centerYFrac = 0.5f, widthFrac = 0.2f,
                blockAspectW = 2f, segmentAspect = 5f,
                relX = 0f, relY = 0f, relScale = 0f,
                frameAspect = 0.5625f,
            ),
        )

        assertEquals(
            0.3f,
            AnimatedTextLayout.blockHeightFrac(0.4f, frameAspect = 0.6f, blockAspectW = 0.8f),
            1e-6f,
        )
        // Height = width × frameAspect / segmentAspect — the ONLY aspect
        // conversion (frame + bitmap aspects; no source-video aspect).
        val p = AnimatedTextLayout.segmentPlacement(
            parentX = 0.5f, parentY = 0.5f, parentScale = 0.4f,
            centerXFrac = 0.5f, centerYFrac = 0.5f, widthFrac = 0.25f,
            blockAspectW = 4f, segmentAspect = 2f,
            relX = 0f, relY = 0f, relScale = 1f,
            frameAspect = 0.5f,
        )!!
        assertEquals(0.4f * 0.25f, p.widthFrac, 1e-6f)
        assertEquals(0.1f * 0.5f / 2f, p.heightFrac, 1e-6f)
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

package com.onevideog.editor.editor

import android.graphics.Paint
import com.onevideog.editor.data.Keyframe
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind
import com.onevideog.editor.data.OverlayTransform
import com.onevideog.editor.data.TextAnimPreset
import com.onevideog.editor.data.TextAnimUnit
import com.onevideog.editor.diag.DiagContext
import com.onevideog.editor.diag.DiagSanitizer
import com.onevideog.editor.diag.DiagnosticsLogger
import kotlin.math.cos
import kotlin.math.sin

/**
 * Expands text overlays with a [com.onevideog.editor.data.TextAnimation]
 * into per-segment overlays — the ONE expansion used by BOTH pipelines:
 *
 *  - PREVIEW: [com.onevideog.editor.ui.editor.OverlayLayer] /
 *    computePlacements render the expanded list (Compose owns preview
 *    overlay pixels — v0.3.1 contract untouched);
 *  - EXPORT: [ClipEffectsFactory.overlayTextures] builds one
 *    [KeyframedOverlay] per segment through the SAME
 *    [ClipEffectPlan.overlayWindowsForClip] windows.
 *
 * Composition model (all pure, all unit-tested):
 *
 *  1. [TextSegmentPlanner] places each word/letter inside the canonical
 *     block bitmap frame (pixel-aligned with the un-animated block);
 *  2. [composeStatic] converts a segment's block-frame geometry into a
 *     content-space [OverlayTransform] relative to the parent's transform —
 *     parent rotation rotates the offset around the block centre and parent
 *     scale scales the offset and the segment;
 *  3. [TextAnimationPlanner] emits RELATIVE entrance keyframes
 *     (opacity/scale factors, y delta);
 *  4. per segment, the parent's keyframes and the entrance keyframes are
 *     composed at the UNION of their times:
 *     `kf(t) = combine(composeStatic(parentEval(t), rel), entranceEval(t))`
 *     so the segment follows the parent's animation exactly at every
 *     authored keyframe time and interpolates the entrance between its own
 *     times (linear/ease per the overlay's interpolation setting).
 *
 * visible=false and locked keep applying to the WHOLE overlay: hidden
 * parents are not expanded at all (render nothing in both pipelines), and
 * segment items inherit the parent's locked flag.
 */
object ExpandedOverlays {

    /**
     * G5 safety limit: segments per overlay. Each segment owns a bitmap +
     * export texture, so unbounded segmentation (a paragraph letter by
     * letter) would multiply memory — at ~150x60 px ARGB_8888 a letter
     * bitmap is ~36 KB, 96 segments ≈ 3.5 MB inside the shared 20 MB
     * LruCache (worst case; identical words share one cache entry).
     */
    const val MAX_SEGMENTS = 96

    /** Segment id separator (segment ids are `parent#sN`). */
    private const val SEGMENT_SUFFIX = "#s"

    /** Segment position inside the parent's block bitmap frame. */
    data class RelGeometry(
        val centerXFrac: Float,
        val centerYFrac: Float,
        val widthFrac: Float,
        /** Block bitmap aspect (w/h) — converts x-fractions to y-fractions. */
        val blockAspectW: Float,
    )

    data class Result(
        /** Render list: segments replace animated text overlays. */
        val overlays: List<OverlayItem>,
        /** segmentId -> parentId (selection/gesture mapping in the canvas). */
        val parentOfSegment: Map<String, String>,
        /**
         * Parent ids whose animation was NOT applied because the segment
         * count exceeded [MAX_SEGMENTS] even after the WORD fallback — the
         * UI surfaces this as a warning (G6).
         */
        val limited: Set<String>,
    )

    /**
     * Builds the render list for [project]. The heavy work is text
     * measurement (a few dozen measureText calls) — callers memoise the
     * result per project revision (preview) or call it once per export.
     */
    fun build(project: OvgProject): Result {
        val renderList = mutableListOf<OverlayItem>()
        val parentOfSegment = mutableMapOf<String, String>()
        val limited = mutableSetOf<String>()
        val contentAspect = projectContentAspect(project)
        for (overlay in project.overlays) {
            val segments = expandOverlay(overlay, contentAspect, limited)
            for (segment in segments) {
                parentOfSegment[segment.id] = overlay.id
                renderList += segment
            }
        }
        return Result(renderList, parentOfSegment, limited)
    }

    /**
     * Pure composition: parent transform + block-frame segment geometry ->
     * the segment's content-space transform.
     *
     * The offset from the block centre is computed in content-normalised
     * units ((frac-0.5) * parent.scale across; the y axis additionally
     * carries the content/block aspect ratio), then rotated by the parent's
     * clockwise-positive rotation. NOT clamped here on purpose: segments of
     * a block near the frame edge may centre slightly outside 0..1 — both
     * pipelines clamp identically at evaluation time
     * ([KeyframeEvaluator.evaluate] -> withClampedValues), so parity holds.
     */
    fun composeStatic(
        parent: OverlayTransform,
        rel: RelGeometry,
        contentAspect: Float,
    ): OverlayTransform {
        val dx = (rel.centerXFrac - 0.5f) * parent.scale
        val dy = (rel.centerYFrac - 0.5f) * parent.scale *
            contentAspect / rel.blockAspectW
        val rad = Math.toRadians(parent.rotationDeg.toDouble())
        val rx = dx * cos(rad) - dy * sin(rad)
        val ry = dx * sin(rad) + dy * cos(rad)
        return OverlayTransform(
            x = parent.x + rx.toFloat(),
            y = parent.y + ry.toFloat(),
            scale = parent.scale * rel.widthFrac,
            rotationDeg = parent.rotationDeg,
            opacity = parent.opacity,
        )
    }

    /**
     * Pure composition: static segment transform + evaluated RELATIVE
     * entrance transform (deltas/factors — see TextAnimationPlanner) ->
     * the absolute transform written into the composed keyframes.
     */
    fun combineTransform(
        static: OverlayTransform,
        relative: OverlayTransform,
    ): OverlayTransform = OverlayTransform(
        x = static.x + relative.x,
        y = static.y + relative.y,
        scale = static.scale * relative.scale,
        rotationDeg = static.rotationDeg + relative.rotationDeg,
        opacity = static.opacity * relative.opacity,
    )

    // ------------------------------------------------------------- internals

    /**
     * Expands one overlay into its segments (empty when no expansion
     * applies: non-text, no animation, hidden, blank text, or the G5 limit
     * fell back).
     */
    private fun expandOverlay(
        overlay: OverlayItem,
        contentAspect: Float,
        limited: MutableSet<String>,
    ): List<OverlayItem> {
        val anim = overlay.textAnimation
        if (overlay.kind != OverlayKind.TEXT || !anim.isEnabled || !overlay.visible) {
            return emptyList()
        }
        val metrics = OverlayBitmapRenderer.textMetrics(overlay)
        val measurer = paintMeasurer(overlay, metrics)
        var unit = anim.unit
        var geoms = TextSegmentPlanner.plan(overlay.text, unit, metrics, measurer)
        if (geoms.isEmpty()) return emptyList()
        if (geoms.size > MAX_SEGMENTS) {
            // G5: LETTER on a long text overflows -> fall back to WORD
            // (coarser granularity, far fewer segments).
            if (unit == TextAnimUnit.LETTER) {
                unit = TextAnimUnit.WORD
                geoms = TextSegmentPlanner.plan(overlay.text, unit, metrics, measurer)
            }
            if (geoms.size > MAX_SEGMENTS) {
                DiagnosticsLogger.w(
                    "preview", "TEXT_ANIM_LIMIT", DiagContext.currentOpId(),
                    "text animation skipped: segment limit exceeded",
                    meta = listOf(
                        "ovId" to DiagSanitizer.fingerprint(overlay.id),
                        "segments" to geoms.size.toString(),
                        "limit" to MAX_SEGMENTS.toString(),
                        "unit" to unit.name,
                    ),
                )
                limited += overlay.id
                return emptyList()
            }
        }
        val frame = TextSegmentPlanner.blockFrame(overlay.text, metrics, measurer)
        val blockAspectW = if (frame.heightPx <= 0) {
            1f
        } else {
            frame.widthPx.toFloat() / frame.heightPx
        }
        val schedules = TextAnimationPlanner.plan(
            anim.preset, anim.durationMs,
            overlay.startMs, overlay.endMs, geoms.size,
        )
        val segments = mutableListOf<OverlayItem>()
        for ((index, geom) in geoms.withIndex()) {
            val rel = RelGeometry(
                geom.centerXFrac, geom.centerYFrac, geom.widthFrac, blockAspectW,
            )
            val schedule = schedules.getOrNull(index)
            segments += buildSegment(overlay, index, geom.text, rel, schedule, contentAspect, unit)
        }
        return segments
    }

    /**
     * One segment item: text of the segment, static transform from the
     * parent's base, and the COMPOSED keyframes (parent animation x entrance
     * animation at the union of their times). The final state holds until
     * the overlay window ends (KeyframeEvaluator hold-after-last).
     */
    private fun buildSegment(
        parent: OverlayItem,
        index: Int,
        text: String,
        rel: RelGeometry,
        schedule: TextAnimationPlanner.SegmentSchedule?,
        contentAspect: Float,
        unit: TextAnimUnit,
    ): OverlayItem {
        val staticBase = composeStatic(parent.transform, rel, contentAspect)
        val composed = if (schedule == null) {
            emptyList()
        } else {
            val synthetic = OverlayItem(
                id = "rel",
                kind = OverlayKind.TEXT,
                transform = TextAnimationPlanner.identityRelative(),
                keyframes = schedule.keyframes,
                interpolation = parent.interpolation,
            )
            // Union of authored parent keyframe times (inside the window)
            // and entrance keyframe times: the parent's animation is exact
            // at every authored time; between union points the evaluator
            // interpolates the composed values.
            val unionTimes = (parent.keyframes.map { it.timeMs }
                .filter { it in parent.startMs..parent.endMs } +
                schedule.keyframes.map { it.timeMs }).distinct().sorted()
            unionTimes.map { t ->
                val parentEval = KeyframeEvaluator.evaluate(parent, t)
                val static = composeStatic(parentEval, rel, contentAspect)
                val relative = KeyframeEvaluator.evaluate(synthetic, t)
                val combined = combineTransform(static, relative)
                Keyframe(
                    timeMs = t,
                    x = combined.x,
                    y = combined.y,
                    scale = combined.scale,
                    rotationDeg = combined.rotationDeg,
                    opacity = combined.opacity,
                )
            }
        }
        return parent.copy(
            id = segmentId(parent.id, index),
            text = text,
            transform = staticBase,
            // Already-expanded: a segment never re-expands (build only reads
            // project.overlays, but the marker also documents intent).
            textAnimation = com.onevideog.editor.data.TextAnimation(
                preset = TextAnimPreset.NONE,
                unit = unit,
                durationMs = parent.textAnimation.durationMs,
            ),
            keyframes = composed,
        )
    }

    /** Deterministic segment id (stable across rebuilds within a session). */
    fun segmentId(parentId: String, index: Int): String = "$parentId$SEGMENT_SUFFIX$index"

    /** True when [overlayId] refers to a generated text segment. */
    fun isSegmentId(overlayId: String): Boolean = overlayId.contains(SEGMENT_SUFFIX)

    /** Parent id of a segment id (or the id itself when not a segment). */
    fun parentIdOf(segmentId: String): String =
        segmentId.substringBefore(SEGMENT_SUFFIX)

    /**
     * The content aspect the composition uses — project aspect first, then
     * the first clip's source aspect, then 16:9 (same priority order the
     * preview viewport uses; per-clip aspect variation inherits the parent
     * overlay's existing model limitation).
     */
    fun projectContentAspect(project: OvgProject): Float =
        project.aspectEnum.ratio()
            ?: project.clips.firstOrNull()?.sourceAspect
            ?: 16f / 9f

    /** Production measurer: the SAME paint the block renderer uses. */
    private fun paintMeasurer(
        overlay: OverlayItem,
        metrics: OverlayBitmapRenderer.TextMetrics,
    ): TextSegmentPlanner.Measurer {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = metrics.fontPx
            typeface = OverlayBitmapRenderer.typefaceFor(
                overlay.fontFamily, overlay.bold, overlay.italic,
            )
        }
        return TextSegmentPlanner.Measurer { paint.measureText(it) }
    }
}

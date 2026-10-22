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
 * visible=false keeps applying to the WHOLE overlay: hidden parents are not
 * expanded and render nothing in both pipelines. Every other overlay that
 * needs no expansion (images, plain text) passes through as itself — since
 * v0.5.5 this is explicit and unit-tested; v0.5.0-v0.5.4 silently dropped it
 * from both pipelines (docs/AUDIT_v0.5.4_report_followup.md §1). Locked
 * parents keep the flag on every segment item.
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
        /**
         * v0.5.9: segmentId -> its plan (block-frame geometry + the RELATIVE
         * entrance item). The preview's computePlacements evaluates the
         * parent + the relative entrance PER FRAME and lays the segment out
         * with [AnimatedTextLayout.segmentPlacement] — THE SAME layout
         * function the export composite uses, with the SAME single reference
         * (the project frame). Without a plan (never, for segments built
         * here) the preview falls back to the segment's composed keyframes.
         */
        val segmentPlans: Map<String, SegmentPlan> = emptyMap(),
    )

    /**
     * v0.5.9: everything the preview needs to lay ONE segment out per frame
     * through the shared [AnimatedTextLayout.segmentPlacement] — the block
     * fractions of the segment inside the canonical block frame plus the
     * synthetic RELATIVE entrance item (identity base + schedule keyframes).
     */
    data class SegmentPlan(
        val parentId: String,
        val centerXFrac: Float,
        val centerYFrac: Float,
        val widthFrac: Float,
        /** Block bitmap aspect (w/h) — converts x-fractions to y-fractions. */
        val blockAspectW: Float,
        /** Identity-base synthetic whose keyframes are the RELATIVE entrance. */
        val relative: OverlayItem,
    )

    /**
     * v0.5.7: the segment PLAN for one overlay without building segment
     * items — the ONE planning rule shared by the preview expansion
     * ([build]) and the export single-texture composite
     * ([AnimatedTextOverlay]). Returns null when the overlay must NOT be
     * segmented (then BOTH pipelines render the plain overlay):
     *  - not TEXT or the entrance animation is disabled;
     *  - blank text cannot be segmented;
     *  - segment count over [MAX_SEGMENTS] even after the LETTER->WORD
     *    fallback (G5 — logs TEXT_ANIM_LIMIT and reports the id through
     *    [onLimited] so the caller can surface the UI warning).
     */
    data class AnimSpec(
        val unit: TextAnimUnit,
        val geoms: List<TextSegmentPlanner.SegmentGeometry>,
        val schedules: List<TextAnimationPlanner.SegmentSchedule>,
        val frame: TextSegmentPlanner.BlockFrame,
        val blockAspectW: Float,
    )

    fun animationSpec(
        overlay: OverlayItem,
        contentAspect: Float,
        onLimited: ((String) -> Unit)? = null,
        /**
         * v0.5.8: injectable text measurer (the TextSegmentPlanner pattern).
         * Null = the production Paint measurer — the ONLY change for callers
         * is that JVM tests can now exercise the enabled-animation plan.
         */
        measurer: TextSegmentPlanner.Measurer? = null,
    ): AnimSpec? {
        val anim = overlay.textAnimation
        if (overlay.kind != OverlayKind.TEXT || !anim.isEnabled) return null
        val metrics = OverlayBitmapRenderer.textMetrics(overlay)
        val measure = measurer ?: paintMeasurer(overlay, metrics)
        var unit = anim.unit
        var geoms = TextSegmentPlanner.plan(overlay.text, unit, metrics, measure)
        if (geoms.isEmpty()) return null
        if (geoms.size > MAX_SEGMENTS) {
            // G5: LETTER on a long text overflows -> fall back to WORD
            // (coarser granularity, far fewer segments).
            if (unit == TextAnimUnit.LETTER) {
                unit = TextAnimUnit.WORD
                geoms = TextSegmentPlanner.plan(overlay.text, unit, metrics, measure)
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
                onLimited?.invoke(overlay.id)
                return null
            }
        }
        val frame = TextSegmentPlanner.blockFrame(overlay.text, metrics, measure)
        val blockAspectW = if (frame.heightPx <= 0) {
            1f
        } else {
            frame.widthPx.toFloat() / frame.heightPx
        }
        val schedules = TextAnimationPlanner.plan(
            anim.preset, anim.durationMs,
            overlay.startMs, overlay.endMs, geoms.size,
        )
        return AnimSpec(unit, geoms, schedules, frame, blockAspectW)
    }

    /**
     * Builds the render list for [project]. The heavy work is text
     * measurement (a few dozen measureText calls) — callers memoise the
     * result per project revision (preview) or call it once per export.
     * [measurer] v0.5.8: injectable for JVM tests (null = production Paint).
     */
    fun build(
        project: OvgProject,
        measurer: TextSegmentPlanner.Measurer? = null,
    ): Result {
        val renderList = mutableListOf<OverlayItem>()
        val parentOfSegment = mutableMapOf<String, String>()
        val limited = mutableSetOf<String>()
        val plans = mutableMapOf<String, SegmentPlan>()
        val contentAspect = projectContentAspect(project)
        for (overlay in project.overlays) {
            val segments = expandOverlay(overlay, contentAspect, limited, measurer, plans)
            for (segment in segments) {
                parentOfSegment[segment.id] = overlay.id
                renderList += segment
            }
        }
        return Result(renderList, parentOfSegment, limited, plans)
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
     * Expands one overlay into its segments. Overlays that need NO expansion
     * are passed through UNCHANGED (v0.5.5, see the docs/Audit reference in
     * the guard below); only HIDDEN overlays expand to nothing.
     */
    private fun expandOverlay(
        overlay: OverlayItem,
        contentAspect: Float,
        limited: MutableSet<String>,
        measurer: TextSegmentPlanner.Measurer? = null,
        plans: MutableMap<String, SegmentPlan>? = null,
    ): List<OverlayItem> {
        // v0.5.5 (docs/AUDIT_v0.5.4_report_followup.md §1): overlays that need
        // no expansion must still RENDER. Since v0.5.0 this path returned
        // emptyList() and build() only appended segments, so EVERY image
        // overlay and every text overlay without an entrance animation was
        // dropped from the ONE render list both pipelines consume: the preview
        // never drew them (no placement -> no pixels, no hit-test, no gizmos)
        // and the export composition built zero overlay textures for them
        // (device report 2026-10-21: a project with overlays=1 exported with
        // effects=0 textures=0). Hidden layers stay dropped — the v0.3.0 rule
        // (render nothing in preview AND export) is unchanged.
        if (!overlay.visible) return emptyList()
        // v0.5.7: the planning rule moved to [animationSpec] (shared with the
        // export composite); expansion and pass-through behaviour identical.
        val spec = animationSpec(overlay, contentAspect, { limited += it }, measurer)
            ?: return listOf(overlay)
        val segments = mutableListOf<OverlayItem>()
        for ((index, geom) in spec.geoms.withIndex()) {
            val rel = RelGeometry(
                geom.centerXFrac, geom.centerYFrac, geom.widthFrac, spec.blockAspectW,
            )
            val schedule = spec.schedules.getOrNull(index)
            // v0.5.9: the RELATIVE entrance item is built ONCE here and
            // shared by the composed keyframes below AND the preview's
            // per-frame plan evaluation (SegmentPlan.relative).
            val relative = OverlayItem(
                id = "rel",
                kind = OverlayKind.TEXT,
                transform = TextAnimationPlanner.identityRelative(),
                keyframes = schedule?.keyframes ?: emptyList(),
                interpolation = overlay.interpolation,
            )
            val segment = buildSegment(
                overlay, index, geom.text, rel, schedule, contentAspect, spec.unit, relative,
            )
            segments += segment
            if (plans != null) {
                plans[segment.id] = SegmentPlan(
                    parentId = overlay.id,
                    centerXFrac = geom.centerXFrac,
                    centerYFrac = geom.centerYFrac,
                    widthFrac = geom.widthFrac,
                    blockAspectW = spec.blockAspectW,
                    relative = relative,
                )
            }
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
        /** v0.5.9: shared synthetic relative item (identity base + schedule). */
        relative: OverlayItem,
    ): OverlayItem {
        val staticBase = composeStatic(parent.transform, rel, contentAspect)
        val composed = if (schedule == null) {
            emptyList()
        } else {
            val synthetic = relative
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

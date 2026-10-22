package com.onevideog.editor.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind
import com.onevideog.editor.data.OverlayTransform
import com.onevideog.editor.diag.DiagSanitizer
import com.onevideog.editor.diag.DiagnosticsLogger
import com.onevideog.editor.diag.ImageImportStages
import com.onevideog.editor.editor.AnimatedTextLayout
import com.onevideog.editor.editor.ExpandedOverlays
import com.onevideog.editor.editor.GizmoMath
import com.onevideog.editor.editor.KeyframeEvaluator
import com.onevideog.editor.editor.OverlayBitmapRenderer
import com.onevideog.editor.editor.OverlayLayout
import android.graphics.Typeface
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolved CONTENT of one overlay — what the Compose overlay layer renders.
 *
 * v0.5.3 device fix: the preview no longer renders overlay pixels from
 * bitmaps in a draw-phase Canvas (the old path produced active overlays
 * with ZERO drawn pixels on the affected device — ACTIVATION logged
 * activeOverlays=1 while nothing appeared). The layer now renders:
 *  - TEXT -> Compose `Text` primitives, laid out from the SAME canonical
 *    block the export bitmap uses ([OverlayTextRender.Layout]);
 *  - IMAGE -> Compose `Image` with the decoded bitmap (async decode state,
 *    cache-backed — never decoded on the render path).
 */
internal sealed interface OverlayRender {

    /** Text block: canonical layout + the exact export typeface. */
    data class TextBlock(
        val layout: OverlayTextRender.Layout,
        val typeface: Typeface,
    ) : OverlayRender

    /** Decoded image content. */
    data class ImageContent(val bitmap: ImageBitmap) : OverlayRender
}

/** One overlay evaluated at the current playhead, resolved to layer pixels. */
internal data class PlacedOverlay(
    val overlay: OverlayItem,
    val transform: OverlayTransform,
    val render: OverlayRender,
    val centerX: Float,
    val centerY: Float,
    val widthPx: Float,
    val heightPx: Float,
    val selected: Boolean,
    /**
     * v0.5.0: parent overlay id when this placement is a generated text
     * animation segment ("parent#sN"); null for regular overlays. Selection
     * and gestures always target the PARENT — segments render only.
     */
    val parentId: String? = null,
)

/**
 * Computes every overlay's placement for [playheadMs] using the SAME pure
 * math the export composition uses ([KeyframeEvaluator] + [OverlayLayout]).
 *
 * [overlays] is the EXPANDED render list ([ExpandedOverlays.build]): text
 * animation segments carry ids "parent#sN" and are placed individually;
 * selection is mapped back to the parent id so gestures keep targeting the
 * whole text block.
 *
 * v0.5.9: animated-text segments are laid out through
 * [AnimatedTextLayout.segmentPlacement] — THE SAME function the export
 * composite uses, with the SAME single reference (the project frame,
 * [contentAspect] on the preview side). The per-segment plan
 * ([ExpandedOverlays.SegmentPlan], from the SAME build) carries the block
 * fractions and the RELATIVE entrance item; the parent + relative are
 * evaluated per frame exactly like [AnimatedTextOverlay.getBitmap] does.
 * Segments without a plan fall back to their composed keyframes (previous
 * behaviour).
 */
internal fun computePlacements(
    overlays: List<OverlayItem>,
    playheadMs: Long,
    contentAspect: Float,
    contentWidth: Float,
    contentHeight: Float,
    resolveRender: (OverlayItem) -> OverlayRender?,
    selectedId: String?,
    parents: List<OverlayItem> = emptyList(),
    segmentPlans: Map<String, ExpandedOverlays.SegmentPlan> = emptyMap(),
): List<PlacedOverlay> {
    if (contentWidth <= 0f || contentHeight <= 0f) return emptyList()
    return overlays
        // v0.3.0: hidden layers render nothing (same rule as the export
        // pipeline — see ClipEffectPlan.overlayWindowsForClip).
        .filter { it.startMs <= playheadMs && playheadMs < it.endMs && it.visible }
        .sortedBy { it.zOrder }
        .mapNotNull { overlay ->
            val render = resolveRender(overlay) ?: return@mapNotNull null
            // The placement aspect is the CONTENT aspect the export scales
            // onto the frame: the canonical block for text, the decoded
            // bitmap for images.
            val aspect = when (render) {
                is OverlayRender.TextBlock -> render.layout.aspect
                is OverlayRender.ImageContent ->
                    if (render.bitmap.height <= 0) 1f
                    else render.bitmap.width.toFloat() / render.bitmap.height
            }
            // ---- v0.5.9: shared layout function for animated-text segments ----
            val plan = segmentPlans[overlay.id]
            if (plan != null) {
                val parent = parents.firstOrNull { it.id == plan.parentId }
                if (parent != null) {
                    val parentEval = KeyframeEvaluator.evaluate(parent, playheadMs)
                    val rel = KeyframeEvaluator.evaluate(plan.relative, playheadMs)
                    val placement = AnimatedTextLayout.segmentPlacement(
                        parentX = parentEval.x,
                        parentY = parentEval.y,
                        parentScale = parentEval.scale,
                        centerXFrac = plan.centerXFrac,
                        centerYFrac = plan.centerYFrac,
                        widthFrac = plan.widthFrac,
                        blockAspectW = plan.blockAspectW,
                        segmentAspect = aspect,
                        relX = rel.x,
                        relY = rel.y,
                        relScale = rel.scale,
                        frameAspect = contentAspect,
                    )
                    if (placement != null) {
                        val isSegment = ExpandedOverlays.isSegmentId(overlay.id)
                        val parentId =
                            if (isSegment) ExpandedOverlays.parentIdOf(overlay.id) else null
                        return@mapNotNull PlacedOverlay(
                            overlay = overlay,
                            transform = OverlayTransform(
                                x = placement.centerXFrac,
                                y = placement.centerYFrac,
                                scale = placement.widthFrac,
                                rotationDeg = parentEval.rotationDeg,
                                // Parent opacity x relative entrance factor —
                                // the SAME product the export applies (bitmap
                                // rel alpha x OverlaySettings alphaScale).
                                opacity = parentEval.opacity * rel.opacity,
                            ),
                            render = render,
                            centerX = placement.centerXFrac * contentWidth,
                            centerY = placement.centerYFrac * contentHeight,
                            widthPx = placement.widthFrac * contentWidth,
                            heightPx = placement.heightFrac * contentHeight,
                            selected = (parentId ?: overlay.id) == selectedId,
                            parentId = parentId,
                        )
                    }
                }
            }
            val transform = KeyframeEvaluator.evaluate(overlay, playheadMs)
            val (w, h) = OverlayLayout.previewSize(
                contentWidth, contentHeight, aspect, transform.scale,
            )
            // v0.5.0: segment ids ("parent#sN") map back to their parent for
            // selection/gestures; regular overlays map to themselves.
            val isSegment = ExpandedOverlays.isSegmentId(overlay.id)
            val parentId = if (isSegment) ExpandedOverlays.parentIdOf(overlay.id) else null
            PlacedOverlay(
                overlay = overlay,
                transform = transform,
                render = render,
                centerX = transform.x * contentWidth,
                centerY = transform.y * contentHeight,
                widthPx = w,
                heightPx = h,
                selected = (parentId ?: overlay.id) == selectedId,
                parentId = parentId,
            )
        }
}

/**
 * v0.5.0: the selection chrome box for a selected PARENT — a single
 * placement for regular overlays, the bounding box of all its segment
 * placements for an animated text block (segments share the parent's
 * rotation, so the rotated chrome wraps the whole block).
 */
internal fun parentChromeBox(
    placements: List<PlacedOverlay>,
    selectedId: String?,
): PlacedOverlay? {
    val group = placements.filter { (it.parentId ?: it.overlay.id) == selectedId }
    val first = group.firstOrNull() ?: return null
    if (group.size == 1) return first
    val left = group.minOf { it.centerX - it.widthPx / 2f }
    val right = group.maxOf { it.centerX + it.widthPx / 2f }
    val top = group.minOf { it.centerY - it.heightPx / 2f }
    val bottom = group.maxOf { it.centerY + it.heightPx / 2f }
    return first.copy(
        centerX = (left + right) / 2f,
        centerY = (top + bottom) / 2f,
        widthPx = (right - left).coerceAtLeast(1f),
        heightPx = (bottom - top).coerceAtLeast(1f),
        selected = true,
    )
}

/**
 * v0.5.3: applies the live gesture override to the raw placements — the
 * delta between the override and the parent's current EVALUATED transform
 * is applied to ALL of the parent's placements, so the whole block follows
 * the finger while its entrance animation keeps playing (and a regular
 * overlay moves 1:1). Extracted unchanged from the old draw block.
 */
private fun applyGestureOverride(
    raw: List<PlacedOverlay>,
    override: Pair<String, OverlayTransform>?,
    parents: List<OverlayItem>,
    playheadMs: Long,
    contentWidth: Float,
    contentHeight: Float,
): List<PlacedOverlay> {
    if (override == null) return raw
    val parentId = override.first
    val parentNow = parents.firstOrNull { it.id == parentId }
        ?.let { KeyframeEvaluator.evaluate(it, playheadMs) }
        ?: return raw
    val t = override.second
    val dx = (t.x - parentNow.x) * contentWidth
    val dy = (t.y - parentNow.y) * contentHeight
    val f = t.scale / parentNow.scale.coerceAtLeast(1e-3f)
    val dRot = t.rotationDeg - parentNow.rotationDeg
    return raw.map { placed ->
        if ((placed.parentId ?: placed.overlay.id) == parentId) {
            placed.copy(
                centerX = placed.centerX + dx,
                centerY = placed.centerY + dy,
                widthPx = placed.widthPx * f,
                heightPx = placed.heightPx * f,
                transform = placed.transform.copy(
                    rotationDeg = placed.transform.rotationDeg + dRot,
                ),
            )
        } else {
            placed
        }
    }
}

/** Point containment against a rotated rect (for touch hit-testing). */
internal fun PlacedOverlay.contains(point: Offset): Boolean {
    val local = toLocal(point)
    return abs(local.x) <= widthPx / 2f && abs(local.y) <= heightPx / 2f
}

/** Maps a canvas point into the overlay's un-rotated local space. */
internal fun PlacedOverlay.toLocal(point: Offset): Offset {
    val dx = point.x - centerX
    val dy = point.y - centerY
    val rad = Math.toRadians(transform.rotationDeg.toDouble())
    // Inverse-rotate the point into the overlay's axis-aligned space.
    // Screen y grows downward, and Compose rotate(degrees) is clockwise in
    // that space, matching the model's clockwise-positive rotationDeg.
    val localX = (dx * cos(rad) + dy * sin(rad)).toFloat()
    val localY = (-dx * sin(rad) + dy * cos(rad)).toFloat()
    return Offset(localX, localY)
}

/** Local (un-rotated, centred) point -> canvas space, rotating by the model. */
internal fun PlacedOverlay.toCanvas(local: Offset): Offset {
    val rad = Math.toRadians(transform.rotationDeg.toDouble())
    val x = local.x * cos(rad) - local.y * sin(rad)
    val y = local.x * sin(rad) + local.y * cos(rad)
    return Offset(centerX + x.toFloat(), centerY + y.toFloat())
}

/**
 * The live overlay layer (v0.5.3 — Compose composable rendering).
 *
 * Rendering contract:
 *  - The CONTENT layer is a plain Compose [Box] ABOVE the PlayerView. It
 *    renders the CONTENT of every overlay active at the current playhead
 *    (startMs <= playhead < endMs, visible) — ALWAYS, selected or not,
 *    independent of selection and of the ExoPlayer GL effect list (which
 *    carries no overlay textures in the preview since v0.3.1). Text renders
 *    with `Text` composables, images with `Image` — standard Compose UI
 *    children of the composition tree, NOT draw-phase bitmaps: this is the
 *    fix for the device bug where active overlays produced zero pixels.
 *  - Content is CLIPPED to the content rect ([clipToBounds]): the export
 *    composites overlays per-clip (before Presentation), so nothing may
 *    bleed into the letterbox bars in either path (parity contract — see
 *    docs/PREVIEW_EXPORT_PARITY.md).
 *  - The selection chrome (bounds frame, rotate knob, delete chip, scale
 *    corner) lives in a SEPARATE layer — an unclipped [Canvas] with no
 *    pointer input on top of the content — drawn ONLY for the selected
 *    overlay (bounding box of its segments when animated). Touches pass
 *    through it to the content layer below.
 *  - Positions are the model's normalized coordinates (x, y in 0..1 of the
 *    content area, scale as a width fraction) evaluated by the SAME pure
 *    math the export composition uses ([KeyframeEvaluator] +
 *    [OverlayLayout]), so what is seen here is what the export composes.
 *    Text block geometry comes from [OverlayTextRender] — the same
 *    canonical block, wrap and metrics the export bitmap uses.
 *  - The playhead arrives as Compose state ([EditorViewModel.UiState
 *    .playheadMs] — the same state the timeline and transport use), so
 *    overlays appear/disappear in sync with the timeline and the player
 *    position without any extra rebuilding. In a project WITHOUT video the
 *    layer is driven by the clip-less clock in [PreviewStage] — the exact
 *    same code path (black 16:9 stage, no PlayerView content).
 *
 * Evidence: every active overlay is logged as OVERLAY_DRAW (id, kind, x, y,
 * scale, rotation, opacity, visible, bitmap/text size) when the drawn set
 * changes, or at most once per second for the same set while playing — the
 * device diagnosis for the rewrite was activeOverlays=1 with no draw
 * evidence at all.
 *
 * Gestures: tap to select/deselect, drag body to move, corner handle to
 * scale, top handle to rotate (clockwise-positive), delete chip to remove,
 * two-finger pinch/twist upgrade. Edits commit once per gesture so undo
 * stays usable. The gesture recogniser lives on the CONTENT layer and
 * hit-tests the same placements the layer renders.
 */
@Composable
internal fun OverlayLayer(
    overlays: List<OverlayItem>,
    playheadMs: () -> Long,
    contentAspect: () -> Float,
    contentWidthPx: () -> Float,
    contentHeightPx: () -> Float,
    selectedOverlayId: () -> String?,
    gestureOverride: () -> Pair<String, OverlayTransform>?,
    /**
     * v0.5.0: the UN-expanded overlays (project.overlays) — the gesture and
     * selection targets. Animated text blocks render as segments but are
     * always selected/transformed/deleted as their parent.
     */
    parents: () -> List<OverlayItem>,
    /** Preview correlation id for the OVERLAY_DRAW evidence events. */
    opId: String,
    /**
     * v0.5.9: segmentId -> plan from the SAME [ExpandedOverlays.build] that
     * produced [overlays] — routes animated-text segments through the shared
     * [AnimatedTextLayout.segmentPlacement] (preview == export layout).
     */
    segmentPlans: () -> Map<String, ExpandedOverlays.SegmentPlan> = { emptyMap() },
    onTap: (OverlayItem?) -> Unit,
    onGesture: (OverlayItem, OverlayTransform) -> Unit,
    onGestureEnd: (OverlayItem, OverlayTransform) -> Unit,
    onDelete: (OverlayItem) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // Composition-time values — read fresh on every recomposition (the
    // parent recomposes on every playhead/state change).
    val parentsList = parents()
    val selectedId = selectedOverlayId()
    val override = gestureOverride()
    val contentW = contentWidthPx()
    val contentH = contentHeightPx()
    val contentAsp = contentAspect()
    val playhead = playheadMs()

    // ---- image content: async decode state (cache-backed, never inline) ----
    // v0.2.5 rule kept: the render path must never decode on the UI thread;
    // failures back off instead of retrying every frame. The decode uses the
    // SAME shared cache the export pipeline populates.
    val imageBitmaps = remember { mutableStateMapOf<String, ImageBitmap>() }
    val failedImageIds = remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(overlays) {
        overlays
            .filter { it.kind == OverlayKind.IMAGE && it.id !in failedImageIds.value }
            .forEach { overlay ->
                val key = OverlayBitmapRenderer.contentKey(overlay)
                if (imageBitmaps.containsKey(key)) return@forEach
                val decoded = withContext(Dispatchers.Default) {
                    OverlayBitmapRenderer.imageBitmap(context, overlay)
                }
                if (decoded != null) {
                    imageBitmaps[key] = decoded.asImageBitmap()
                } else {
                    failedImageIds.value = failedImageIds.value + overlay.id
                }
            }
    }

    // ---- text content: canonical layout cache (per content key) ----
    val textLayouts = remember { mutableMapOf<String, OverlayTextRender.Layout>() }
    val textTypefaces = remember { mutableMapOf<String, Typeface>() }

    fun resolveRender(overlay: OverlayItem): OverlayRender? = when (overlay.kind) {
        OverlayKind.TEXT -> {
            val key = OverlayBitmapRenderer.contentKey(overlay)
            val layout = textLayouts.getOrPut(key) {
                // v0.5.9: THE ONE shared measurer (OverlayBitmapRenderer
                // .textMeasurer) — the exact paint the export block renderer
                // measures/draws with; preview and export cannot drift.
                val measurer = OverlayBitmapRenderer.textMeasurer(overlay)
                OverlayTextRender.layout(overlay) { measurer.measureText(it) }
            }
            val typeface = textTypefaces.getOrPut(key) {
                OverlayBitmapRenderer.typefaceFor(
                    overlay.fontFamily, overlay.bold, overlay.italic,
                )
            }
            OverlayRender.TextBlock(layout, typeface)
        }
        OverlayKind.IMAGE ->
            imageBitmaps[OverlayBitmapRenderer.contentKey(overlay)]
                ?.let { OverlayRender.ImageContent(it) }
    }

    val rawPlacements = computePlacements(
        overlays = overlays,
        playheadMs = playhead,
        contentAspect = contentAsp,
        contentWidth = contentW,
        contentHeight = contentH,
        resolveRender = ::resolveRender,
        selectedId = selectedId,
        parents = parentsList,
        segmentPlans = segmentPlans(),
    )
    val placements = applyGestureOverride(
        raw = rawPlacements,
        override = override,
        parents = parentsList,
        playheadMs = playhead,
        contentWidth = contentW,
        contentHeight = contentH,
    )

    // ---- OVERLAY_DRAW evidence (throttled: on set change or 1 Hz) ----
    val drawLogGate = remember { OverlayDrawLogGate() }
    LaunchedEffect(placements) {
        val sig = placements.joinToString("|") { p ->
            "${p.parentId ?: p.overlay.id}:" +
                "${p.widthPx.roundToInt()}x${p.heightPx.roundToInt()}"
        }
        val now = SystemClock.elapsedRealtime()
        if (!drawLogGate.shouldLog(sig, now)) return@LaunchedEffect
        drawLogGate.log(sig, now)
        for (p in placements) {
            val contentMeta = when (val r = p.render) {
                is OverlayRender.TextBlock ->
                    "text block=${r.layout.blockW}x${r.layout.blockH}" +
                        " lines=${r.layout.lines.size}" +
                        " chars=${r.layout.charCount}"
                is OverlayRender.ImageContent ->
                    "image bitmap=${r.bitmap.width}x${r.bitmap.height}"
            }
            DiagnosticsLogger.d(
                "preview", ImageImportStages.OVERLAY_DRAW, opId,
                "overlay rendered by compose layer",
                meta = buildList {
                    // v0.5.5: parent-first id — OVERLAY_ADD/OVERLAY_TRANSFORM log
                    // the parent, so the report trail joins only if the draw
                    // does too. The segment id (animated text) rides in `seg`.
                    add("ovId" to DiagSanitizer.fingerprint(p.parentId ?: p.overlay.id))
                    if (p.parentId != null) {
                        add("seg" to DiagSanitizer.fingerprint(p.overlay.id))
                    }
                    add("kind" to p.overlay.kind.name)
                    add("x" to p.transform.x.toString())
                    add("y" to p.transform.y.toString())
                    add("scale" to p.transform.scale.toString())
                    add("rotDeg" to p.transform.rotationDeg.toString())
                    add("opacity" to p.transform.opacity.toString())
                    add("visible" to p.overlay.visible.toString())
                    add("boxPx" to "${p.widthPx.roundToInt()}x${p.heightPx.roundToInt()}")
                    add("content" to contentMeta)
                },
            )
        }
    }

    // ---- gesture-recogniser inputs (kept fresh via updated states) ----
    val currentPlacements = rememberUpdatedState(placements)
    val currentSelected = rememberUpdatedState(selectedId)
    val currentParents = rememberUpdatedState(parentsList)
    val currentPlayhead = rememberUpdatedState(playhead)
    val currentW = rememberUpdatedState(contentW)
    val currentH = rememberUpdatedState(contentH)
    val currentOnTap = rememberUpdatedState(onTap)
    val currentOnGesture = rememberUpdatedState(onGesture)
    val currentOnGestureEnd = rememberUpdatedState(onGestureEnd)
    val currentOnDelete = rememberUpdatedState(onDelete)

    /** Maps a hit placement back to its gesture/selection target. */
    fun targetFor(placement: PlacedOverlay): OverlayItem? {
        val wantedId = placement.parentId ?: placement.overlay.id
        return currentParents.value.firstOrNull { it.id == wantedId }
            ?: placement.overlay.takeIf { it.id == wantedId }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ------------- content layer (clipped, interactive) -------------
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val w = currentW.value
                        val h = currentH.value
                        // v0.5.4: a degenerate content space (collapsed stage,
                        // e.g. the reported 53×30 px surface) must not host
                        // gestures — deltas divide by the content size, so a
                        // few px of drag corrupts the saved transform, and the
                        // 24 dp gizmo zones cover the whole box (a plain tap
                        // classifies as DELETE). Refuse the whole interaction;
                        // the layout guards make this unreachable in practice
                        // (docs/AUDIT_v0.5.3_problems.md §1/§6), this is the
                        // corruption hard-stop with evidence.
                        if (!OverlayLayout.isUsableContentSpace(w, h)) {
                            DiagnosticsLogger.d(
                                "preview", "OVERLAY_GESTURE_REJECTED", opId,
                                "overlay gesture rejected: degenerate content space",
                                meta = listOf(
                                    "contentW" to w.toString(),
                                    "contentH" to h.toString(),
                                ),
                            )
                            return@awaitEachGesture
                        }
                        val layerPlacements = currentPlacements.value
                        // v0.5.0: hit placement -> gesture/selection target
                        // (the PARENT for animated text segments, the overlay
                        // itself otherwise).
                        val hitTarget = layerPlacements
                            .lastOrNull { it.contains(down.position) }
                            ?.let { targetFor(it) }
                        // v0.5.0: chrome/geometry for the SELECTED PARENT (the
                        // bounding box of its segment placements when animated).
                        val selectedChrome = parentChromeBox(
                            layerPlacements,
                            currentSelected.value,
                        )
                        var mode = GestureMode.IDLE
                        var base: OverlayTransform? = null
                        var moved = false
                        var gestureTarget: OverlayItem? = null
                        // v0.4.0 incremental rotation state (D4): the handle
                        // angle is accumulated per event via [GizmoMath
                        // .angleDelta] so crossing the atan2 ±180° seam can
                        // never flip the model rotation by a full turn.
                        var rotateAccum = 0f
                        var lastHandleAngle = 0f
                        // v0.4.0 two-finger pinch/twist state (D3/D4).
                        var pinching = false
                        var pinchStartDist = 0f
                        var pinchBase: OverlayTransform? = null
                        var pinchStartCentroid = Offset.Zero
                        var lastPinchAngle = 0f
                        var pinchRotateAccum = 0f

                        if (hitTarget != null &&
                            hitTarget.id == currentSelected.value &&
                            selectedChrome != null &&
                            !hitTarget.locked
                        ) {
                            // The touched overlay's PARENT is already selected
                            // AND not locked: check handles. Locked overlays
                            // stay selectable (tap falls through to selection
                            // below) but never start a transform/delete
                            // gesture — v0.3.0 layer lock (segments inherit
                            // the parent's lock, so animated blocks behave the
                            // same). The pointerInput scope is a Density —
                            // resolve zones in px.
                            val zoneR = 24.dp.toPx()
                            gestureTarget = hitTarget
                            base = KeyframeEvaluator.evaluate(
                                hitTarget, currentPlayhead.value,
                            )
                            mode = when {
                                selectedChrome.isInDeleteZone(down.position, zoneR) ->
                                    GestureMode.DELETE
                                selectedChrome.isInRotateZone(down.position, zoneR) ->
                                    GestureMode.ROTATE
                                selectedChrome.isInScaleZone(down.position, zoneR) ->
                                    GestureMode.SCALE
                                else -> GestureMode.MOVE
                            }
                            if (mode == GestureMode.ROTATE) {
                                lastHandleAngle = angleDeg(
                                    selectedChrome.centerX,
                                    selectedChrome.centerY,
                                    down.position,
                                )
                            }
                            currentOnGesture.value(hitTarget, base)
                        }
                        val scaleStart = selectedChrome?.let {
                            distance(it.centerX, it.centerY, down.position)
                        } ?: 1f
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressedPointers = event.changes.filter { it.pressed }
                            val pressed = pressedPointers.isNotEmpty()
                            val pan = event.calculatePan()
                            if (base != null && gestureTarget != null &&
                                mode != GestureMode.DELETE
                            ) {
                                // ---- two-finger pinch/twist upgrade (v0.4.0) ----
                                if (mode == GestureMode.MOVE &&
                                    pressedPointers.size >= 2 && !pinching
                                ) {
                                    val (a, b) = pressedPointers
                                        .take(2).map { it.position }
                                    pinching = true
                                    pinchBase = base
                                    pinchStartDist = twoFingerDistance(a, b)
                                    lastPinchAngle = twoFingerAngle(a, b)
                                    pinchStartCentroid = Offset(
                                        (a.x + b.x) / 2f,
                                        (a.y + b.y) / 2f,
                                    )
                                    pinchRotateAccum = 0f
                                }
                                if (pinching && pressedPointers.size < 2) {
                                    // Back to one finger: continue as a plain
                                    // move from the pinch-accumulated state.
                                    pinching = false
                                }
                                val next = if (pinching && pressedPointers.size >= 2) {
                                    val (a, b) = pressedPointers
                                        .take(2).map { it.position }
                                    val nowDist = twoFingerDistance(a, b)
                                    val nowCentroid = Offset(
                                        (a.x + b.x) / 2f,
                                        (a.y + b.y) / 2f,
                                    )
                                    val nowAngle = twoFingerAngle(a, b)
                                    pinchRotateAccum += GizmoMath.angleDelta(
                                        lastPinchAngle, nowAngle,
                                    )
                                    lastPinchAngle = nowAngle
                                    val pb = pinchBase ?: base
                                    pb.copy(
                                        x = (pb.x +
                                            (nowCentroid.x - pinchStartCentroid.x) / w)
                                            .coerceIn(0f, 1f),
                                        y = (pb.y +
                                            (nowCentroid.y - pinchStartCentroid.y) / h)
                                            .coerceIn(0f, 1f),
                                        scale = GizmoMath.pinchScale(
                                            pb.scale, pinchStartDist, nowDist,
                                        ),
                                        rotationDeg = pb.rotationDeg + pinchRotateAccum,
                                    )
                                } else {
                                    val pointer = pressedPointers
                                        .firstOrNull()?.position ?: down.position
                                    when (mode) {
                                        GestureMode.MOVE -> base.copy(
                                            x = (base.x + pan.x / w).coerceIn(0f, 1f),
                                            y = (base.y + pan.y / h).coerceIn(0f, 1f),
                                        )
                                        GestureMode.SCALE -> {
                                            val dist = distance(
                                                selectedChrome!!.centerX,
                                                selectedChrome.centerY,
                                                pointer,
                                            )
                                            base.copy(
                                                scale = (base.scale *
                                                    (dist / scaleStart.coerceAtLeast(1f)))
                                                    .coerceIn(0.05f, 1.6f),
                                            )
                                        }
                                        GestureMode.ROTATE -> {
                                            val angle = angleDeg(
                                                selectedChrome!!.centerX,
                                                selectedChrome.centerY,
                                                pointer,
                                            )
                                            // Incremental, seam-safe
                                            // accumulation (v0.4.0).
                                            rotateAccum += GizmoMath.angleDelta(
                                                lastHandleAngle, angle,
                                            )
                                            lastHandleAngle = angle
                                            base.copy(
                                                rotationDeg = base.rotationDeg +
                                                    rotateAccum,
                                            )
                                        }
                                        else -> null
                                    }
                                }
                                if (next != null) {
                                    moved = true
                                    base = next
                                    currentOnGesture.value(gestureTarget, next)
                                }
                            }
                            event.changes.forEach { it.consume() }
                            if (!pressed) break
                        }

                        val target = gestureTarget
                        when {
                            target != null && mode == GestureMode.DELETE && !moved ->
                                currentOnDelete.value(target)
                            target != null && base != null && moved ->
                                currentOnGestureEnd.value(
                                    target, base.withClampedValues(),
                                )
                            hitTarget != null ->
                                // Plain tap (no drag) on an overlay: select
                                // its PARENT (v0.5.0 — segments select the
                                // block).
                                currentOnTap.value(hitTarget)
                            else ->
                                currentOnTap.value(null)
                        }
                    }
                },
        ) {
            // Pass 1 — content: EVERY active overlay renders its pixels
            // here, always (v0.5.3 Compose children; children are composed
            // in zOrder order — placements are sorted ascending, matching
            // the export's texture FIFO).
            placements.forEach { placed ->
                OverlayPlacementContent(placed, density)
            }
        }

        // ------------- chrome layer (unclipped, non-interactive) -------------
        // Pass 2 — selection chrome: drawn in its OWN layer, ONLY for the
        // selected PARENT (bounding box of its segments when animated), on
        // top of all content so the handles sit above everything. A LOCKED
        // layer draws bounds-only chrome (no handles): the handles would be
        // inert and only invite dead taps (v0.4.0 — E3). No pointer input:
        // touches fall through to the content layer.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val chrome = parentChromeBox(placements, selectedId)
                ?: return@Canvas
            val locked = parentsList
                .firstOrNull { it.id == (chrome.parentId ?: chrome.overlay.id) }
                ?.locked ?: chrome.overlay.locked
            drawSelectionChrome(chrome, interactive = !locked)
        }
    }
}

/**
 * Throttle gate for the OVERLAY_DRAW evidence: log when the drawn set
 * signature changes (add/remove/resize/edit) or at most once per second
 * for an unchanged set (live transform evidence while playing). Plain
 * holder — no snapshot state, so logging never triggers recomposition.
 */
private class OverlayDrawLogGate {
    var lastSig: String? = null
    var lastAtMs: Long = 0L

    fun shouldLog(sig: String, nowMs: Long): Boolean =
        sig != lastSig || nowMs - lastAtMs >= 1000L

    fun log(sig: String, nowMs: Long) {
        lastSig = sig
        lastAtMs = nowMs
    }
}

/**
 * One placement's content: a positioned, sized, rotated, alpha-applied box
 * whose child is the resolved [OverlayRender]. Rotation pivots on the box
 * centre (graphicsLayer default), opacity applies to the WHOLE overlay —
 * exactly like the export applies OverlaySettings opacity to the bitmap.
 */
@Composable
private fun OverlayPlacementContent(placed: PlacedOverlay, density: Density) {
    val w = placed.widthPx
    val h = placed.heightPx
    if (w <= 0f || h <= 0f) return
    val left = placed.centerX - w / 2f
    val top = placed.centerY - h / 2f
    val widthDp = with(density) { w.toDp() }
    val heightDp = with(density) { h.toDp() }
    Box(
        modifier = Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(widthDp, heightDp)
            .graphicsLayer {
                rotationZ = placed.transform.rotationDeg
                alpha = placed.transform.opacity.coerceIn(0f, 1f)
            },
    ) {
        when (val render = placed.render) {
            is OverlayRender.TextBlock ->
                TextBlockContent(render, boxWidthPx = w, density = density)
            is OverlayRender.ImageContent -> Image(
                bitmap = render.bitmap,
                contentDescription = null,
                // The box aspect == the bitmap aspect (computePlacements),
                // so FillBounds maps it 1:1 like the export quad.
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Renders a text block with Compose `Text` primitives, scaled from the
 * canonical block frame to the placement box by the SAME factor the export
 * scales the block bitmap with (boxW / blockW). The lines are the EXACT
 * lines the export bitmap draws ([OverlayBitmapRenderer.wrapTextLines]),
 * rendered one-per-`Text` (maxLines=1) so Compose never re-wraps them —
 * line assignment parity by construction. Line height == font size and the
 * inter-line spacing == 0.22 × fontPx reproduce [OverlayBitmapRenderer
 * .blockSizePx]'s arithmetic; the optional background pill, padding,
 * corner radius, colour and soft shadow match renderTextBlock.
 */
@Composable
private fun TextBlockContent(
    render: OverlayRender.TextBlock,
    boxWidthPx: Float,
    density: Density,
) {
    val layout = render.layout
    val k = if (layout.blockW <= 0) 1f else boxWidthPx / layout.blockW
    val fontSp = with(density) { (layout.fontPx * k).toSp() }
    val lineSpacingDp = with(density) { (layout.lineSpacingPx * k).toDp() }
    val padXDp = with(density) { (layout.padXPx * k).toDp() }
    val padYDp = with(density) { (layout.padYPx * k).toDp() }
    val radiusDp = with(density) { (layout.radiusPx * k).toDp() }
    val hasBg = (layout.bgArgb ushr 24) != 0
    val style = TextStyle(
        color = Color(layout.colorArgb),
        fontSize = fontSp,
        lineHeight = fontSp,
        fontFamily = FontFamily(render.typeface),
        // renderTextBlock draws with a soft shadow (0.08 × fontPx blur,
        // 0.04 × fontPx down, 55% black).
        shadow = Shadow(
            color = Color(0x88000000.toInt()),
            offset = Offset(0f, layout.fontPx * k * 0.04f),
            blurRadius = layout.fontPx * k * 0.08f,
        ),
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (hasBg) {
                    Modifier.background(
                        Color(layout.bgArgb),
                        RoundedCornerShape(radiusDp),
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = padXDp, vertical = padYDp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(lineSpacingDp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            layout.lines.forEach { line ->
                Text(
                    text = line,
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                )
            }
        }
    }
}

private enum class GestureMode { IDLE, MOVE, SCALE, ROTATE, DELETE }

private fun distance(x: Float, y: Float, point: Offset): Float =
    kotlin.math.hypot((point.x - x).toDouble(), (point.y - y).toDouble()).toFloat()

/** Distance between two touch points (two-finger spread — v0.4.0 pinch). */
private fun twoFingerDistance(a: Offset, b: Offset): Float =
    kotlin.math.hypot(
        (b.x - a.x).toDouble(),
        (b.y - a.y).toDouble(),
    ).toFloat()

/** Clockwise-positive screen angle (y-down) of the a→b vector. */
private fun twoFingerAngle(a: Offset, b: Offset): Float =
    Math.toDegrees(
        kotlin.math.atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()).toDouble(),
    ).toFloat()

/** Clockwise-positive screen angle (y-down) of [point] around (x, y). */
private fun angleDeg(x: Float, y: Float, point: Offset): Float =
    Math.toDegrees(
        atan2((point.y - y).toDouble(), (point.x - x).toDouble()).toDouble(),
    ).toFloat()

/*
 * Handle geometry: knob centres sit stemPx beyond the rotated bounding box;
 * the zone radius is resolved from density (24 dp) by the calling scope.
 */
private fun PlacedOverlay.stemPx(zone: Float): Float = zone * 1.1f

private fun PlacedOverlay.rotateHandlePos(zone: Float): Offset =
    toCanvas(Offset(0f, -heightPx / 2f - stemPx(zone)))

private fun PlacedOverlay.scaleHandlePos(): Offset =
    toCanvas(Offset(widthPx / 2f, heightPx / 2f))

private fun PlacedOverlay.deleteHandlePos(zone: Float): Offset =
    toCanvas(Offset(widthPx / 2f, -heightPx / 2f))

private fun PlacedOverlay.isInRotateZone(point: Offset, zone: Float): Boolean =
    distanceTo(rotateHandlePos(zone)) <= zone

private fun PlacedOverlay.isInScaleZone(point: Offset, zone: Float): Boolean =
    distanceTo(scaleHandlePos()) <= zone

private fun PlacedOverlay.isInDeleteZone(point: Offset, zone: Float): Boolean =
    distanceTo(deleteHandlePos(zone)) <= zone

private fun PlacedOverlay.distanceTo(point: Offset): Float =
    kotlin.math.hypot(
        (point.x - centerX).toDouble(),
        (point.y - centerY).toDouble(),
    ).toFloat()

/**
 * Selection chrome: bounds, rotation stem + knob (top-centre), delete chip
 * (top-right) and scale corner (bottom-right). Editor-only — never exported.
 *
 * v0.4.0: [interactive] == false (locked layer) draws ONLY the bounds, in a
 * dimmed accent, without the handles — a locked layer rejects every gesture,
 * so dead handles would only invite taps that do nothing.
 */
private fun DrawScope.drawSelectionChrome(placed: PlacedOverlay, interactive: Boolean) {
    val accent = Color(0xFFF98A0B)
    val zone = 24.dp.toPx()
    val handleR = zone * 0.5f
    val left = placed.centerX - placed.widthPx / 2f
    val top = placed.centerY - placed.heightPx / 2f
    withTransform({
        rotate(degrees = placed.transform.rotationDeg, pivot = Offset(placed.centerX, placed.centerY))
    }) {
        drawRoundRect(
            color = if (interactive) accent else accent.copy(alpha = 0.5f),
            topLeft = Offset(left, top),
            size = Size(placed.widthPx, placed.heightPx),
            cornerRadius = CornerRadius(8f, 8f),
            style = Stroke(width = 2.5f),
        )
    }
    if (!interactive) return
    // Rotation stem + knob (above the top edge centre, rotates with the box).
    val knob = placed.rotateHandlePos(zone)
    val topCenter = placed.toCanvas(Offset(0f, -placed.heightPx / 2f))
    drawLine(
        color = accent,
        start = topCenter,
        end = knob,
        strokeWidth = 2.5f,
    )
    drawCircle(color = Color(0xCC1B1C22), radius = handleR, center = knob)
    drawCircle(color = accent, radius = handleR, center = knob, style = Stroke(width = 2.5f))
    drawLine(accent, knob + Offset(-handleR * 0.35f, -handleR * 0.35f), knob + Offset(handleR * 0.35f, handleR * 0.35f), 3f)
    drawLine(accent, knob + Offset(-handleR * 0.35f, handleR * 0.35f), knob + Offset(handleR * 0.35f, -handleR * 0.35f), 3f)
    // Delete chip (top-right corner).
    val del = placed.deleteHandlePos(zone)
    drawCircle(color = Color(0xCCB3261E), radius = handleR, center = del)
    drawLine(Color.White, del + Offset(-handleR * 0.4f, -handleR * 0.4f), del + Offset(handleR * 0.4f, handleR * 0.4f), 3f)
    drawLine(Color.White, del + Offset(-handleR * 0.4f, handleR * 0.4f), del + Offset(handleR * 0.4f, -handleR * 0.4f), 3f)
    // Scale corner (bottom-right).
    val scl = placed.scaleHandlePos()
    drawCircle(color = Color(0xCC1B1C22), radius = handleR, center = scl)
    drawCircle(color = accent, radius = handleR, center = scl, style = Stroke(width = 2.5f))
    drawCircle(color = accent, radius = handleR * 0.35f, center = scl)
}

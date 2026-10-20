package com.onevideog.editor.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind
import com.onevideog.editor.data.OverlayTransform
import com.onevideog.editor.editor.KeyframeEvaluator
import com.onevideog.editor.editor.OverlayBitmapRenderer
import com.onevideog.editor.editor.OverlayLayout

/** One overlay evaluated at the current playhead, resolved to canvas pixels. */
internal data class PlacedOverlay(
    val overlay: OverlayItem,
    val transform: OverlayTransform,
    val bitmap: ImageBitmap?,
    val centerX: Float,
    val centerY: Float,
    val widthPx: Float,
    val heightPx: Float,
    val selected: Boolean,
)

/**
 * Computes every overlay's placement for [playheadMs] using the SAME pure
 * math the export composition uses ([KeyframeEvaluator] + [OverlayLayout]).
 */
internal fun computePlacements(
    overlays: List<OverlayItem>,
    playheadMs: Long,
    contentAspect: Float,
    contentWidth: Float,
    contentHeight: Float,
    resolveBitmap: (OverlayItem) -> ImageBitmap?,
    selectedId: String?,
): List<PlacedOverlay> {
    if (contentWidth <= 0f || contentHeight <= 0f) return emptyList()
    return overlays
        // v0.3.0: hidden layers render nothing (same rule as the export
        // pipeline — see ClipEffectPlan.overlayWindowsForClip).
        .filter { it.startMs <= playheadMs && playheadMs < it.endMs && it.visible }
        .sortedBy { it.zOrder }
        .mapNotNull { overlay ->
            val transform = KeyframeEvaluator.evaluate(overlay, playheadMs)
            val bitmap = resolveBitmap(overlay) ?: return@mapNotNull null
            val aspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
            val (w, h) = OverlayLayout.previewSize(
                contentWidth, contentHeight, aspect, transform.scale,
            )
            PlacedOverlay(
                overlay = overlay,
                transform = transform,
                bitmap = bitmap,
                centerX = transform.x * contentWidth,
                centerY = transform.y * contentHeight,
                widthPx = w,
                heightPx = h,
                selected = overlay.id == selectedId,
            )
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
 * The live overlay layer.
 *
 * Rendering contract (v0.2.2): when a video clip is active and the player is
 * ready, overlay PIXELS come from the Media3 GL pipeline via
 * `ExoPlayer.setVideoEffects` (see [com.onevideog.editor.editor.ClipEffectsFactory])
 * — the same effect list the export composes. This layer then draws only
 * editor chrome (selection bounds, scale/rotate/delete handles) plus the
 * live preview of the overlay being manipulated. When there is no active
 * clip (overlays over black) or the player is not ready, it falls back to
 * drawing the overlay content here with the same shared math, so overlays
 * are always visible and scrubbing never blanks them.
 *
 * Gestures: tap to select/deselect, drag body to move, corner handle to
 * scale, top handle to rotate (clockwise-positive), delete chip to remove.
 * Edits commit once per gesture so undo stays usable.
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
    renderContentFallback: () -> Boolean,
    onTap: (OverlayItem?) -> Unit,
    onGesture: (OverlayItem, OverlayTransform) -> Unit,
    onGestureEnd: (OverlayItem, OverlayTransform) -> Unit,
    onDelete: (OverlayItem) -> Unit,
) {
    val context = LocalContext.current
    val currentOverlays = rememberUpdatedState(overlays)
    val currentPlayhead = rememberUpdatedState(playheadMs)
    val currentAspect = rememberUpdatedState(contentAspect)
    val currentW = rememberUpdatedState(contentWidthPx)
    val currentH = rememberUpdatedState(contentHeightPx)
    val currentSelected = rememberUpdatedState(selectedOverlayId)
    val currentOverride = rememberUpdatedState(gestureOverride)
    val currentFallback = rememberUpdatedState(renderContentFallback)
    val currentOnTap = rememberUpdatedState(onTap)
    val currentOnGesture = rememberUpdatedState(onGesture)
    val currentOnGestureEnd = rememberUpdatedState(onGestureEnd)
    val currentOnDelete = rememberUpdatedState(onDelete)

    fun resolveBitmap(overlay: OverlayItem): ImageBitmap? = when (overlay.kind) {
        OverlayKind.TEXT -> OverlayBitmapRenderer
            .textBitmap(overlay).asImageBitmap()
        OverlayKind.IMAGE -> OverlayBitmapRenderer
            // v0.2.5: cache-only — the draw/gesture paths must never decode
            // on the render thread and a failed decode must not be retried
            // once per frame. Populated by the pre-warm below (Default
            // dispatcher) and by the shared effects pipeline (same cache).
            .peekImageBitmap(overlay)?.asImageBitmap()
    }

    // Image overlays that failed to decode this session (bounded set): the
    // pre-warm stops retrying them — RENDER_FAIL evidence is already logged
    // by the decode stage — so a broken image cannot stall the render loop.
    // Re-adding the image (new overlay id) retries naturally.
    val failedImageIds = remember { mutableStateOf(emptySet<String>()) }

    // Pre-warm image bitmaps off the UI thread so first paint is smooth;
    // v0.2.5: failures back off instead of retrying every frame.
    LaunchedEffect(overlays) {
        overlays.filter { it.kind == OverlayKind.IMAGE && it.id !in failedImageIds.value }
            .forEach { overlay ->
                val bitmap = kotlinx.coroutines.withContext(
                    kotlinx.coroutines.Dispatchers.Default,
                ) {
                    OverlayBitmapRenderer.imageBitmap(context, overlay)
                }
                if (bitmap == null) {
                    failedImageIds.value = failedImageIds.value + overlay.id
                }
            }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = currentW.value()
                    val h = currentH.value()
                    if (w <= 0f || h <= 0f) return@awaitEachGesture
                    val placements = computePlacements(
                        currentOverlays.value, currentPlayhead.value(), currentAspect.value(),
                        w, h, ::resolveBitmap, currentSelected.value(),
                    )
                    val hit = placements.lastOrNull { it.contains(down.position) }?.overlay
                    val selectedPlacement = placements.firstOrNull { it.selected }
                    var mode = GestureMode.IDLE
                    var base: OverlayTransform? = null
                    var moved = false
                    var gestureTarget: OverlayItem? = null

                    if (hit != null && hit.id == currentSelected.value() &&
                        selectedPlacement != null && !hit.locked
                    ) {
                        // The touched overlay is already selected AND not
                        // locked: check handles. Locked overlays stay
                        // selectable (tap falls through to selection below)
                        // but never start a transform/delete gesture —
                        // v0.3.0 layer lock.
                        // The pointerInput scope is a Density — resolve zones in px.
                        val zoneR = 24.dp.toPx()
                        gestureTarget = hit
                        base = KeyframeEvaluator.evaluate(hit, currentPlayhead.value())
                        mode = when {
                            selectedPlacement.isInDeleteZone(down.position, zoneR) -> GestureMode.DELETE
                            selectedPlacement.isInRotateZone(down.position, zoneR) -> GestureMode.ROTATE
                            selectedPlacement.isInScaleZone(down.position, zoneR) -> GestureMode.SCALE
                            else -> GestureMode.MOVE
                        }
                        currentOnGesture.value(hit, base)
                    }
                    val rotateStart = selectedPlacement?.let {
                        angleDeg(it.centerX, it.centerY, down.position)
                    } ?: 0f
                    val scaleStart = selectedPlacement?.let {
                        distance(it.centerX, it.centerY, down.position)
                    } ?: 1f

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.any { it.pressed }
                        val pan = event.calculatePan()
                        if (base != null && gestureTarget != null && mode != GestureMode.DELETE) {
                            val pointer = event.changes.firstOrNull { it.pressed }?.position
                                ?: down.position
                            val next = when (mode) {
                                GestureMode.MOVE -> base.copy(
                                    x = (base.x + pan.x / w).coerceIn(0f, 1f),
                                    y = (base.y + pan.y / h).coerceIn(0f, 1f),
                                )
                                GestureMode.SCALE -> {
                                    val dist = distance(
                                        selectedPlacement!!.centerX,
                                        selectedPlacement.centerY,
                                        pointer,
                                    )
                                    base.copy(
                                        scale = (base.scale * (dist / scaleStart.coerceAtLeast(1f)))
                                            .coerceIn(0.05f, 1.6f),
                                    )
                                }
                                GestureMode.ROTATE -> {
                                    val angle = angleDeg(
                                        selectedPlacement!!.centerX,
                                        selectedPlacement.centerY,
                                        pointer,
                                    )
                                    base.copy(rotationDeg = base.rotationDeg + (angle - rotateStart))
                                }
                                else -> null
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
                            currentOnGestureEnd.value(target, base.withClampedValues())
                        hit != null ->
                            // Plain tap (no drag) on an overlay: select it.
                            currentOnTap.value(hit)
                        else ->
                            currentOnTap.value(null)
                    }
                }
            },
    ) {
        val override = currentOverride.value()
        val placements = computePlacements(
            overlays = currentOverlays.value,
            playheadMs = currentPlayhead.value(),
            contentAspect = currentAspect.value(),
            contentWidth = size.width,
            contentHeight = size.height,
            resolveBitmap = ::resolveBitmap,
            selectedId = currentSelected.value(),
        ).map { placed ->
            if (override != null && override.first == placed.overlay.id) {
                val t = override.second
                val aspect = placed.bitmap
                    ?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: 1.5f
                val (w2, h2) = OverlayLayout.previewSize(size.width, size.height, aspect, t.scale)
                placed.copy(
                    transform = t,
                    centerX = t.x * size.width,
                    centerY = t.y * size.height,
                    widthPx = w2,
                    heightPx = h2,
                )
            } else {
                placed
            }
        }
        val fallback = currentFallback.value()
        placements.forEach { placed ->
            val isGestured = override != null && override.first == placed.overlay.id
            // Content pixels only when the GL pipeline is not already showing
            // them (no active clip / player not ready) or while this overlay
            // is being manipulated live. Chrome always draws.
            if (fallback || isGestured) {
                drawPlaced(placed)
            }
            if (placed.selected) {
                drawSelectionChrome(placed)
            }
        }
    }
}

private enum class GestureMode { IDLE, MOVE, SCALE, ROTATE, DELETE }

private fun distance(x: Float, y: Float, point: Offset): Float =
    kotlin.math.hypot((point.x - x).toDouble(), (point.y - y).toDouble()).toFloat()

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
 */
private fun DrawScope.drawSelectionChrome(placed: PlacedOverlay) {
    val accent = Color(0xFFF98A0B)
    val zone = 24.dp.toPx()
    val handleR = zone * 0.5f
    val left = placed.centerX - placed.widthPx / 2f
    val top = placed.centerY - placed.heightPx / 2f
    withTransform({
        rotate(degrees = placed.transform.rotationDeg, pivot = Offset(placed.centerX, placed.centerY))
    }) {
        drawRoundRect(
            color = accent,
            topLeft = Offset(left, top),
            size = Size(placed.widthPx, placed.heightPx),
            cornerRadius = CornerRadius(8f, 8f),
            style = Stroke(width = 2.5f),
        )
    }
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

private fun DrawScope.drawPlaced(placed: PlacedOverlay) {
    val bitmap = placed.bitmap ?: return
    val w = placed.widthPx.roundToInt().coerceAtLeast(1)
    val h = placed.heightPx.roundToInt().coerceAtLeast(1)
    val left = (placed.centerX - w / 2f).roundToInt()
    val top = (placed.centerY - h / 2f).roundToInt()
    withTransform({
        rotate(
            degrees = placed.transform.rotationDeg,
            pivot = Offset(placed.centerX, placed.centerY),
        )
    }) {
        drawImage(
            image = bitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bitmap.width, bitmap.height),
            dstOffset = IntOffset(left, top),
            dstSize = IntSize(w, h),
            alpha = placed.transform.opacity.coerceIn(0f, 1f),
            filterQuality = FilterQuality.Low,
        )
    }
}

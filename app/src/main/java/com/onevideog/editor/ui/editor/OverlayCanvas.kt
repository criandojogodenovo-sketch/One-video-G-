package com.onevideog.editor.ui.editor

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlin.math.abs
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
        .filter { it.startMs <= playheadMs && playheadMs < it.endMs }
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
    val dx = point.x - centerX
    val dy = point.y - centerY
    val rad = Math.toRadians(transform.rotationDeg.toDouble())
    // Inverse-rotate the point into the overlay's axis-aligned space.
    val localX = (dx * cos(-rad) - dy * sin(-rad)).toFloat()
    val localY = (dx * sin(-rad) + dy * cos(-rad)).toFloat()
    return abs(localX) <= widthPx / 2f && abs(localY) <= heightPx / 2f
}

/**
 * The live overlay layer: draws placements and handles direct manipulation
 * (tap to select, one-finger drag to move, pinch to scale, two-finger twist
 * to rotate). Gesture edits commit once per gesture so undo stays usable.
 *
 * Placement math is identical to the export composition — see
 * docs/PREVIEW_EXPORT_PARITY.md.
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
    onTap: (OverlayItem?) -> Unit,
    onGesture: (OverlayItem, OverlayTransform) -> Unit,
    onGestureEnd: (OverlayItem, OverlayTransform) -> Unit,
) {
    val context = LocalContext.current
    val currentOverlays = rememberUpdatedState(overlays)
    val currentPlayhead = rememberUpdatedState(playheadMs)
    val currentAspect = rememberUpdatedState(contentAspect)
    val currentW = rememberUpdatedState(contentWidthPx)
    val currentH = rememberUpdatedState(contentHeightPx)
    val currentSelected = rememberUpdatedState(selectedOverlayId)
    val currentOverride = rememberUpdatedState(gestureOverride)
    val currentOnTap = rememberUpdatedState(onTap)
    val currentOnGesture = rememberUpdatedState(onGesture)
    val currentOnGestureEnd = rememberUpdatedState(onGestureEnd)

    fun resolveBitmap(overlay: OverlayItem): ImageBitmap? = when (overlay.kind) {
        OverlayKind.TEXT -> OverlayBitmapRenderer
            .textBitmap(overlay).asImageBitmap()
        OverlayKind.IMAGE -> OverlayBitmapRenderer
            .imageBitmap(context, overlay)?.asImageBitmap()
    }

    // Pre-warm image bitmaps off the UI thread so first paint is smooth.
    LaunchedEffect(overlays) {
        overlays.filter { it.kind == OverlayKind.IMAGE }.forEach { overlay ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                OverlayBitmapRenderer.imageBitmap(context, overlay)
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
                    val hit = computePlacements(
                        currentOverlays.value, currentPlayhead.value(), currentAspect.value(),
                        w, h, ::resolveBitmap, currentSelected.value(),
                    ).lastOrNull { it.contains(down.position) }?.overlay
                    var moved = false
                    var base: OverlayTransform? = null
                    if (hit != null && hit.id == currentSelected.value()) {
                        base = KeyframeEvaluator.evaluate(hit, currentPlayhead.value())
                        currentOnGesture.value(hit, base)
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.any { it.pressed }
                        val zoom = event.calculateZoom()
                        val rotation = event.calculateRotation()
                        val pan = event.calculatePan()
                        if (base != null &&
                            (zoom != 1f || rotation != 0f || pan != Offset.Zero)
                        ) {
                            moved = true
                            val next = base.copy(
                                x = (base.x + pan.x / w).coerceIn(0f, 1f),
                                y = (base.y + pan.y / h).coerceIn(0f, 1f),
                                scale = (base.scale * zoom).coerceIn(0.05f, 1.6f),
                                rotationDeg = base.rotationDeg + rotation,
                            )
                            base = next
                            currentOnGesture.value(hit!!, next)
                        }
                        event.changes.forEach { it.consume() }
                        if (!pressed) break
                    }
                    if (hit != null && base != null) {
                        if (moved) {
                            currentOnGestureEnd.value(hit, base.withClampedValues())
                        } else {
                            currentOnTap.value(hit)
                        }
                    } else {
                        currentOnTap.value(null)
                    }
                }
            },
    ) {
        val placements = computePlacements(
            overlays = currentOverlays.value,
            playheadMs = currentPlayhead.value(),
            contentAspect = currentAspect.value(),
            contentWidth = size.width,
            contentHeight = size.height,
            resolveBitmap = ::resolveBitmap,
            selectedId = currentSelected.value(),
        ).map { placed ->
            val override = currentOverride.value()
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
        placements.forEach { drawPlaced(it) }
    }
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
        if (placed.selected) {
            drawRoundRect(
                color = Color(0xFFF98A0B),
                topLeft = Offset(left.toFloat(), top.toFloat()),
                size = Size(w.toFloat(), h.toFloat()),
                cornerRadius = CornerRadius(8f, 8f),
                style = Stroke(width = 3f),
            )
        }
    }
}

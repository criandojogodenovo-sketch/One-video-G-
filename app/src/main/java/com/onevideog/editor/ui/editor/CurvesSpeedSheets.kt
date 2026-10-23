package com.onevideog.editor.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.onevideog.editor.R
import com.onevideog.editor.data.Curve
import com.onevideog.editor.data.CurvePoint
import com.onevideog.editor.data.SpeedPoint
import com.onevideog.editor.editor.CurveMath
import com.onevideog.editor.editor.EditorViewModel
import com.onevideog.editor.editor.SpeedRampPlanner
import com.onevideog.editor.ui.home.formatDuration
import kotlin.math.abs

/**
 * v0.7.0 sheets for Fase J (colour curves — J5) and Fase K (speed ramps — K6).
 *
 * Both panels only WRITE through the ViewModel (one commit per gesture —
 * the draft lives in the panel state and the commit happens on drag end /
 * preset tap, so a drag never re-prepares the decoder per frame); the
 * preview and the export derive everything from the committed project state
 * through the shared pipeline — no panel-level rendering decisions exist.
 */

private const val MAX_CURVE_POINTS = 8

private const val HIT_RADIUS_PX = 40f

// ---------------------------------------------------------------- Curves (J5)

/**
 * J5 — the colour-curves editor for the selected (or playhead) video clip:
 * one tab per channel (Master/R/G/B), a canvas with draggable control
 * points (tap adds, double-tap removes, up to [MAX_CURVE_POINTS]), the four
 * presets, and a "no curves" reset. Edits commit ONE
 * [EditorViewModel.setClipCurveChannel] call per gesture end.
 */
@Composable
internal fun ColorCurvesPanel(viewModel: EditorViewModel, clip: com.onevideog.editor.data.Clip) {
    val state by viewModel.ui.collectAsState()
    val fresh = state.project?.clips?.firstOrNull { it.id == clip.id } ?: clip
    val linear = Curve(listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)))
    var channel by remember { mutableStateOf(CurveMath.Channel.MASTER) }

    SheetTitle(stringResource(R.string.curves_title))
    InfoLine(stringResource(R.string.curves_hint))

    // Channel tabs.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CurveMath.Channel.entries.forEach { ch ->
            FilterChip(
                selected = channel == ch,
                onClick = { channel = ch },
                label = { Text(channelLabel(ch)) },
            )
        }
    }

    val currentCurve = when (channel) {
        CurveMath.Channel.MASTER -> fresh.curves?.master ?: linear
        CurveMath.Channel.RED -> fresh.curves?.red ?: linear
        CurveMath.Channel.GREEN -> fresh.curves?.green ?: linear
        CurveMath.Channel.BLUE -> fresh.curves?.blue ?: linear
    }
    // Draft points while dragging: committed once per gesture (one undo step,
    // one decoder re-prepare) — never per frame.
    var draft by remember(fresh.id, channel, currentCurve) {
        mutableStateOf(currentCurve.points)
    }
    var dragging by remember(fresh.id, channel, currentCurve) { mutableStateOf(-1) }

    CurveCanvas(
        points = draft,
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        onDragStart = { nx, ny ->
            // Nearest point within the hit radius becomes the drag target.
            var best = -1
            var bestDist = Float.MAX_VALUE
            draft.forEachIndexed { i, p ->
                val dx = (p.x - nx)
                val dy = (p.y - ny)
                val d = dx * dx + dy * dy
                if (d < bestDist) {
                    bestDist = d
                    best = i
                }
            }
            dragging = if (best >= 0 && bestDist < 0.18f) best else -1
        },
        onDrag = { nx, ny ->
            if (dragging >= 0 && dragging < draft.size) {
                val moved = draft.toMutableList()
                val clampedY = ny.coerceIn(0f, 1f)
                // Endpoints stay pinned to the plot edges on x.
                val isEdge = dragging == 0 || dragging == draft.lastIndex
                val clampedX = if (isEdge) moved[dragging].x else nx.coerceIn(0f, 1f)
                moved[dragging] = CurvePoint(clampedX, clampedY)
                draft = moved.sortedBy { it.x }
            }
        },
        onDragEnd = {
            if (dragging >= 0) {
                dragging = -1
                CurveMath.sanitize(draft)?.let {
                    viewModel.setClipCurveChannel(fresh.id, channel, it)
                }
            }
        },
        onTap = { nx, ny ->
            if (draft.size < MAX_CURVE_POINTS) {
                draft = (draft + CurvePoint(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f)))
                    .sortedBy { it.x }
                CurveMath.sanitize(draft)?.let {
                    viewModel.setClipCurveChannel(fresh.id, channel, it)
                }
            }
        },
        onDoubleTap = { nx, ny ->
            if (draft.size > 2) {
                var best = -1
                var bestDist = Float.MAX_VALUE
                draft.forEachIndexed { i, p ->
                    val d = (p.x - nx) * (p.x - nx) + (p.y - ny) * (p.y - ny)
                    if (d < bestDist) {
                        bestDist = d
                        best = i
                    }
                }
                if (best >= 0 && bestDist < 0.18f) {
                    val remaining = draft.toMutableList().apply { removeAt(best) }
                    draft = remaining
                    CurveMath.sanitize(remaining)?.let {
                        viewModel.setClipCurveChannel(fresh.id, channel, it)
                    }
                }
            }
        },
    )

    // Presets (apply to the current channel) + clear-all.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = false,
            onClick = {
                val preset = CurveMath.Presets.LINEAR
                draft = preset
                viewModel.setClipCurveChannel(fresh.id, channel, CurveMath.sanitize(preset) ?: Curve(preset))
            },
            label = { Text(stringResource(R.string.curves_preset_linear)) },
        )
        FilterChip(
            selected = false,
            onClick = {
                val preset = CurveMath.Presets.S_CURVE
                draft = preset
                viewModel.setClipCurveChannel(fresh.id, channel, CurveMath.sanitize(preset) ?: Curve(preset))
            },
            label = { Text(stringResource(R.string.curves_preset_scurve)) },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = false,
            onClick = {
                val preset = CurveMath.Presets.FADE
                draft = preset
                viewModel.setClipCurveChannel(fresh.id, channel, CurveMath.sanitize(preset) ?: Curve(preset))
            },
            label = { Text(stringResource(R.string.curves_preset_fade)) },
        )
        FilterChip(
            selected = false,
            onClick = {
                val preset = CurveMath.Presets.CINEMATIC
                draft = preset
                viewModel.setClipCurveChannel(fresh.id, channel, CurveMath.sanitize(preset) ?: Curve(preset))
            },
            label = { Text(stringResource(R.string.curves_preset_cinematic)) },
        )
    }
    OutlinedButton(
        onClick = {
            draft = linear.points
            viewModel.setClipCurves(fresh.id, null)
        },
    ) {
        Text(stringResource(R.string.curves_clear))
    }
    Text(
        stringResource(R.string.curves_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun channelLabel(channel: CurveMath.Channel): String = stringResource(
    when (channel) {
        CurveMath.Channel.MASTER -> R.string.curves_channel_master
        CurveMath.Channel.RED -> R.string.curves_channel_red
        CurveMath.Channel.GREEN -> R.string.curves_channel_green
        CurveMath.Channel.BLUE -> R.string.curves_channel_blue
    },
)

/**
 * The curve plot: 0..1 both axes (y up), the identity diagonal as reference,
 * the spline polyline through the draft points and the points themselves.
 * Drag/tap/double-tap handlers receive NORMALIZED coordinates.
 */
@Composable
private fun CurveCanvas(
    points: List<CurvePoint>,
    modifier: Modifier,
    onDragStart: (Float, Float) -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onTap: (Float, Float) -> Unit,
    onDoubleTap: (Float, Float) -> Unit,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val curveColor = MaterialTheme.colorScheme.primary
    val pointColor = MaterialTheme.colorScheme.tertiary
    Canvas(
        modifier
            // Gestures: drag moves a point; tap adds; double-tap removes.
            // Attached directly to the drawing node — one surface, no overlay.
            .pointerInput(points) {
                detectTapGestures(
                    onTap = { offset -> onTap(offset.x / size.width, 1f - offset.y / size.height) },
                    onDoubleTap = { offset ->
                        onDoubleTap(offset.x / size.width, 1f - offset.y / size.height)
                    },
                )
            }
            .pointerInput(points) {
                detectDragGestures(
                    onDragStart = { offset ->
                        onDragStart(offset.x / size.width, 1f - offset.y / size.height)
                    },
                    onDrag = { change, _ ->
                        onDrag(change.position.x / size.width, 1f - change.position.y / size.height)
                    },
                    onDragEnd = { onDragEnd() },
                )
            },
    ) {
        val w = size.width
        val h = size.height
        fun toPx(p: CurvePoint) = Offset(p.x * w, (1f - p.y) * h)
        // Grid (quarters) + frame.
        for (i in 1..3) {
            drawLine(gridColor, Offset(w * i / 4f, 0f), Offset(w * i / 4f, h), strokeWidth = 1f)
            drawLine(gridColor, Offset(0f, h * i / 4f), Offset(w, h * i / 4f), strokeWidth = 1f)
        }
        // Identity diagonal.
        drawLine(gridColor, Offset(0f, h), Offset(w, 0f), strokeWidth = 1.5f)
        // Spline polyline through the LUT (the EXACT mapping the shader uses).
        if (CurveMath.isValid(Curve(points))) {
            val lut = CurveMath.buildLut(Curve(points))
            val path = Path()
            for (i in lut.indices step 4) {
                val x = i.toFloat() / (CurveMath.LUT_SIZE - 1)
                val y = lut[i]
                val p = Offset(x * w, (1f - y) * h)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            val lastIdx = CurveMath.LUT_SIZE - 1
            path.lineTo(w, (1f - lut[lastIdx]) * h)
            drawPath(path, curveColor, style = Stroke(width = 3f))
        }
        // Control points.
        points.forEach { p ->
            drawCircle(pointColor, radius = 7f, center = toPx(p))
        }
    }
}

// ----------------------------------------------------------- Speed ramps (K6)

/**
 * K6 — the speed-ramp editor for the selected (or playhead) video clip: a
 * velocity-vs-time canvas (x = clip-local constant-speed basis time, y =
 * factor 0.1..4.0), tap-to-add / drag-to-shape / double-tap-to-remove
 * points, the five presets, and the segment/duration evidence line. Edits
 * commit ONE [EditorViewModel.setSpeedRamps] call per gesture end.
 */
@Composable
internal fun SpeedRampPanel(viewModel: EditorViewModel, clip: com.onevideog.editor.data.Clip) {
    val state by viewModel.ui.collectAsState()
    val fresh = state.project?.clips?.firstOrNull { it.id == clip.id } ?: clip
    val domainEnd = fresh.effectiveDurationMs.coerceAtLeast(1)

    SheetTitle(stringResource(R.string.ramp_title))
    InfoLine(stringResource(R.string.ramp_hint))

    var draft by remember(fresh.id, fresh.speedRamps) { mutableStateOf(fresh.speedRamps) }
    var dragging by remember(fresh.id, fresh.speedRamps) { mutableStateOf(-1) }

    val previewClip = fresh.copy(speedRamps = draft)
    val segments = SpeedRampPlanner.planSegments(previewClip).size
    val outputDur = SpeedRampPlanner.outputDurationMs(previewClip)

    RampCanvas(
        points = draft,
        domainEndMs = domainEnd,
        baseSpeed = fresh.speed,
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        onDragStart = { t, f ->
            var best = -1
            var bestDist = Float.MAX_VALUE
            draft.forEachIndexed { i, p ->
                val dt = (p.localTimeMs - t) / domainEnd.toFloat()
                val df = (p.factor - f) / (SpeedRampPlanner.MAX_FACTOR - SpeedRampPlanner.MIN_FACTOR)
                val d = dt * dt + df * df
                if (d < bestDist) {
                    bestDist = d
                    best = i
                }
            }
            dragging = if (best >= 0 && bestDist < 0.14f) best else -1
        },
        onDrag = { t, f ->
            if (dragging >= 0 && dragging < draft.size) {
                val moved = draft.toMutableList()
                val clampedT = t.coerceIn(0f, domainEnd.toFloat()).toLong()
                val clampedF = f.coerceIn(SpeedRampPlanner.MIN_FACTOR, SpeedRampPlanner.MAX_FACTOR)
                moved[dragging] = SpeedPoint(clampedT, clampedF)
                draft = moved.distinctBy { it.localTimeMs }.sortedBy { it.localTimeMs }
            }
        },
        onDragEnd = {
            if (dragging >= 0) {
                dragging = -1
                viewModel.setSpeedRamps(fresh.id, draft)
            }
        },
        onTap = { t, f ->
            if (draft.size < SpeedRampPlanner.MAX_SEGMENTS) {
                val point = SpeedPoint(
                    t.coerceIn(0f, domainEnd.toFloat()).toLong(),
                    f.coerceIn(SpeedRampPlanner.MIN_FACTOR, SpeedRampPlanner.MAX_FACTOR),
                )
                draft = (draft + point).distinctBy { it.localTimeMs }.sortedBy { it.localTimeMs }
                viewModel.setSpeedRamps(fresh.id, draft)
            }
        },
        onDoubleTap = { t, f ->
            if (draft.size > 2) {
                var best = -1
                var bestDist = Float.MAX_VALUE
                draft.forEachIndexed { i, p ->
                    val dt = (p.localTimeMs - t) / domainEnd.toFloat()
                    val df = (p.factor - f) / (SpeedRampPlanner.MAX_FACTOR - SpeedRampPlanner.MIN_FACTOR)
                    val d = dt * dt + df * df
                    if (d < bestDist) {
                        bestDist = d
                        best = i
                    }
                }
                if (best >= 0 && bestDist < 0.14f) {
                    val remaining = draft.toMutableList().apply { removeAt(best) }
                    draft = remaining
                    viewModel.setSpeedRamps(fresh.id, remaining)
                }
            }
        },
    )

    Text(
        stringResource(
            R.string.ramp_segments,
            segments,
            SpeedRampPlanner.MAX_SEGMENTS,
        ),
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        stringResource(
            R.string.ramp_duration,
            formatDuration(fresh.effectiveDurationMs),
            formatDuration(outputDur),
        ),
        color = MaterialTheme.colorScheme.onSurface,
    )

    // Presets: Normal clears the ramp; the others are constant-factor ramps.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = draft.isEmpty(),
            onClick = {
                draft = emptyList()
                viewModel.setSpeedRamps(fresh.id, emptyList())
            },
            label = { Text(stringResource(R.string.ramp_preset_normal)) },
        )
        FilterChip(
            selected = false,
            onClick = { applyConstantRamp({ draft = it }, fresh, viewModel, 0.5f, domainEnd) },
            label = { Text(stringResource(R.string.ramp_preset_slow)) },
        )
        FilterChip(
            selected = false,
            onClick = { applyConstantRamp({ draft = it }, fresh, viewModel, 0.25f, domainEnd) },
            label = { Text(stringResource(R.string.ramp_preset_ultra)) },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = false,
            onClick = { applyConstantRamp({ draft = it }, fresh, viewModel, 0.1f, domainEnd) },
            label = { Text(stringResource(R.string.ramp_preset_bullet)) },
        )
        FilterChip(
            selected = false,
            onClick = { applyConstantRamp({ draft = it }, fresh, viewModel, 2f, domainEnd) },
            label = { Text(stringResource(R.string.ramp_preset_fast)) },
        )
    }
    Text(
        stringResource(R.string.ramp_note, SpeedRampPlanner.MAX_SEGMENTS),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun applyConstantRamp(
    setDraft: (List<SpeedPoint>) -> Unit,
    clip: com.onevideog.editor.data.Clip,
    viewModel: EditorViewModel,
    factor: Float,
    domainEnd: Long,
) {
    val ramp = listOf(SpeedPoint(0, factor), SpeedPoint(domainEnd, factor))
    setDraft(ramp)
    viewModel.setSpeedRamps(clip.id, ramp)
}

/** The velocity plot: x = time (0..domainEnd), y = factor (0.1..4.0, linear). */
@Composable
private fun RampCanvas(
    points: List<SpeedPoint>,
    domainEndMs: Long,
    baseSpeed: Float,
    modifier: Modifier,
    onDragStart: (Float, Float) -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onTap: (Float, Float) -> Unit,
    onDoubleTap: (Float, Float) -> Unit,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val lineColor = MaterialTheme.colorScheme.primary
    val pointColor = MaterialTheme.colorScheme.tertiary
    val baseColor = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
    Canvas(
        modifier
            .pointerInput(points, domainEndMs) {
                detectTapGestures(
                    onTap = { offset ->
                        onTap(
                            offset.x / size.width * domainEndMs,
                            SpeedRampPlanner.MAX_FACTOR -
                                (offset.y / size.height) *
                                (SpeedRampPlanner.MAX_FACTOR - SpeedRampPlanner.MIN_FACTOR),
                        )
                    },
                    onDoubleTap = { offset ->
                        onDoubleTap(
                            offset.x / size.width * domainEndMs,
                            SpeedRampPlanner.MAX_FACTOR -
                                (offset.y / size.height) *
                                (SpeedRampPlanner.MAX_FACTOR - SpeedRampPlanner.MIN_FACTOR),
                        )
                    },
                )
            }
            .pointerInput(points, domainEndMs) {
                detectDragGestures(
                    onDragStart = { offset ->
                        onDragStart(
                            offset.x / size.width * domainEndMs,
                            SpeedRampPlanner.MAX_FACTOR -
                                (offset.y / size.height) *
                                (SpeedRampPlanner.MAX_FACTOR - SpeedRampPlanner.MIN_FACTOR),
                        )
                    },
                    onDrag = { change, _ ->
                        onDrag(
                            change.position.x / size.width * domainEndMs,
                            SpeedRampPlanner.MAX_FACTOR -
                                (change.position.y / size.height) *
                                (SpeedRampPlanner.MAX_FACTOR - SpeedRampPlanner.MIN_FACTOR),
                        )
                    },
                    onDragEnd = { onDragEnd() },
                )
            },
    ) {
        val w = size.width
        val h = size.height
        val fMin = SpeedRampPlanner.MIN_FACTOR
        val fMax = SpeedRampPlanner.MAX_FACTOR
        fun yOf(f: Float): Float = (1f - (f - fMin) / (fMax - fMin)) * h
        fun xOf(t: Long): Float = t.toFloat() / domainEndMs * w
        // Quarters grid + the 1.0x reference line.
        for (i in 1..3) {
            drawLine(gridColor, Offset(w * i / 4f, 0f), Offset(w * i / 4f, h), strokeWidth = 1f)
        }
        drawLine(baseColor, Offset(0f, yOf(baseSpeed)), Offset(w, yOf(baseSpeed)), strokeWidth = 1.5f)
        // The ramp polyline: clamp semantics before the first / after the last point.
        if (points.isEmpty()) {
            drawLine(gridColor, Offset(0f, yOf(baseSpeed)), Offset(w, yOf(baseSpeed)), strokeWidth = 2f)
        } else {
            val path = Path()
            path.moveTo(0f, yOf(points.first().factor))
            points.forEach { p -> path.lineTo(xOf(p.localTimeMs), yOf(p.factor)) }
            path.lineTo(w, yOf(points.last().factor))
            drawPath(path, lineColor, style = Stroke(width = 3f))
            points.forEach { p ->
                drawCircle(pointColor, radius = 7f, center = Offset(xOf(p.localTimeMs), yOf(p.factor)))
            }
        }
    }
}

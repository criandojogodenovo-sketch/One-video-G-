package com.onevideog.editor.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.onevideog.editor.R
import com.onevideog.editor.data.Interpolation
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind
import com.onevideog.editor.data.TextAnimPreset
import com.onevideog.editor.data.TextAnimUnit
import com.onevideog.editor.data.TextFontFamily
import com.onevideog.editor.editor.EditorViewModel
import com.onevideog.editor.editor.ExpandedOverlays
import com.onevideog.editor.editor.KeyframeEditing
import com.onevideog.editor.editor.KeyframeEvaluator
import com.onevideog.editor.editor.OverlayBitmapRenderer
import com.onevideog.editor.editor.OverlayLayout
import com.onevideog.editor.editor.TextSegmentPlanner
import com.onevideog.editor.ui.home.formatDuration
import kotlin.math.roundToInt

private val TEXT_COLORS = listOf(
    0xFFFFFFFF.toInt(), 0xFF111111.toInt(), 0xFFF98A0B.toInt(),
    0xFFE2574C.toInt(), 0xFF4C9BE2.toInt(), 0xFF59C979.toInt(),
)
private val BG_COLORS = listOf(
    0x00000000, 0xCC111111.toInt(), 0xCCFFFFFF.toInt(), 0xCCF98A0B.toInt(),
)

/**
 * Unified overlay editor for text and image overlays: style, transform,
 * timing and keyframes. The panel edits the selected overlay of [kind]; when
 * none is selected it offers creation plus the list of existing ones.
 */
@Composable
internal fun OverlayEditorPanel(viewModel: EditorViewModel, kind: OverlayKind) {
    val state by viewModel.ui.collectAsState()
    val project = state.project ?: return
    val selected = project.overlays.firstOrNull {
        it.id == state.selectedOverlayId && it.kind == kind
    }

    if (selected != null) {
        OverlayDetailEditor(viewModel, selected)
    } else {
        SheetTitle(
            stringResource(if (kind == OverlayKind.TEXT) R.string.text_add else R.string.image_add),
        )
        if (kind == OverlayKind.TEXT) {
            TextCreateSection(viewModel)
        } else {
            ImageCreateSection(viewModel)
        }
        val others = project.overlays.filter { it.kind == kind }
        if (others.isNotEmpty()) {
            Text(
                stringResource(R.string.overlay_existing),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            others.forEach { overlay ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (overlay.kind) {
                            OverlayKind.TEXT -> overlay.text
                            OverlayKind.IMAGE -> stringResource(R.string.timeline_image_marker)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                // v0.5.1: unified exclusive selection.
                                viewModel.selectTimelineClip(
                                    if (overlay.isText) {
                                        com.onevideog.editor.editor.ClipKind.TEXT
                                    } else {
                                        com.onevideog.editor.editor.ClipKind.IMAGE
                                    },
                                    overlay.id,
                                )
                            },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        formatDuration(overlay.startMs) + "–" + formatDuration(overlay.endMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { viewModel.removeOverlay(overlay.id) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.overlay_remove),
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------ creation

@Composable
private fun TextCreateSection(viewModel: EditorViewModel) {
    var text by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(TEXT_COLORS.first()) }
    var bg by remember { mutableStateOf(BG_COLORS.first()) }
    var family by remember { mutableStateOf(TextFontFamily.SANS) }
    var bold by remember { mutableStateOf(true) }
    var italic by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(R.string.text_hint)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        stringResource(R.string.text_color),
        color = MaterialTheme.colorScheme.onSurface,
    )
    ColorSwatchRow(TEXT_COLORS, color) { color = it }
    Text(
        stringResource(R.string.text_background),
        color = MaterialTheme.colorScheme.onSurface,
    )
    ColorSwatchRow(BG_COLORS, bg) { bg = it }
    Text(
        stringResource(R.string.text_font),
        color = MaterialTheme.colorScheme.onSurface,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextFontFamily.entries.forEach { f ->
            FilterChip(
                selected = family == f,
                onClick = { family = f },
                label = {
                    Text(
                        stringResource(
                            when (f) {
                                TextFontFamily.SANS -> R.string.font_sans
                                TextFontFamily.SERIF -> R.string.font_serif
                                TextFontFamily.MONO -> R.string.font_mono
                                TextFontFamily.CONDENSED -> R.string.font_condensed
                            },
                        ),
                    )
                },
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = bold, onClick = { bold = !bold }, label = { Text("B") })
        FilterChip(selected = italic, onClick = { italic = !italic }, label = { Text("I") })
    }
    Button(
        onClick = {
            viewModel.addTextOverlay(
                text = text.trim(),
                colorArgb = color,
                fontFamily = family.id,
                bold = bold,
                italic = italic,
                sizeFrac = 0.07f,
                bgArgb = bg,
            )
            text = ""
        },
        enabled = text.isNotBlank(),
    ) {
        Text(stringResource(R.string.text_add))
    }
}

@Composable
private fun ImageCreateSection(viewModel: EditorViewModel) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.addImageOverlay(uri)
    }
    Text(
        stringResource(R.string.image_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = { picker.launch(arrayOf("image/*")) }) {
        Text(stringResource(R.string.image_pick))
    }
}

// ------------------------------------------------------------ detail editor

@Composable
private fun OverlayDetailEditor(viewModel: EditorViewModel, overlay: OverlayItem) {
    SheetTitle(
        stringResource(if (overlay.isText) R.string.text_edit else R.string.image_edit),
    )
    if (overlay.locked) {
        // v0.3.0 layer lock: the ViewModel rejects every edit for a locked
        // layer — show WHY the controls do nothing instead of a dead panel.
        Text(
            stringResource(R.string.layer_locked_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (overlay.isText) {
            TextContentSection(viewModel, overlay)
        }
        TransformSection(viewModel, overlay)
        TimingSection(viewModel, overlay)
        KeyframeSection(viewModel, overlay)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.bringOverlayToFront(overlay.id) }) {
                Icon(Icons.Filled.VerticalAlignTop, contentDescription = null)
                Text(stringResource(R.string.overlay_front))
            }
            OutlinedButton(onClick = { viewModel.removeOverlay(overlay.id) }) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Text(stringResource(R.string.overlay_remove))
            }
        }
    }
}

@Composable
private fun TextContentSection(viewModel: EditorViewModel, overlay: OverlayItem) {
    var text by remember(overlay.id, overlay.text) { mutableStateOf(overlay.text) }
    // Debounced commit so typing feels live without flooding undo history.
    LaunchedEffect(text) {
        if (text != overlay.text) {
            kotlinx.coroutines.delay(500)
            viewModel.updateOverlay(overlay.id) { it.copy(text = text) }
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(R.string.text_hint)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        stringResource(R.string.text_color),
        color = MaterialTheme.colorScheme.onSurface,
    )
    ColorSwatchRow(TEXT_COLORS, overlay.colorArgb) { c ->
        viewModel.updateOverlay(overlay.id) { it.copy(colorArgb = c) }
    }
    Text(
        stringResource(R.string.text_background),
        color = MaterialTheme.colorScheme.onSurface,
    )
    ColorSwatchRow(BG_COLORS, overlay.bgArgb) { c ->
        viewModel.updateOverlay(overlay.id) { it.copy(bgArgb = c) }
    }
    Text(
        stringResource(R.string.text_font),
        color = MaterialTheme.colorScheme.onSurface,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextFontFamily.entries.forEach { f ->
            FilterChip(
                selected = TextFontFamily.fromId(overlay.fontFamily) == f,
                onClick = { viewModel.updateOverlay(overlay.id) { it.copy(fontFamily = f.id) } },
                label = {
                    Text(
                        stringResource(
                            when (f) {
                                TextFontFamily.SANS -> R.string.font_sans
                                TextFontFamily.SERIF -> R.string.font_serif
                                TextFontFamily.MONO -> R.string.font_mono
                                TextFontFamily.CONDENSED -> R.string.font_condensed
                            },
                        ),
                    )
                },
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = overlay.bold,
            onClick = { viewModel.updateOverlay(overlay.id) { it.copy(bold = !it.bold) } },
            label = { Text("B") },
        )
        FilterChip(
            selected = overlay.italic,
            onClick = { viewModel.updateOverlay(overlay.id) { it.copy(italic = !it.italic) } },
            label = { Text("I") },
        )
    }
    var sizeFrac by remember(overlay.id, overlay.sizeFrac) {
        mutableFloatStateOf(overlay.sizeFrac)
    }
    Text(
        stringResource(R.string.text_size, (sizeFrac * 100).roundToInt()),
        color = MaterialTheme.colorScheme.onSurface,
    )
    Slider(
        value = sizeFrac,
        onValueChange = { sizeFrac = it },
        onValueChangeFinished = {
            // Keep the visual size stable: scale must grow with the font.
            val ratio = sizeFrac / overlay.sizeFrac.coerceAtLeast(0.01f)
            viewModel.updateOverlay(overlay.id) {
                it.copy(sizeFrac = sizeFrac, transform = it.transform.copy(scale = it.transform.scale * ratio))
            }
        },
        valueRange = 0.02f..0.2f,
    )
    TextAnimationSection(viewModel, overlay)
}

/**
 * v0.5.0 (G6): per-word / per-letter entrance animation controls. The
 * selection is overlay-level state — segment expansion happens only at
 * render time, so changing it never rebuilds the preview decoder.
 * When the text would produce more than [ExpandedOverlays.MAX_SEGMENTS]
 * segments for the chosen unit, the UI warns (WORD is the fallback the
 * expansion applies automatically; WORD overflow disables the animation).
 */
@Composable
private fun TextAnimationSection(viewModel: EditorViewModel, overlay: OverlayItem) {
    val anim = overlay.textAnimation
    Text(
        stringResource(R.string.anim_title),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 6.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextAnimPreset.entries.forEach { preset ->
            FilterChip(
                selected = anim.preset == preset,
                onClick = {
                    viewModel.setTextAnimation(
                        overlay.id,
                        anim.copy(preset = preset),
                    )
                },
                label = {
                    Text(
                        stringResource(
                            when (preset) {
                                TextAnimPreset.NONE -> R.string.anim_preset_none
                                TextAnimPreset.FADE -> R.string.anim_preset_fade
                                TextAnimPreset.SLIDE_UP -> R.string.anim_preset_slide_up
                                TextAnimPreset.POP -> R.string.anim_preset_pop
                                TextAnimPreset.TYPEWRITER -> R.string.anim_preset_typewriter
                            },
                        ),
                    )
                },
            )
        }
    }
    if (anim.preset == TextAnimPreset.NONE) return

    // Segment-count guard (G5): the same pure planner the render pipelines
    // use, with the production measurer (Paint) — a warning ONLY; the
    // expansion itself enforces the hard limit with the WORD fallback.
    val metrics = OverlayBitmapRenderer.textMetrics(overlay)
    val measurer = remember(overlay.fontFamily, overlay.bold, overlay.italic, overlay.sizeFrac) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = metrics.fontPx
            typeface = OverlayBitmapRenderer.typefaceFor(
                overlay.fontFamily, overlay.bold, overlay.italic,
            )
        }
        TextSegmentPlanner.Measurer { paint.measureText(it) }
    }
    val letterCount = remember(overlay.text) {
        TextSegmentPlanner.segmentCount(
            overlay.text, TextAnimUnit.LETTER, metrics, measurer,
        )
    }
    val wordCount = remember(overlay.text) {
        TextSegmentPlanner.segmentCount(
            overlay.text, TextAnimUnit.WORD, metrics, measurer,
        )
    }
    if (anim.unit == TextAnimUnit.LETTER && letterCount > ExpandedOverlays.MAX_SEGMENTS) {
        Text(
            stringResource(R.string.anim_limit_letter, ExpandedOverlays.MAX_SEGMENTS),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    } else if (wordCount > ExpandedOverlays.MAX_SEGMENTS) {
        Text(
            stringResource(R.string.anim_limit_word, ExpandedOverlays.MAX_SEGMENTS),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Text(
        stringResource(R.string.anim_unit),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextAnimUnit.entries.forEach { unit ->
            FilterChip(
                selected = anim.unit == unit,
                onClick = {
                    viewModel.setTextAnimation(
                        overlay.id,
                        anim.copy(unit = unit),
                    )
                },
                label = {
                    Text(
                        stringResource(
                            when (unit) {
                                TextAnimUnit.WORD -> R.string.anim_unit_word
                                TextAnimUnit.LETTER -> R.string.anim_unit_letter
                            },
                        ),
                    )
                },
            )
        }
    }
    var duration by remember(overlay.id, anim.durationMs) {
        mutableFloatStateOf(anim.durationMs.toFloat())
    }
    Text(
        stringResource(R.string.anim_duration, duration.roundToInt()),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Slider(
        value = duration,
        onValueChange = { duration = it },
        onValueChangeFinished = {
            viewModel.setTextAnimation(
                overlay.id,
                anim.copy(durationMs = duration.roundToInt().toLong()),
            )
        },
        valueRange = 200f..2000f,
    )
}

@Composable
private fun TransformSection(viewModel: EditorViewModel, overlay: OverlayItem) {
    val state by viewModel.ui.collectAsState()
    // v0.5.0: the sheet shows the EVALUATED transform at the playhead (what
    // is on screen for a keyframed overlay); edits commit through the
    // auto-key path below.
    val t = KeyframeEvaluator.evaluate(overlay, state.playheadMs)
    Text(
        stringResource(R.string.transform_title),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    // v0.5.0 (F1): sheet sliders commit through the SAME auto-key path as
    // the gizmos and the quick panel (one rule, one undo step per slider).
    LabeledSlider(stringResource(R.string.transform_x), t.x, 0f..1f) { value ->
        viewModel.applyTransformEdit(overlay.id) { it.copy(x = value) }
    }
    LabeledSlider(stringResource(R.string.transform_y), t.y, 0f..1f) { value ->
        viewModel.applyTransformEdit(overlay.id) { it.copy(y = value) }
    }
    LabeledSlider(
        stringResource(R.string.transform_scale),
        t.scale,
        0.05f..1.6f,
        format = { v -> "${(v * 100).roundToInt()}%" },
    ) { value ->
        viewModel.applyTransformEdit(overlay.id) { it.copy(scale = value) }
    }
    LabeledSlider(
        stringResource(R.string.transform_rotation),
        t.rotationDeg,
        -180f..180f,
        format = { v -> "${v.roundToInt()}\u00b0" },
    ) { value ->
        viewModel.applyTransformEdit(overlay.id) { it.copy(rotationDeg = value) }
    }
    LabeledSlider(
        stringResource(R.string.transform_opacity),
        t.opacity,
        0f..1f,
        format = { v -> "${(v * 100).roundToInt()}%" },
    ) { value ->
        viewModel.applyTransformEdit(overlay.id) { it.copy(opacity = value) }
    }
    Text(
        stringResource(R.string.transform_touch_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Slider with local drag state, a single commit on release (undo-friendly)
 *  and a per-property display format (v0.4.0: % for scale/opacity, ° for
 *  rotation — model units are unchanged). */
@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String = { v -> String.format("%.2f", v) },
    onCommit: (Float) -> Unit,
) {
    var dragValue by remember(label, value) { mutableStateOf<Float?>(null) }
    val shown = dragValue ?: value
    Text(
        label + " " + format(shown.coerceIn(range.start, range.endInclusive)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Slider(
        value = shown.coerceIn(range.start, range.endInclusive),
        onValueChange = { dragValue = it },
        onValueChangeFinished = {
            dragValue?.let { v -> onCommit(v) }
            dragValue = null
        },
        valueRange = range,
    )
}

// ------------------------------------------------------------ timing

@Composable
private fun TimingSection(viewModel: EditorViewModel, overlay: OverlayItem) {
    Text(
        stringResource(R.string.overlay_timing),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = {
            viewModel.updateOverlay(overlay.id) {
                it.copy(
                    startMs = (it.startMs - 100).coerceAtLeast(0),
                    endMs = (it.endMs - 100).coerceAtLeast(it.startMs + 100),
                )
            }
        }) { Text("-0.1s") }
        OutlinedButton(onClick = {
            viewModel.updateOverlay(overlay.id) {
                it.copy(
                    startMs = it.startMs + 100,
                    endMs = it.endMs + 100,
                )
            }
        }) { Text("+0.1s") }
        OutlinedButton(onClick = {
            viewModel.updateOverlay(overlay.id) {
                it.copy(endMs = (it.endMs - 100).coerceAtLeast(it.startMs + 100))
            }
        }) { Text("-0.1s ⌐") }
        OutlinedButton(onClick = {
            viewModel.updateOverlay(overlay.id) { it.copy(endMs = it.endMs + 100) }
        }) { Text("+0.1s ⌐") }
    }
    Text(
        formatDuration(overlay.startMs) + " – " + formatDuration(overlay.endMs),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ------------------------------------------------------------ keyframes

@Composable
private fun KeyframeSection(viewModel: EditorViewModel, overlay: OverlayItem) {
    val state by viewModel.ui.collectAsState()
    Text(
        stringResource(R.string.keyframes_title),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        stringResource(R.string.keyframes_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { viewModel.addKeyframeAtPlayhead(overlay.id) }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(R.string.keyframe_add))
        }
    }
    // v0.5.0 (F3): keyframe navigation — jump the playhead to the nearest
    // keyframe before/after it (pure rule in [KeyframeEditing]).
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                KeyframeEditing.previousKeyframe(overlay.keyframes, state.playheadMs)
                    ?.let { viewModel.seekTo(it.timeMs) }
            },
            enabled = overlay.keyframes.isNotEmpty(),
        ) {
            Icon(
                Icons.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.keyframe_prev),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            stringResource(R.string.keyframe_jump),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        IconButton(
            onClick = {
                KeyframeEditing.nextKeyframe(overlay.keyframes, state.playheadMs)
                    ?.let { viewModel.seekTo(it.timeMs) }
            },
            enabled = overlay.keyframes.isNotEmpty(),
        ) {
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.keyframe_next),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = overlay.interpolation == Interpolation.LINEAR,
            onClick = { viewModel.setInterpolation(overlay.id, Interpolation.LINEAR) },
            label = { Text(stringResource(R.string.interp_linear)) },
        )
        FilterChip(
            selected = overlay.interpolation == Interpolation.EASE_IN_OUT,
            onClick = { viewModel.setInterpolation(overlay.id, Interpolation.EASE_IN_OUT) },
            label = { Text(stringResource(R.string.interp_smooth)) },
        )
    }
    if (overlay.keyframes.isEmpty()) {
        Text(
            stringResource(R.string.keyframes_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        overlay.keyframes.sortedBy { it.timeMs }.forEach { keyframe ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatDuration(keyframe.timeMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { viewModel.seekTo(keyframe.timeMs) },
                )
                TextButton(onClick = {
                    viewModel.moveKeyframe(overlay.id, keyframe.timeMs, keyframe.timeMs - 100)
                }) { Text("-") }
                TextButton(onClick = {
                    viewModel.moveKeyframe(overlay.id, keyframe.timeMs, keyframe.timeMs + 100)
                }) { Text("+") }
                TextButton(onClick = {
                    viewModel.removeKeyframe(overlay.id, keyframe.timeMs)
                }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.keyframe_delete),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------ shared

@Composable
private fun ColorSwatchRow(
    colors: List<Int>,
    current: Int,
    onSelect: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEach { c ->
            val color = Color(c)
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(color, CircleShape)
                    .clickable { onSelect(c) },
                contentAlignment = Alignment.Center,
            ) {
                if (current == c) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(
                                if (c == 0xFFFFFFFF.toInt()) Color.Black else Color.White,
                                CircleShape,
                            ),
                    )
                }
            }
        }
    }
}

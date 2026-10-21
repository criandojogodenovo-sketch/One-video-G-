package com.onevideog.editor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.onevideog.editor.R
import com.onevideog.editor.data.OverlayKind
import com.onevideog.editor.editor.EditorViewModel
import kotlin.math.roundToInt

/**
 * The contextual transform panel (v0.4.0, Etapa E1/E4): appears inline in the
 * editor column the moment a text/image layer is selected — no modal sheet
 * needed for the everyday move/scale/rotate/opacity tweaks.
 *
 * Contract:
 *  - Panel and gizmos write the SAME normalized model
 *    ([com.onevideog.editor.data.OverlayTransform]); every control commits on
 *    release so a slider drag is ONE undo step, mirroring the gizmo gesture
 *    commit.
 *  - Collapsed by default to a single title row (~34 dp) so it never
 *    permanently covers the preview element; the user expands on demand.
 *    Collapsed state resets per selection.
 *  - A locked layer shows WHY nothing can be edited instead of dead sliders
 *    (matching the sheet behaviour and the canvas bounds-only chrome).
 *  - Lives BELOW the preview and ABOVE the transport bar: it never intercepts
 *    play/pause, scrubbing or timeline gestures, and its own commits only
 *    produce a project copy — the player, bitmaps and effects chain are never
 *    rebuilt by panel edits (v0.3.1 contract).
 */
@Composable
internal fun TransformQuickPanel(
    viewModel: EditorViewModel,
    onOpenEditor: (Tool) -> Unit,
) {
    val state by viewModel.ui.collectAsState()
    val project = state.project ?: return
    val overlay = project.overlays.firstOrNull { it.id == state.selectedOverlayId }
        ?: return

    // Collapsed by default; resets when the selection changes so a new layer
    // never opens with the previous layer's panel state.
    var expanded by remember(overlay.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (overlay.isText) Icons.Filled.TextFields else Icons.Filled.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                when (overlay.kind) {
                    OverlayKind.TEXT -> overlay.text.ifBlank {
                        stringResource(R.string.timeline_image_marker)
                    }
                    OverlayKind.IMAGE -> stringResource(R.string.timeline_image_marker)
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
                    .clickable { expanded = !expanded },
            )
            // v0.5.0 (F4): keyframe-state indicator — the playhead is either
            // ON a keyframe (edits there UPDATE it — auto-key) or BETWEEN
            // keyframes (edits CREATE one). Pure rule in [KeyframeEditing].
            val onKeyframe = run {
                com.onevideog.editor.editor.KeyframeEditing
                    .keyframeAtPlayhead(overlay, state.playheadMs) != null
            }
            Text(
                stringResource(
                    if (onKeyframe) R.string.kf_state_on else R.string.kf_state_between,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if (onKeyframe) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(end = 6.dp),
            )
            if (overlay.locked) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = stringResource(R.string.layer_locked_hint),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                    contentDescription = stringResource(
                        if (expanded) R.string.quick_collapse else R.string.quick_expand,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = {
                    onOpenEditor(if (overlay.isText) Tool.TEXT else Tool.IMAGE)
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.quick_open_editor),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { viewModel.clearSelection() },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.quick_deselect),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!expanded) return@Column

        if (overlay.locked) {
            Text(
                stringResource(R.string.layer_locked_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            return@Column
        }

        // v0.5.4: the five sliders stack ≈ 330 dp — expanded, they consumed
        // the whole editor column and starved the preview stage down to a
        // 53×30 px sliver (device audit docs/AUDIT_v0.5.3_problems.md §1).
        // The sliders block now scrolls within a 240 dp cap: the panel keeps
        // every control reachable while the stage keeps a usable height.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState()),
        ) {
        // v0.5.0: sliders show the EVALUATED transform at the playhead (what
        // is actually on screen for a keyframed overlay); edits still commit
        // through the auto-key path below.
        val t = com.onevideog.editor.editor.KeyframeEvaluator
            .evaluate(overlay, state.playheadMs)
        // v0.5.0 (F1): every slider commits through the auto-key path — with
        // keyframes the edit lands on the keyframe at the playhead (± 25 ms)
        // or creates one at the snapped playhead; without keyframes it
        // writes the base transform (v0.4.0 behaviour). One undo step per
        // slider release.
        QuickSlider(stringResource(R.string.transform_x), t.x, 0f..1f) { value ->
            viewModel.applyTransformEdit(overlay.id) { it.copy(x = value) }
        }
        QuickSlider(stringResource(R.string.transform_y), t.y, 0f..1f) { value ->
            viewModel.applyTransformEdit(overlay.id) { it.copy(y = value) }
        }
        QuickSlider(
            stringResource(R.string.transform_scale),
            t.scale,
            0.05f..1.6f,
            format = { v -> "${(v * 100).roundToInt()}%" },
        ) { value ->
            viewModel.applyTransformEdit(overlay.id) { it.copy(scale = value) }
        }
        QuickSlider(
            stringResource(R.string.transform_rotation),
            t.rotationDeg.coerceIn(-180f, 180f),
            -180f..180f,
            format = { v -> "${v.roundToInt()}°" },
        ) { value ->
            viewModel.applyTransformEdit(overlay.id) { it.copy(rotationDeg = value) }
        }
        QuickSlider(
            stringResource(R.string.transform_opacity),
            t.opacity,
            0f..1f,
            format = { v -> "${(v * 100).roundToInt()}%" },
        ) { value ->
            viewModel.applyTransformEdit(overlay.id) { it.copy(opacity = value) }
        }
        }
    }
}

/**
 * Commit-on-release slider with local drag state: while the finger drags, the
 * committed model value is shown only until the first drag event, then the
 * local value leads (gizmo commits during the drag cannot fight the slider);
 * on release the model is updated once — one undo step per property change.
 */
@Composable
private fun QuickSlider(
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

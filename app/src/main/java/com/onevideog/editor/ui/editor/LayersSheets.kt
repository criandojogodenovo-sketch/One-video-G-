package com.onevideog.editor.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.onevideog.editor.R
import com.onevideog.editor.data.Interpolation
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind
import com.onevideog.editor.editor.EditorViewModel
import com.onevideog.editor.editor.KeyframeEvaluator
import com.onevideog.editor.editor.TimelineLogic
import com.onevideog.editor.ui.home.formatDuration
import kotlin.math.roundToInt

/**
 * The unified layer system: every element of the project — video clips,
 * independent audio clips, image and text overlays — in one reachable list
 * with select / add / reorder / delete and jump-off points into the
 * per-layer editors (including the keyframe editor). This is the entry point
 * that makes the multi-track model usable on a phone instead of a
 * source-code abstraction.
 */

@Composable
internal fun LayersPanel(viewModel: EditorViewModel, onOpenTool: (Tool) -> Unit) {
    val state by viewModel.ui.collectAsState()
    val project = state.project ?: return
    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importVideo(uri, uri.lastPathSegment ?: "video")
        }
    }
    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.addAudioClip(uri, uri.lastPathSegment ?: "audio")
        }
    }

    SheetTitle(stringResource(R.string.tools_layers))

    // ---- add layer shortcuts -------------------------------------------------
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { videoPicker.launch(arrayOf("video/*")) }) {
            Icon(Icons.Filled.Videocam, contentDescription = null)
            Text(stringResource(R.string.layer_add_video), modifier = Modifier.padding(start = 4.dp))
        }
        OutlinedButton(onClick = { audioPicker.launch(arrayOf("audio/*")) }) {
            Icon(Icons.Filled.MusicNote, contentDescription = null)
            Text(stringResource(R.string.layer_add_audio), modifier = Modifier.padding(start = 4.dp))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = { onOpenTool(Tool.TEXT) },
            enabled = viewModel.durationMs > 0,
        ) {
            Icon(Icons.Filled.TextFields, contentDescription = null)
            Text(stringResource(R.string.layer_add_text), modifier = Modifier.padding(start = 4.dp))
        }
        OutlinedButton(
            onClick = { onOpenTool(Tool.IMAGE) },
            enabled = viewModel.durationMs > 0,
        ) {
            Icon(Icons.Filled.Image, contentDescription = null)
            Text(stringResource(R.string.layer_add_image), modifier = Modifier.padding(start = 4.dp))
        }
    }

    // ---- video clips ---------------------------------------------------------
    Text(
        stringResource(R.string.layer_group_video, project.clips.size),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (project.clips.isEmpty()) {
        InfoLine(stringResource(R.string.timeline_no_video))
    }
    project.clips.forEachIndexed { index, clip ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (clip.id in state.missingClipIds) stringResource(R.string.editor_clip_missing) + " · " else "") +
                    stringResource(R.string.clip_label, index + 1) + " · " +
                    formatDuration(clip.effectiveDurationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        viewModel.selectClip(clip.id)
                        viewModel.selectOverlay(null)
                        viewModel.selectAudioClip(null)
                        viewModel.seekTo(TimelineLogic.clipStartMs(project.clips, index))
                        onOpenTool(Tool.TRIM)
                    },
            )
            IconButton(
                onClick = { viewModel.moveClip(clip.id, -1) },
                enabled = index > 0,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.layer_move_earlier),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { viewModel.moveClip(clip.id, +1) },
                enabled = index < project.clips.lastIndex,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.layer_move_later),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { viewModel.deleteClip(clip.id) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.layer_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // ---- audio clips ---------------------------------------------------------
    Text(
        stringResource(R.string.layer_group_audio, project.audioClips.size),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (project.audioClips.isEmpty()) {
        InfoLine(stringResource(R.string.music_none))
    }
    project.audioClips.forEach { audioClip ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                audioClip.displayName + " · " + formatDuration(audioClip.startMs) +
                    "–" + formatDuration(audioClip.endMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        viewModel.selectAudioClip(audioClip.id)
                        viewModel.selectClip(null)
                        viewModel.selectOverlay(null)
                        viewModel.seekTo(audioClip.startMs)
                        onOpenTool(Tool.AUDIO)
                    },
            )
            IconButton(
                onClick = { viewModel.removeAudioClip(audioClip.id) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.layer_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // ---- overlays (text + image, bottom → top of the stack) -------------------
    Text(
        stringResource(
            R.string.layer_group_overlays,
            project.overlays.size,
        ),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (project.overlays.isEmpty()) {
        InfoLine(stringResource(R.string.overlays_none))
    }
    project.overlays.sortedBy { it.zOrder }.forEach { overlay ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (overlay.isText) Icons.Filled.TextFields else Icons.Filled.Image,
                contentDescription = null,
                tint = if (overlay.visible) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(16.dp),
            )
            Text(
                when (overlay.kind) {
                    OverlayKind.TEXT -> overlay.text.take(20)
                    OverlayKind.IMAGE -> stringResource(R.string.timeline_image_marker)
                } + " · " + formatDuration(overlay.startMs) + "–" +
                    formatDuration(overlay.endMs) +
                    (if (overlay.keyframes.isNotEmpty()) " · " + overlay.keyframes.size + " ◆" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = if (overlay.id == state.selectedOverlayId) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp)
                    .clickable {
                        viewModel.selectOverlay(overlay.id)
                        viewModel.selectClip(null)
                        viewModel.selectAudioClip(null)
                        viewModel.seekTo(overlay.startMs)
                        onOpenTool(if (overlay.isText) Tool.TEXT else Tool.IMAGE)
                    },
            )
            IconButton(
                onClick = {
                    viewModel.selectOverlay(overlay.id)
                    onOpenTool(Tool.KEYFRAMES)
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Filled.Animation,
                    contentDescription = stringResource(R.string.layer_open_keyframes),
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
            IconButton(
                onClick = { viewModel.bringOverlayToFront(overlay.id) },
                enabled = !overlay.locked,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Filled.VerticalAlignTop,
                    contentDescription = stringResource(R.string.overlay_front),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { viewModel.sendOverlayToBack(overlay.id) },
                enabled = !overlay.locked,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Filled.VerticalAlignBottom,
                    contentDescription = stringResource(R.string.overlay_back),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // v0.3.0 layer state: visibility + lock toggles (single-tap).
            IconButton(
                onClick = { viewModel.setOverlayVisible(overlay.id, !overlay.visible) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    if (overlay.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = stringResource(
                        if (overlay.visible) R.string.layer_hide else R.string.layer_show,
                    ),
                    tint = if (overlay.visible) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { viewModel.setOverlayLocked(overlay.id, !overlay.locked) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    if (overlay.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = stringResource(
                        if (overlay.locked) R.string.layer_unlock else R.string.layer_lock,
                    ),
                    tint = if (overlay.locked) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { viewModel.removeOverlay(overlay.id) },
                enabled = !overlay.locked,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.layer_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Dedicated keyframe editor for the selected overlay: add a keyframe at the
 * playhead (capturing the evaluated transform), edit the property values of
 * the keyframe at the playhead, choose interpolation and move/delete
 * keyframes. The playhead can be moved from the timeline while this panel is
 * open — values always reflect the current playhead.
 */
@Composable
internal fun KeyframePanel(viewModel: EditorViewModel) {
    val state by viewModel.ui.collectAsState()
    val project = state.project ?: return
    val overlay = project.overlays.firstOrNull { it.id == state.selectedOverlayId }
    if (overlay == null) {
        SheetTitle(stringResource(R.string.keyframes_title))
        InfoLine(stringResource(R.string.keyframes_select_layer))
        return
    }

    SheetTitle(
        stringResource(R.string.keyframes_for, overlay.text.ifBlank {
            stringResource(R.string.timeline_image_marker)
        }),
    )
    val playhead = state.playheadMs
    val evaluated = KeyframeEvaluator.evaluate(overlay, playhead)
    val keyframeAtPlayhead = overlay.keyframes.firstOrNull { it.timeMs == playhead }

    Text(
        stringResource(R.string.keyframe_values_at, formatDuration(playhead)),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        "x " + String.format("%.2f", evaluated.x) +
            " · y " + String.format("%.2f", evaluated.y) +
            " · s " + String.format("%.2f", evaluated.scale) +
            " · r " + String.format("%.0f", evaluated.rotationDeg) + "°" +
            " · a " + String.format("%.0f", evaluated.opacity * 100) + "%",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Button(onClick = { viewModel.addKeyframeAtPlayhead(overlay.id) }) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Text(stringResource(R.string.keyframe_add), modifier = Modifier.padding(start = 4.dp))
    }

    // Interpolation between neighbouring keyframes.
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

    // Edit the property values of the keyframe exactly at the playhead.
    keyframeAtPlayhead?.let { keyframe ->
        Text(
            stringResource(R.string.keyframe_edit_values),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        fun writeKeyframe(update: (com.onevideog.editor.data.Keyframe) -> com.onevideog.editor.data.Keyframe) {
            viewModel.updateOverlay(overlay.id) { o ->
                o.copy(
                    keyframes = o.keyframes.map {
                        if (it.timeMs == keyframe.timeMs) update(it) else it
                    },
                )
            }
        }
        KeyframeValueSlider(
            label = stringResource(R.string.transform_x),
            value = keyframe.x ?: evaluated.x,
            range = 0f..1f,
        ) { v -> writeKeyframe { it.copy(x = v) } }
        KeyframeValueSlider(
            label = stringResource(R.string.transform_y),
            value = keyframe.y ?: evaluated.y,
            range = 0f..1f,
        ) { v -> writeKeyframe { it.copy(y = v) } }
        KeyframeValueSlider(
            label = stringResource(R.string.transform_scale),
            value = keyframe.scale ?: evaluated.scale,
            range = 0.05f..1.6f,
        ) { v -> writeKeyframe { it.copy(scale = v) } }
        KeyframeValueSlider(
            label = stringResource(R.string.transform_rotation),
            value = keyframe.rotationDeg ?: evaluated.rotationDeg,
            range = -180f..180f,
        ) { v -> writeKeyframe { it.copy(rotationDeg = v) } }
        KeyframeValueSlider(
            label = stringResource(R.string.transform_opacity),
            value = keyframe.opacity ?: evaluated.opacity,
            range = 0f..1f,
        ) { v -> writeKeyframe { it.copy(opacity = v) } }
    } ?: run {
        Text(
            stringResource(R.string.keyframe_no_kf_at_playhead),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // Keyframe list: seek / move ± 100 ms / delete.
    if (overlay.keyframes.isEmpty()) {
        Text(
            stringResource(R.string.keyframes_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            stringResource(R.string.keyframes_title) + " (" + overlay.keyframes.size + ")",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
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
    Text(
        stringResource(R.string.keyframes_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Commit-on-release slider for one keyframe property. */
@Composable
private fun KeyframeValueSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onCommit: (Float) -> Unit,
) {
    var dragValue by remember(label, value) { mutableFloatStateOf(value) }
    Text(
        label + " " + String.format("%.2f", dragValue),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Slider(
        value = dragValue.coerceIn(range.start, range.endInclusive),
        onValueChange = { dragValue = it },
        onValueChangeFinished = { onCommit(dragValue) },
        valueRange = range,
    )
}

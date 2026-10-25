package com.onevideog.editor.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import com.onevideog.editor.data.OverlayStyle
import com.onevideog.editor.data.OverlayStylePresets
import com.onevideog.editor.editor.CameraMath
import com.onevideog.editor.editor.EditorViewModel
import com.onevideog.editor.ui.home.formatDuration

/**
 * v0.9.0 (L3): the "Profundidade & Câmara" panel — the selected overlay's
 * depth plane ([com.onevideog.editor.data.OverlayItem.zDepth], slider) and
 * the project camera (pan/zoom sliders + add/remove keyframe at the playhead,
 * the KeyframePanel pattern). Everything commits through the ViewModel —
 * the SAME pure CameraMath both pipelines consume.
 */
@Composable
internal fun DepthCameraPanel(viewModel: EditorViewModel) {
    val state by viewModel.ui.collectAsState()
    val project = state.project ?: return
    SheetTitle(stringResource(R.string.depth_title))

    // ---- depth (selected overlay) ----
    val overlay = project.overlays.firstOrNull { it.id == state.selectedOverlayId }
    if (overlay == null) {
        InfoLine(stringResource(R.string.depth_select_layer))
    } else {
        Text(
            stringResource(R.string.depth_for, overlay.text.ifBlank {
                stringResource(R.string.timeline_image_marker)
            }),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        var depth by remember(overlay.id, overlay.zDepth) {
            mutableFloatStateOf(overlay.zDepth)
        }
        Text(
            stringResource(R.string.depth_value, String.format("%.2f", depth)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Slider(
            value = depth.coerceIn(CameraMath.DEPTH_MIN, CameraMath.DEPTH_MAX),
            onValueChange = { depth = it },
            onValueChangeFinished = { viewModel.setOverlayZDepth(overlay.id, depth) },
            valueRange = CameraMath.DEPTH_MIN..CameraMath.DEPTH_MAX,
        )
        InfoLine(stringResource(R.string.depth_hint))
    }

    // ---- camera (project) ----
    val camera = project.camera
    Text(
        stringResource(R.string.camera_section),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    var panX by remember(camera.panX) { mutableFloatStateOf(camera.panX) }
    var panY by remember(camera.panY) { mutableFloatStateOf(camera.panY) }
    var zoom by remember(camera.zoom) { mutableFloatStateOf(camera.zoom) }
    SliderLabel(stringResource(R.string.camera_pan_x) + " " + String.format("%.2f", panX))
    Slider(
        value = panX.coerceIn(-0.5f, 0.5f),
        onValueChange = { panX = it },
        onValueChangeFinished = { viewModel.setCamera(panX, panY, zoom) },
        valueRange = -0.5f..0.5f,
    )
    SliderLabel(stringResource(R.string.camera_pan_y) + " " + String.format("%.2f", panY))
    Slider(
        value = panY.coerceIn(-0.5f, 0.5f),
        onValueChange = { panY = it },
        onValueChangeFinished = { viewModel.setCamera(panX, panY, zoom) },
        valueRange = -0.5f..0.5f,
    )
    SliderLabel(stringResource(R.string.camera_zoom) + " " + String.format("%.2f", zoom))
    Slider(
        value = zoom.coerceIn(CameraMath.ZOOM_MIN, CameraMath.ZOOM_MAX),
        onValueChange = { zoom = it },
        onValueChangeFinished = { viewModel.setCamera(panX, panY, zoom) },
        valueRange = CameraMath.ZOOM_MIN..CameraMath.ZOOM_MAX,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = camera.interpolation == Interpolation.LINEAR,
            onClick = { viewModel.setCameraInterpolation(Interpolation.LINEAR) },
            label = { Text(stringResource(R.string.interp_linear)) },
        )
        FilterChip(
            selected = camera.interpolation == Interpolation.EASE_IN_OUT,
            onClick = { viewModel.setCameraInterpolation(Interpolation.EASE_IN_OUT) },
            label = { Text(stringResource(R.string.interp_smooth)) },
        )
    }
    Button(onClick = { viewModel.addCameraKeyframeAtPlayhead() }) {
        Text(stringResource(R.string.camera_keyframe_add))
    }
    if (camera.keyframes.isEmpty()) {
        InfoLine(stringResource(R.string.camera_keyframes_none))
    } else {
        camera.keyframes.sortedBy { it.timeMs }.forEach { keyframe ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatDuration(keyframe.timeMs) +
                        "  " + String.format("%.2f", keyframe.zoom),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.seekTo(keyframe.timeMs) }) {
                    Text(stringResource(R.string.keyframe_jump))
                }
                TextButton(onClick = { viewModel.removeCameraKeyframe(keyframe.timeMs) }) {
                    Text(stringResource(R.string.camera_keyframe_remove))
                }
            }
        }
    }
    InfoLine(stringResource(R.string.camera_note))
}

/**
 * v0.9.0 (L3): the "Estilo" panel — shadow/glow presets for the selected
 * overlay. The presets are deterministic parameter sets; the render is the
 * ONE baked bitmap (pure [com.onevideog.editor.editor.OverlayStyleBake]) the
 * preview and the export share.
 */
@Composable
internal fun StylePanel(viewModel: EditorViewModel) {
    val state by viewModel.ui.collectAsState()
    val project = state.project ?: return
    SheetTitle(stringResource(R.string.style_title))
    val overlay = project.overlays.firstOrNull { it.id == state.selectedOverlayId }
    if (overlay == null) {
        InfoLine(stringResource(R.string.style_select_layer))
        return
    }
    val presets = listOf(
        stringResource(R.string.style_preset_none) to null as OverlayStyle?,
        stringResource(R.string.style_preset_soft) to OverlayStylePresets.SOFT,
        stringResource(R.string.style_preset_hard) to OverlayStylePresets.HARD,
        stringResource(R.string.style_preset_neon) to OverlayStylePresets.NEON,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, style) ->
                    FilterChip(
                        selected = overlay.style == style,
                        onClick = { viewModel.setOverlayStyle(overlay.id, style) },
                        label = { Text(label) },
                    )
                }
            }
        }
    }
    if (overlay.kind == com.onevideog.editor.data.OverlayKind.TEXT &&
        overlay.textAnimation.isEnabled
    ) {
        InfoLine(stringResource(R.string.style_anim_note))
    }
    InfoLine(stringResource(R.string.style_note))
}

@Composable
private fun SliderLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

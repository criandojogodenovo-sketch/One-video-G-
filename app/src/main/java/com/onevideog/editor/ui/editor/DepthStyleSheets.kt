package com.onevideog.editor.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import com.onevideog.editor.data.CameraKeyframe
import com.onevideog.editor.data.Interpolation
import com.onevideog.editor.data.OverlayGlowStyle
import com.onevideog.editor.data.OverlayShadowStyle
import com.onevideog.editor.data.OverlayStyle
import com.onevideog.editor.editor.CameraMath
import com.onevideog.editor.editor.EditorViewModel
import com.onevideog.editor.editor.KeyframeEvaluator
import com.onevideog.editor.ui.home.formatDuration
import kotlin.math.roundToInt

/**
 * v0.9.0 (Fase L, L3): the "Profundidade & Câmara" and "Estilo" panels.
 *
 *  - DepthCameraPanel: one zDepth slider for the SELECTED overlay (-1..1,
 *    0 = the video plane) plus the project depth camera (pan X/Y, zoom and
 *    keyframes at the playhead, the SAME edit pattern the overlay keyframe
 *    panel uses). The camera is labelled the DEPTH camera (layers) — the
 *    video plane is not transformed in v0.9.0 (deferred L2b, D4 gate).
 *  - StylePanel: shadow/glow presets (none / soft drop / hard drop / neon
 *    glow) plus the fine sliders; the pixels are baked by the shared
 *    OverlayStyleBake so preview and export stay identical by construction.
 */
private object DepthStylePresets {
    val NONE: OverlayStyle? = null
    val SOFT_DROP = OverlayStyle(
        shadow = OverlayShadowStyle(
            offsetX = 0.006f, offsetY = 0.010f, blur = 0.014f,
            colorArgb = 0xFF000000.toInt(), opacity = 0.45f,
        ),
    )
    val HARD_DROP = OverlayStyle(
        shadow = OverlayShadowStyle(
            offsetX = 0.014f, offsetY = 0.014f, blur = 0.003f,
            colorArgb = 0xE6000000.toInt(), opacity = 0.9f,
        ),
    )
    val NEON_GLOW = OverlayStyle(
        glow = OverlayGlowStyle(
            radius = 0.028f, colorArgb = 0xFF00E5FF.toInt(), strength = 0.9f,
        ),
    )
}

@Composable
internal fun DepthCameraPanel(viewModel: EditorViewModel) {
    val state by viewModel.ui.collectAsState()
    val project = state.project ?: return
    SheetTitle(stringResource(R.string.tools_depth))

    // ---- selected overlay: zDepth ----
    val overlay = project.overlays.firstOrNull { it.id == state.selectedOverlayId }
    if (overlay == null) {
        InfoLine(stringResource(R.string.depth_none_selected))
    } else {
        Text(
            stringResource(R.string.depth_z_depth) + " " +
                String.format("%.2f", overlay.zDepth),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        CommitSlider(
            label = stringResource(R.string.depth_z_depth),
            value = overlay.zDepth,
            range = CameraMath.Z_DEPTH_MIN..CameraMath.Z_DEPTH_MAX,
        ) { v -> viewModel.setOverlayZDepth(overlay.id, v) }
        Text(
            stringResource(R.string.depth_z_depth_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // ---- the project depth camera ----
    val camera = project.camera
    val evaluated = CameraMath.evaluate(camera, state.playheadMs)
    Text(
        stringResource(R.string.depth_camera_title),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        stringResource(R.string.depth_camera_values) + " " +
            "x " + String.format("%.2f", evaluated.panX) +
            " · y " + String.format("%.2f", evaluated.panY) +
            " · z " + String.format("%.2f", evaluated.zoom),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    CommitSlider(
        label = stringResource(R.string.depth_camera_pan_x),
        value = camera.panX,
        range = CameraMath.PAN_MIN..CameraMath.PAN_MAX,
    ) { v -> viewModel.setCameraPanZoom(v, camera.panY, camera.zoom) }
    CommitSlider(
        label = stringResource(R.string.depth_camera_pan_y),
        value = camera.panY,
        range = CameraMath.PAN_MIN..CameraMath.PAN_MAX,
    ) { v -> viewModel.setCameraPanZoom(camera.panX, v, camera.zoom) }
    CommitSlider(
        label = stringResource(R.string.depth_camera_zoom),
        value = camera.zoom,
        range = CameraMath.ZOOM_MIN..CameraMath.ZOOM_MAX,
    ) { v -> viewModel.setCameraPanZoom(camera.panX, camera.panY, v) }
    Text(
        stringResource(R.string.depth_camera_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // ---- camera keyframes at the playhead (the overlay-keyframe pattern) ----
    val keyframeAtPlayhead = camera.keyframes.firstOrNull { it.timeMs == state.playheadMs }
    Button(onClick = { viewModel.addCameraKeyframeAtPlayhead() }) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Text(stringResource(R.string.depth_camera_kf_add), modifier = Modifier.padding(start = 4.dp))
    }
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
    if (keyframeAtPlayhead != null) {
        Text(
            stringResource(R.string.depth_camera_kf_at, formatDuration(state.playheadMs)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    if (camera.keyframes.isEmpty()) {
        Text(
            stringResource(R.string.depth_camera_kf_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            stringResource(R.string.keyframes_title) + " (" + camera.keyframes.size + ")",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        camera.keyframes.sortedBy { it.timeMs }.forEach { keyframe ->
            CameraKeyframeRow(viewModel, keyframe)
        }
    }
}

@Composable
private fun CameraKeyframeRow(viewModel: EditorViewModel, keyframe: CameraKeyframe) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            formatDuration(keyframe.timeMs) + " · " +
                String.format("%.2f", keyframe.panX) + "," +
                String.format("%.2f", keyframe.panY) + "," +
                String.format("%.2f", keyframe.zoom),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .clickable { viewModel.seekTo(keyframe.timeMs) },
        )
        TextButton(onClick = {
            viewModel.moveCameraKeyframe(keyframe.timeMs, keyframe.timeMs - 100)
        }) { Text("-") }
        TextButton(onClick = {
            viewModel.moveCameraKeyframe(keyframe.timeMs, keyframe.timeMs + 100)
        }) { Text("+") }
        TextButton(onClick = { viewModel.removeCameraKeyframe(keyframe.timeMs) }) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.keyframe_delete),
            )
        }
    }
}

@Composable
internal fun StylePanel(viewModel: EditorViewModel) {
    val state by viewModel.ui.collectAsState()
    val project = state.project ?: return
    SheetTitle(stringResource(R.string.tools_style))
    val overlay = project.overlays.firstOrNull { it.id == state.selectedOverlayId }
    if (overlay == null) {
        InfoLine(stringResource(R.string.style_none_selected))
        return
    }
    if (overlay.textAnimation.isEnabled) {
        Text(
            stringResource(R.string.style_anim_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    val style = overlay.style
    // ---- presets ----
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = style == null,
            onClick = { viewModel.setOverlayStyle(overlay.id, DepthStylePresets.NONE) },
            label = { Text(stringResource(R.string.style_preset_none)) },
        )
        FilterChip(
            selected = style == DepthStylePresets.SOFT_DROP,
            onClick = { viewModel.setOverlayStyle(overlay.id, DepthStylePresets.SOFT_DROP) },
            label = { Text(stringResource(R.string.style_preset_soft)) },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = style == DepthStylePresets.HARD_DROP,
            onClick = { viewModel.setOverlayStyle(overlay.id, DepthStylePresets.HARD_DROP) },
            label = { Text(stringResource(R.string.style_preset_hard)) },
        )
        FilterChip(
            selected = style == DepthStylePresets.NEON_GLOW,
            onClick = { viewModel.setOverlayStyle(overlay.id, DepthStylePresets.NEON_GLOW) },
            label = { Text(stringResource(R.string.style_preset_neon)) },
        )
    }
    Text(
        stringResource(R.string.style_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // ---- fine sliders (preserving each layer's tint) ----
    if (style != null) {
        style.shadow?.let { shadow ->
            Text(
                stringResource(R.string.style_shadow_group),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            fun writeShadow(update: (OverlayShadowStyle) -> OverlayShadowStyle) {
                viewModel.setOverlayStyle(
                    overlay.id,
                    style.copy(shadow = update(shadow)),
                )
            }
            CommitSlider(
                label = stringResource(R.string.style_offset_x),
                value = shadow.offsetX,
                range = -0.05f..0.05f,
            ) { v -> writeShadow { it.copy(offsetX = v) } }
            CommitSlider(
                label = stringResource(R.string.style_offset_y),
                value = shadow.offsetY,
                range = -0.05f..0.05f,
            ) { v -> writeShadow { it.copy(offsetY = v) } }
            CommitSlider(
                label = stringResource(R.string.style_blur),
                value = shadow.blur,
                range = 0f..0.05f,
            ) { v -> writeShadow { it.copy(blur = v) } }
            CommitSlider(
                label = stringResource(R.string.style_opacity),
                value = shadow.opacity,
                range = 0f..1f,
            ) { v -> writeShadow { it.copy(opacity = v) } }
        }
        style.glow?.let { glow ->
            Text(
                stringResource(R.string.style_glow_group),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            fun writeGlow(update: (OverlayGlowStyle) -> OverlayGlowStyle) {
                viewModel.setOverlayStyle(
                    overlay.id,
                    style.copy(glow = update(glow)),
                )
            }
            CommitSlider(
                label = stringResource(R.string.style_radius),
                value = glow.radius,
                range = 0f..0.06f,
            ) { v -> writeGlow { it.copy(radius = v) } }
            CommitSlider(
                label = stringResource(R.string.style_strength),
                value = glow.strength,
                range = 0f..1f,
            ) { v -> writeGlow { it.copy(strength = v) } }
        }
    }
}

/** Commit-on-release slider (the KeyframeValueSlider pattern, labelled). */
@Composable
private fun CommitSlider(
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

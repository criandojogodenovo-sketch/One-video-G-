package com.onevideog.editor.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import com.onevideog.editor.ui.common.OvgFilterChip as FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.onevideog.editor.R
import com.onevideog.editor.data.ProjectAspect
import com.onevideog.editor.editor.ColorMatrixMath
import com.onevideog.editor.editor.EditorViewModel
import com.onevideog.editor.editor.TimelineLogic
import com.onevideog.editor.ui.home.formatDuration

/** Bottom tool sheets. Each tool edits the selected (or containing) clip. */
enum class Tool(val labelRes: Int) {
    LAYERS(R.string.tools_layers),
    TRIM(R.string.tools_trim),
    SPLIT(R.string.tools_split),
    VOLUME(R.string.tools_volume),
    SPEED(R.string.tools_speed),
    SPEED_RAMP(R.string.tools_speed_ramp),
    ASPECT(R.string.tools_aspect),
    COLOR(R.string.tools_color),
    CURVES(R.string.tools_curves),
    EFFECTS(R.string.tools_effects),
    // v0.9.0 (Fase L): depth/camera + overlay styles (Opção A 2.5D).
    DEPTH(R.string.tools_depth),
    STYLE(R.string.tools_style),
    TEXT(R.string.tools_text),
    IMAGE(R.string.tools_image),
    AUDIO(R.string.tools_audio),
    KEYFRAMES(R.string.keyframes_title),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolSheet(
    tool: Tool,
    viewModel: EditorViewModel,
    onDismiss: () -> Unit,
    onOpenTool: (Tool) -> Unit = {},
) {
    val state by viewModel.ui.collectAsState()
    val project = state.project ?: return

    // The clip a tool acts on: selected clip, else the clip under the playhead.
    val clipIndex = project.clips.indexOfFirst { it.id == state.selectedClipId }
        .takeIf { it >= 0 }
        ?: TimelineLogic.clipIndexAt(project.clips, state.playheadMs)
    val clip = project.clips.getOrNull(clipIndex)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (tool) {
                Tool.LAYERS -> LayersPanel(viewModel, onOpenTool)
                Tool.KEYFRAMES -> KeyframePanel(viewModel)
                Tool.TRIM -> if (clip != null) TrimPanel(viewModel, clip) else InfoLine(stringResource(R.string.split_no_clip))
                Tool.SPLIT -> SplitPanel(viewModel, clip != null)
                Tool.VOLUME -> if (clip != null) VolumePanel(viewModel, clip) else InfoLine(stringResource(R.string.split_no_clip))
                Tool.SPEED -> if (clip != null) SpeedPanel(viewModel, clip) else InfoLine(stringResource(R.string.split_no_clip))
                Tool.SPEED_RAMP -> if (clip != null) SpeedRampPanel(viewModel, clip) else InfoLine(stringResource(R.string.split_no_clip))
                Tool.ASPECT -> AspectPanel(viewModel, project.aspectEnum)
                Tool.COLOR -> if (clip != null) ColorPanel(viewModel, clip) else InfoLine(stringResource(R.string.split_no_clip))
                Tool.CURVES -> if (clip != null) ColorCurvesPanel(viewModel, clip) else InfoLine(stringResource(R.string.split_no_clip))
                Tool.EFFECTS -> if (clip != null) ClipEffectsPanel(viewModel, clip) else InfoLine(stringResource(R.string.split_no_clip))
                Tool.DEPTH -> DepthCameraPanel(viewModel)
                Tool.STYLE -> StylePanel(viewModel)
                Tool.TEXT -> OverlayEditorPanel(viewModel, com.onevideog.editor.data.OverlayKind.TEXT)
                Tool.IMAGE -> OverlayEditorPanel(viewModel, com.onevideog.editor.data.OverlayKind.IMAGE)
                Tool.AUDIO -> AudioPanel(viewModel)
            }
        }
    }
}

@Composable
internal fun InfoLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SheetTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface)
}

// ---------------------------------------------------------------- Trim

@Composable
private fun TrimPanel(viewModel: EditorViewModel, clip: com.onevideog.editor.data.Clip) {
    var range by remember(clip.id, clip.trimStartMs, clip.trimEndMs) {
        mutableStateOf(clip.trimStartMs.toFloat()..clip.trimEndMs.toFloat())
    }
    SheetTitle(stringResource(R.string.trim_title))
    RangeSlider(
        value = range,
        onValueChange = { range = it },
        valueRange = 0f..clip.sourceDurationMs.toFloat().coerceAtLeast(1f),
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            stringResource(R.string.trim_start) + ": " + formatDuration(range.start.toLong()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(R.string.trim_duration) + ": " +
                formatDuration((range.endInclusive - range.start).toLong()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(R.string.trim_end) + ": " + formatDuration(range.endInclusive.toLong()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { range = (range.start - 100).coerceAtLeast(0f)..range.endInclusive }) {
            Text("-0.1s")
        }
        OutlinedButton(onClick = { range = (range.start + 100).coerceAtMost(range.endInclusive - 100)..range.endInclusive }) {
            Text("+0.1s")
        }
        OutlinedButton(onClick = { range = range.start..(range.endInclusive - 100).coerceAtLeast(range.start + 100) }) {
            Text("-0.1s ⌐")
        }
        OutlinedButton(onClick = { range = range.start..(range.endInclusive + 100).coerceAtMost(clip.sourceDurationMs.toFloat()) }) {
            Text("+0.1s ⌐")
        }
    }
    Button(
        onClick = {
            viewModel.trimClip(clip.id, range.start.toLong(), range.endInclusive.toLong())
            viewModel.seekTo(0)
        },
        enabled = (range.endInclusive - range.start) >= TimelineLogic.MIN_CLIP_SOURCE_MS,
    ) {
        Text(stringResource(R.string.trim_apply))
    }
}

// ---------------------------------------------------------------- Split

@Composable
private fun SplitPanel(viewModel: EditorViewModel, hasClipUnderPlayhead: Boolean) {
    SheetTitle(stringResource(R.string.tools_split))
    if (hasClipUnderPlayhead) {
        Button(onClick = { viewModel.splitAtPlayhead() }) {
            Text(stringResource(R.string.split_here))
        }
    } else {
        InfoLine(stringResource(R.string.split_no_clip))
    }
}

// ---------------------------------------------------------------- Volume

@Composable
private fun VolumePanel(viewModel: EditorViewModel, clip: com.onevideog.editor.data.Clip) {
    var volume by remember(clip.id, clip.volume) { mutableFloatStateOf(clip.volume) }
    SheetTitle(stringResource(R.string.volume_title))
    Text(
        stringResource(R.string.volume_percent, (volume * 100).toInt()),
        color = MaterialTheme.colorScheme.onSurface,
    )
    Slider(
        value = volume,
        onValueChange = { volume = it },
        onValueChangeFinished = { viewModel.setVolume(clip.id, volume) },
        valueRange = 0f..2f,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { volume = 1f; viewModel.setVolume(clip.id, 1f) }) {
            Text(stringResource(R.string.reset))
        }
    }
}

// ---------------------------------------------------------------- Speed

@Composable
private fun SpeedPanel(viewModel: EditorViewModel, clip: com.onevideog.editor.data.Clip) {
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    SheetTitle(stringResource(R.string.speed_title))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        speeds.forEach { speed ->
            FilterChip(
                selected = clip.speed == speed,
                onClick = { viewModel.setSpeed(clip.id, speed) },
                label = { Text(stringResource(R.string.speed_value, String.format("%.2f", speed).removeSuffix("0").removeSuffix("."))) },
            )
        }
    }
}

// ---------------------------------------------------------------- Aspect

@Composable
private fun AspectPanel(viewModel: EditorViewModel, current: ProjectAspect) {
    SheetTitle(stringResource(R.string.aspect_title))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProjectAspect.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { aspect ->
                    FilterChip(
                        selected = current == aspect,
                        onClick = { viewModel.setAspect(aspect) },
                        label = { Text(stringResource(aspect.labelRes)) },
                    )
                }
            }
        }
    }
    Text(
        stringResource(R.string.aspect_preview_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---------------------------------------------------------------- Color

@Composable
private fun ColorPanel(viewModel: EditorViewModel, clip: com.onevideog.editor.data.Clip) {
    var brightness by remember(clip.id) { mutableFloatStateOf(clip.color.brightness * 100f / 0.3f) }
    var contrast by remember(clip.id) { mutableFloatStateOf(if (clip.color.contrast >= 1f) (clip.color.contrast - 1f) * 100f else (clip.color.contrast - 1f) * 200f) }
    var saturation by remember(clip.id) { mutableFloatStateOf((clip.color.saturation - 1f) * 100f) }
    SheetTitle(stringResource(R.string.color_title))
    Text(
        stringResource(R.string.color_brightness),
        color = MaterialTheme.colorScheme.onSurface,
    )
    Slider(
        value = brightness,
        onValueChange = { brightness = it },
        onValueChangeFinished = {
            viewModel.setColor(
                clip.id,
                ColorMatrixMath.brightnessFromSlider(brightness.toInt()),
                ColorMatrixMath.contrastFromSlider(contrast.toInt()),
                ColorMatrixMath.saturationFromSlider(saturation.toInt()),
            )
        },
        valueRange = -100f..100f,
    )
    Text(
        stringResource(R.string.color_contrast),
        color = MaterialTheme.colorScheme.onSurface,
    )
    Slider(
        value = contrast,
        onValueChange = { contrast = it },
        onValueChangeFinished = {
            viewModel.setColor(
                clip.id,
                ColorMatrixMath.brightnessFromSlider(brightness.toInt()),
                ColorMatrixMath.contrastFromSlider(contrast.toInt()),
                ColorMatrixMath.saturationFromSlider(saturation.toInt()),
            )
        },
        valueRange = -100f..100f,
    )
    Text(
        stringResource(R.string.color_saturation),
        color = MaterialTheme.colorScheme.onSurface,
    )
    Slider(
        value = saturation,
        onValueChange = { saturation = it },
        onValueChangeFinished = {
            viewModel.setColor(
                clip.id,
                ColorMatrixMath.brightnessFromSlider(brightness.toInt()),
                ColorMatrixMath.contrastFromSlider(contrast.toInt()),
                ColorMatrixMath.saturationFromSlider(saturation.toInt()),
            )
        },
        valueRange = -100f..100f,
    )
    Text(
        stringResource(R.string.color_live_preview),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            brightness = 0f; contrast = 0f; saturation = 0f
            viewModel.setColor(clip.id, 0f, 1f, 1f)
        }) {
            Text(stringResource(R.string.reset))
        }
    }
}

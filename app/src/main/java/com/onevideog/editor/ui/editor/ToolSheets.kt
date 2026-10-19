package com.onevideog.editor.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.onevideog.editor.R
import com.onevideog.editor.data.OverlayPosition
import com.onevideog.editor.data.ProjectAspect
import com.onevideog.editor.editor.ColorAdjustmentEffect
import com.onevideog.editor.editor.EditorViewModel
import com.onevideog.editor.editor.TimelineLogic
import com.onevideog.editor.ui.home.formatDuration

/** Bottom tool sheets. Each tool edits the selected (or containing) clip. */
enum class Tool(val labelRes: Int) {
    TRIM(R.string.tools_trim),
    SPLIT(R.string.tools_split),
    VOLUME(R.string.tools_volume),
    SPEED(R.string.tools_speed),
    ASPECT(R.string.tools_aspect),
    COLOR(R.string.tools_color),
    TEXT(R.string.tools_text),
    IMAGE(R.string.tools_image),
    MUSIC(R.string.tools_music),
}

private val TEXT_COLORS = listOf(
    0xFFFFFFFF.toInt(), 0xFF111111.toInt(), 0xFFF98A0B.toInt(),
    0xFFE2574C.toInt(), 0xFF4C9BE2.toInt(), 0xFF59C979.toInt(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolSheet(
    tool: Tool,
    viewModel: EditorViewModel,
    onDismiss: () -> Unit,
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
                Tool.TRIM -> if (clip != null) TrimPanel(viewModel, clip) else InfoLine(stringResource(R.string.split_no_clip))
                Tool.SPLIT -> SplitPanel(viewModel, clip != null)
                Tool.VOLUME -> if (clip != null) VolumePanel(viewModel, clip) else InfoLine(stringResource(R.string.split_no_clip))
                Tool.SPEED -> if (clip != null) SpeedPanel(viewModel, clip) else InfoLine(stringResource(R.string.split_no_clip))
                Tool.ASPECT -> AspectPanel(viewModel, project.aspectEnum)
                Tool.COLOR -> if (clip != null) ColorPanel(viewModel, clip) else InfoLine(stringResource(R.string.split_no_clip))
                Tool.TEXT -> TextPanel(viewModel)
                Tool.IMAGE -> ImagePanel(viewModel)
                Tool.MUSIC -> MusicPanel(viewModel)
            }
        }
    }
}

@Composable
private fun InfoLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
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
        )
        Text(
            stringResource(R.string.trim_duration) + ": " +
                formatDuration((range.endInclusive - range.start).toLong()),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.trim_end) + ": " + formatDuration(range.endInclusive.toLong()),
            style = MaterialTheme.typography.bodySmall,
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
    Text(stringResource(R.string.volume_percent, (volume * 100).toInt()))
    Slider(
        value = volume,
        onValueChange = { volume = it },
        valueRange = 0f..2f,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { volume = 1f; viewModel.setVolume(clip.id, 1f) }) {
            Text(stringResource(R.string.reset))
        }
        Button(onClick = { viewModel.setVolume(clip.id, volume) }) {
            Text(stringResource(R.string.save))
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
                label = { Text(stringResource(R.string.speed_value, String.format("%.2f", speed).removeSuffix("0").removeSuffix("."))) },            )
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
}

// ---------------------------------------------------------------- Color

@Composable
private fun ColorPanel(viewModel: EditorViewModel, clip: com.onevideog.editor.data.Clip) {
    var brightness by remember(clip.id) { mutableFloatStateOf(clip.color.brightness * 100f / 0.3f) }
    var contrast by remember(clip.id) { mutableFloatStateOf(if (clip.color.contrast >= 1f) (clip.color.contrast - 1f) * 100f else (clip.color.contrast - 1f) * 200f) }
    var saturation by remember(clip.id) { mutableFloatStateOf((clip.color.saturation - 1f) * 100f) }
    SheetTitle(stringResource(R.string.color_title))
    Text(stringResource(R.string.color_brightness))
    Slider(value = brightness, onValueChange = { brightness = it }, valueRange = -100f..100f)
    Text(stringResource(R.string.color_contrast))
    Slider(value = contrast, onValueChange = { contrast = it }, valueRange = -100f..100f)
    Text(stringResource(R.string.color_saturation))
    Slider(value = saturation, onValueChange = { saturation = it }, valueRange = -100f..100f)
    Text(
        stringResource(R.string.color_applied_on_export),
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
        Button(onClick = {
            viewModel.setColor(
                clip.id,
                ColorAdjustmentEffect.brightnessFromSlider(brightness.toInt()),
                ColorAdjustmentEffect.contrastFromSlider(contrast.toInt()),
                ColorAdjustmentEffect.saturationFromSlider(saturation.toInt()),
            )
        }) {
            Text(stringResource(R.string.save))
        }
    }
}

// ---------------------------------------------------------------- Text overlay

@Composable
private fun TextPanel(viewModel: EditorViewModel) {
    val state by viewModel.ui.collectAsState()
    val project = state.project ?: return
    var text by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(TEXT_COLORS.first()) }
    var position by remember { mutableStateOf(OverlayPosition.BOTTOM) }

    SheetTitle(stringResource(R.string.text_add))
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(R.string.text_hint)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(stringResource(R.string.text_color))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TEXT_COLORS.forEach { c ->
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(Color(c), CircleShape)
                    .clickable { color = c },
                contentAlignment = Alignment.Center,
            ) {
                if (color == c) {
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
    PositionChips(position) { position = it }
    Text(
        stringResource(R.string.overlay_applied_on_export),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = {
            viewModel.addTextOverlay(text.trim(), color, position)
            text = ""
        },
        enabled = text.isNotBlank(),
    ) {
        Text(stringResource(R.string.text_add))
    }
    if (project.textOverlays.isNotEmpty()) {
        Text(stringResource(R.string.tools_text), style = MaterialTheme.typography.labelMedium)
        project.textOverlays.forEach { overlay ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    overlay.text,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { viewModel.removeTextOverlay(overlay.id) }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.overlay_remove))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Image overlay

@Composable
private fun ImagePanel(viewModel: EditorViewModel) {
    val state by viewModel.ui.collectAsState()
    val project = state.project ?: return
    var position by remember { mutableStateOf(OverlayPosition.BOTTOM) }
    var scale by remember { mutableStateOf(0.4f) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.addImageOverlay(uri, position, scale)
    }

    SheetTitle(stringResource(R.string.image_add))
    PositionChips(position) { position = it }
    Text(stringResource(R.string.image_scale) + String.format(": %d%%", (scale * 100).toInt()))
    Slider(value = scale, onValueChange = { scale = it }, valueRange = 0.1f..1f)
    Text(
        stringResource(R.string.overlay_applied_on_export),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = { picker.launch(arrayOf("image/*")) }) {
        Text(stringResource(R.string.image_pick))
    }
    if (project.imageOverlays.isNotEmpty()) {
        project.imageOverlays.forEach { overlay ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    overlay.uri.substringAfterLast('/'),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { viewModel.removeImageOverlay(overlay.id) }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.overlay_remove))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Music

@Composable
private fun MusicPanel(viewModel: EditorViewModel) {
    val state by viewModel.ui.collectAsState()
    val music = state.project?.music
    var volume by remember(music?.uri) { mutableFloatStateOf(music?.volume ?: 0.8f) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.setMusic(uri, uri.lastPathSegment ?: "music")
        }
    }

    SheetTitle(stringResource(R.string.music_title))
    if (music == null) {
        InfoLine(stringResource(R.string.music_none))
    } else {
        Text(music.displayName, maxLines = 1)
        Text(stringResource(R.string.music_volume) + " " + (music.volume * 100).toInt() + "%")
        Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..2f)
        Button(onClick = { viewModel.setMusicVolume(volume) }) {
            Text(stringResource(R.string.save))
        }
    }
    Text(
        stringResource(R.string.music_applied_on_export),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }) {
            Text(stringResource(R.string.editor_import_music))
        }
        if (music != null) {
            OutlinedButton(onClick = { viewModel.removeMusic() }) {
                Text(stringResource(R.string.music_remove))
            }
        }
    }
}

// ---------------------------------------------------------------- Shared

@Composable
private fun PositionChips(current: OverlayPosition, onSelect: (OverlayPosition) -> Unit) {
    Text(stringResource(R.string.text_position))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OverlayPosition.entries.forEach { position ->
            FilterChip(
                selected = current == position,
                onClick = { onSelect(position) },
                label = {
                    Text(
                        stringResource(
                            when (position) {
                                OverlayPosition.TOP -> R.string.position_top
                                OverlayPosition.CENTER -> R.string.position_center
                                OverlayPosition.BOTTOM -> R.string.position_bottom
                            },
                        ),
                    )
                },
            )
        }
    }
}

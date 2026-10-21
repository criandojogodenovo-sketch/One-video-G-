package com.onevideog.editor.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.onevideog.editor.R
import com.onevideog.editor.data.AudioClipItem
import com.onevideog.editor.editor.AudioTimeline
import com.onevideog.editor.editor.EditorViewModel
import com.onevideog.editor.ui.home.formatDuration

/**
 * Independent audio tracks: import, move (start time), trim (source range),
 * volume and loop. Everything edited here feeds the preview audio scheduler
 * and the export audio sequences.
 */
@Composable
internal fun AudioPanel(viewModel: EditorViewModel) {
    val state by viewModel.ui.collectAsState()
    val project = state.project ?: return
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.addAudioClip(uri, uri.lastPathSegment ?: "audio")
        }
    }

    SheetTitle(stringResource(R.string.tools_audio))
    Button(onClick = { picker.launch(arrayOf("audio/*")) }) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Text(stringResource(R.string.editor_import_music))
    }

    val selected = project.audioClips.firstOrNull { it.id == state.selectedAudioClipId }
    if (selected != null) {
        AudioClipEditor(viewModel, selected)
    }

    Text(
        stringResource(R.string.audio_tracks),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (project.audioClips.isEmpty()) {
        InfoLine(stringResource(R.string.music_none))
    } else {
        project.audioClips.forEach { clip ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    clip.displayName +
                        " · " + formatDuration(clip.startMs) + "–" +
                        formatDuration(clip.endMs) +
                        (if (clip.loop) " · ∞" else ""),
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            // v0.5.1: unified exclusive selection.
                            viewModel.selectTimelineClip(
                                com.onevideog.editor.editor.ClipKind.AUDIO, clip.id,
                            )
                        },
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(onClick = { viewModel.removeAudioClip(clip.id) }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.audio_remove),
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioClipEditor(viewModel: EditorViewModel, clip: AudioClipItem) {
    SheetTitle(clip.displayName)

    // Move (absolute project position)
    Text(
        stringResource(R.string.audio_start) + ": " + formatDuration(clip.startMs),
        color = MaterialTheme.colorScheme.onSurface,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = {
            viewModel.moveAudioClip(clip.id, (clip.startMs - 100).coerceAtLeast(0))
        }) { Text("-0.1s") }
        OutlinedButton(onClick = {
            viewModel.moveAudioClip(clip.id, clip.startMs + 100)
        }) { Text("+0.1s") }
        OutlinedButton(onClick = {
            viewModel.moveAudioClip(clip.id, viewModel.ui.value.playheadMs)
        }) { Text(stringResource(R.string.audio_move_to_playhead)) }
    }

    // Trim (source range)
    var range by remember(clip.id, clip.trimStartMs, clip.trimEndMs) {
        mutableStateOf(clip.trimStartMs.toFloat()..clip.trimEndMs.toFloat())
    }
    Text(
        stringResource(R.string.trim_title),
        color = MaterialTheme.colorScheme.onSurface,
    )
    RangeSlider(
        value = range,
        onValueChange = { range = it },
        valueRange = 0f..clip.sourceDurationMs.toFloat().coerceAtLeast(1f),
    )
    Text(
        stringResource(R.string.trim_duration) + ": " +
            formatDuration((range.endInclusive - range.start).toLong()),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                viewModel.trimAudioClip(clip.id, range.start.toLong(), range.endInclusive.toLong(), null)
            },
            enabled = (range.endInclusive - range.start) >= AudioTimelineMinMs,
        ) {
            Text(stringResource(R.string.trim_apply))
        }
    }

    // Volume
    var volume by remember(clip.id, clip.volume) { mutableFloatStateOf(clip.volume) }
    Text(
        stringResource(R.string.volume_percent, (volume * 100).toInt()),
        color = MaterialTheme.colorScheme.onSurface,
    )
    Slider(
        value = volume,
        onValueChange = { volume = it },
        onValueChangeFinished = { viewModel.setAudioVolume(clip.id, volume) },
        valueRange = 0f..2f,
    )

    // Loop
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = clip.loop,
            onClick = { viewModel.setAudioLoop(clip.id, !clip.loop) },
            label = { Text(stringResource(R.string.audio_loop)) },
        )
    }
    Text(
        stringResource(R.string.audio_plan_note, AudioTimeline.MAX_LOOP_SEGMENTS),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private const val AudioTimelineMinMs = 150L

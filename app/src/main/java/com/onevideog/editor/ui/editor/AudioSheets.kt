package com.onevideog.editor.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import com.onevideog.editor.ui.common.OvgFilterChip as FilterChip
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.onevideog.editor.R
import com.onevideog.editor.data.AudioClipItem
import com.onevideog.editor.data.AudioKeyframe
import com.onevideog.editor.editor.AudioCrossfade
import com.onevideog.editor.editor.AudioGainMath
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

    // v0.8.0 (P3): fades — linear gain ramps at the clip edges, applied by
    // the shared GainAutomationAudioProcessor (preview AND export).
    val clipDuration = clip.trimmedDurationMs
    if (clipDuration > 0) {
        Text(
            stringResource(R.string.audio_fade_in) + ": " + formatDuration(clip.fadeInMs) +
                " · " + stringResource(R.string.audio_fade_out) + ": " +
                formatDuration(clip.fadeOutMs),
            color = MaterialTheme.colorScheme.onSurface,
        )
        var fadeIn by remember(clip.id, clip.fadeInMs, clip.fadeOutMs) {
            mutableFloatStateOf(clip.fadeInMs.toFloat())
        }
        var fadeOut by remember(clip.id, clip.fadeInMs, clip.fadeOutMs) {
            mutableFloatStateOf(clip.fadeOutMs.toFloat())
        }
        Slider(
            value = fadeIn,
            onValueChange = { fadeIn = it },
            onValueChangeFinished = {
                viewModel.setAudioFades(clip.id, fadeIn.toLong(), clip.fadeOutMs)
            },
            valueRange = 0f..clipDuration.toFloat(),
        )
        Slider(
            value = fadeOut,
            onValueChange = { fadeOut = it },
            onValueChangeFinished = {
                viewModel.setAudioFades(clip.id, clip.fadeInMs, fadeOut.toLong())
            },
            valueRange = 0f..clipDuration.toFloat(),
        )
    }

    // v0.8.0 (Q3): the volume-automation curve editor — tap adds a point,
    // drag moves it, double-tap removes it; ONE commit per gesture end.
    if (clipDuration > 0) {
        AudioKeyframeEditor(viewModel, clip, clipDuration)
    }

    // v0.8.0 (Q4): simple time-domain noise gate (hiss between speech).
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = clip.noiseGate,
            onClick = { viewModel.setAudioNoiseGate(clip.id, !clip.noiseGate) },
            label = { Text(stringResource(R.string.audio_noise_gate)) },
        )
    }
    Text(
        stringResource(R.string.audio_noise_gate_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // v0.8.0 (P4): crossfade with the nearest audio neighbour — creates the
    // overlap and sets both fades (the overlap then crossfades automatically
    // through the fade envelopes).
    val hasNeighbour = remember(clip.id, clip.startMs, clip.endMs, viewModel.ui.value.project?.audioClips) {
        viewModel.ui.value.project?.audioClips
            ?.let { AudioCrossfade.neighbour(it, clip) } != null
    }
    Button(
        onClick = { viewModel.applyAudioCrossfade(clip.id) },
        enabled = hasNeighbour,
    ) {
        Text(stringResource(R.string.audio_crossfade))
    }
    Text(
        stringResource(R.string.audio_crossfade_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/**
 * v0.8.0 (Q3) — the audio volume-automation editor, in the pattern of the
 * video keyframe editors: a canvas of the gain envelope (x = clip-local
 * time, y = volume 0..2 with the 1.0 reference line), tap adds a point,
 * drag moves it, double-tap removes it. The draft lives in panel state and
 * commits ONE [EditorViewModel.setAudioKeyframes] per gesture end (never
 * per frame). The curve drawn IS [AudioGainMath.gainAt] (the same function
 * preview and export apply) — no second rendering decision exists.
 */
@Composable
private fun AudioKeyframeEditor(
    viewModel: EditorViewModel,
    clip: AudioClipItem,
    clipDurationMs: Long,
) {
    val draft = remember(clip.id, clip.gainKeyframes) {
        mutableStateOf(clip.gainKeyframes.sortedBy { it.timeMs })
    }
    var dragIndex by remember { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current
    val hitRadiusPx = with(density) { 28.dp.toPx() }

    Text(
        stringResource(R.string.audio_keyframes),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .padding(vertical = 4.dp)
            .pointerInput(clip.id, clip.gainKeyframes, clipDurationMs) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        // Remove the nearest point (double-tap on it).
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val index = draft.value.indexOfFirst { kf ->
                            val px = (kf.timeMs.toFloat() / clipDurationMs) * w
                            val py = h * (1f - kf.volume.coerceIn(0f, 2f) / 2f)
                            val dx = offset.x - px
                            val dy = offset.y - py
                            dx * dx + dy * dy <= hitRadiusPx * hitRadiusPx
                        }
                        if (index >= 0) {
                            val next = draft.value.toMutableList().apply { removeAt(index) }
                            draft.value = next
                            viewModel.setAudioKeyframes(clip.id, next)
                        }
                    },
                    onTap = { offset ->
                        if (draft.value.size >= AudioGainMath.MAX_KEYFRAMES) return@detectTapGestures
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val t = ((offset.x / w) * clipDurationMs)
                            .toLong().coerceIn(0L, clipDurationMs)
                        val v = ((1f - offset.y / h) * 2f).coerceIn(0f, AudioGainMath.MAX_VOLUME)
                        val next = draft.value + AudioKeyframe(t, v)
                        draft.value = next.sortedBy { it.timeMs }
                        viewModel.setAudioKeyframes(clip.id, next)
                    },
                )
            }
            .pointerInput(clip.id, clip.gainKeyframes, clipDurationMs) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        dragIndex = draft.value.indexOfFirst { kf ->
                            val px = (kf.timeMs.toFloat() / clipDurationMs) * w
                            val py = h * (1f - kf.volume.coerceIn(0f, 2f) / 2f)
                            val dx = offset.x - px
                            val dy = offset.y - py
                            dx * dx + dy * dy <= hitRadiusPx * hitRadiusPx
                        }.takeIf { it >= 0 }
                    },
                    onDragEnd = {
                        dragIndex = null
                        viewModel.setAudioKeyframes(clip.id, draft.value)
                    },
                    onDragCancel = { dragIndex = null },
                ) { change, _ ->
                    val index = dragIndex ?: return@detectDragGestures
                    change.consume()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val t = ((change.position.x / w) * clipDurationMs)
                        .toLong().coerceIn(0L, clipDurationMs)
                    val v = ((1f - change.position.y / h) * 2f)
                        .coerceIn(0f, AudioGainMath.MAX_VOLUME)
                    draft.value = draft.value.mapIndexed { i, kf ->
                        if (i == index) kf.copy(timeMs = t, volume = v) else kf
                    }.sortedBy { it.timeMs }
                    // Keep dragging the SAME logical point after sorting.
                    dragIndex = draft.value.indexOfFirst { it.timeMs == t && it.volume == v }
                        .takeIf { it >= 0 } ?: index
                }
            },
    ) {
        // Volume = 1.0 reference line.
        drawLine(
            color = Color.Gray.copy(alpha = 0.4f),
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = 1f,
        )
        // The envelope: the SAME AudioGainMath the pipelines apply.
        val step = size.width / (ENVELOPE_SAMPLES - 1)
        val probe = clip.copy(gainKeyframes = draft.value)
        val path = Path()
        for (i in 0 until ENVELOPE_SAMPLES) {
            val t = (i.toLong() * (clipDurationMs - 1)) / (ENVELOPE_SAMPLES - 1).coerceAtLeast(1)
            val g = AudioGainMath.gainAt(probe, t).coerceIn(0f, 2f)
            val x = i * step
            val y = size.height * (1f - g / 2f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = Color(0xFF7FD1C0), style = Stroke(width = 3f))
        // The control points.
        draft.value.forEach { kf ->
            val x = (kf.timeMs.toFloat() / clipDurationMs) * size.width
            val y = size.height * (1f - kf.volume.coerceIn(0f, 2f) / 2f)
            drawCircle(color = Color.White, radius = 6f, center = Offset(x, y))
            drawCircle(color = Color(0xFF1E6E5C), radius = 6f, center = Offset(x, y), style = Stroke(2f))
        }
    }
    Text(
        stringResource(R.string.audio_keyframe_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(
        onClick = {
            draft.value = emptyList()
            viewModel.setAudioKeyframes(clip.id, emptyList())
        },
        enabled = clip.gainKeyframes.isNotEmpty(),
    ) {
        Text(stringResource(R.string.audio_keyframes_clear))
    }
}

private const val ENVELOPE_SAMPLES = 48

private const val AudioTimelineMinMs = 150L

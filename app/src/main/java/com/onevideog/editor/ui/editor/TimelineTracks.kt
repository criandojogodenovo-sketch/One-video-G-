package com.onevideog.editor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.onevideog.editor.R
import com.onevideog.editor.data.AudioClipItem
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind
import com.onevideog.editor.editor.AudioTimeline
import com.onevideog.editor.editor.EditorViewModel
import com.onevideog.editor.ui.home.formatDuration
import kotlin.math.roundToLong

/**
 * Timeline rows for independent audio clips and overlay markers. Every row
 * shares the video track's time scale; blocks can be dragged to move in time
 * and trimmed by their edge handles. All edits commit once per gesture
 * (undo-friendly) and immediately affect preview and export.
 */

private const val MIN_AUDIO_MS = 150L

@Composable
internal fun AudioTrackRow(
    audioClip: AudioClipItem,
    zoom: Float,
    selected: Boolean,
    viewModel: EditorViewModel,
    onOpenTool: (Tool) -> Unit,
) {
    val pxPerMs = 1f / msPerPx(zoom)
    val blockW = (audioClip.trimmedDurationMs * pxPerMs).dp.coerceIn(28.dp, 2000.dp)
    var dragOffsetMs by remember(audioClip.id, audioClip.startMs) { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp),
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(((audioClip.startMs + dragOffsetMs) * pxPerMs).roundToLong().toInt(), 0) }
                .padding(horizontal = 3.dp, vertical = 2.dp)
                .width(blockW)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
                )
                .pointerInput(audioClip.id) {
                    detectTapGestures(onTap = {
                        viewModel.selectAudioClip(audioClip.id)
                        viewModel.selectClip(null)
                        viewModel.selectOverlay(null)
                        onOpenTool(Tool.AUDIO)
                    })
                }
                .pointerInput(audioClip.id, audioClip.startMs, audioClip.trimmedDurationMs) {
                    detectDragGestures(
                        onDragEnd = {
                            if (dragOffsetMs != 0f) {
                                val newStart =
                                    (audioClip.startMs + dragOffsetMs.toLong()).coerceAtLeast(0)
                                viewModel.moveAudioClip(audioClip.id, newStart)
                                dragOffsetMs = 0f
                            }
                        },
                        onDragCancel = { dragOffsetMs = 0f },
                    ) { change, dragAmount ->
                        change.consume()
                        dragOffsetMs += dragAmount.x * msPerPx(zoom)
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .width(14.dp),
            )
            Text(
                audioClip.displayName + " · " + formatDuration(audioClip.trimmedDurationMs) +
                    (if (audioClip.loop) " · ∞" else "") +
                    (if (audioClip.volume != 1f) " · " + (audioClip.volume * 100).toInt() + "%" else ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.padding(start = 20.dp, end = 4.dp),
            )
        }
    }
}

@Composable
internal fun OverlayTrackRow(
    zoom: Float,
    viewModel: EditorViewModel,
    onOpenTool: (Tool) -> Unit,
) {
    val state by viewModel.ui.collectAsState()
    val project = state.project ?: return
    if (project.overlays.isEmpty()) return
    val pxPerMs = 1f / msPerPx(zoom)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp),
    ) {
        project.overlays.forEach { overlay ->
            if (overlay.endMs <= overlay.startMs) return@forEach
            var dragOffsetMs by remember(overlay.id, overlay.startMs) { mutableStateOf(0f) }
            val blockW = ((overlay.endMs - overlay.startMs) * pxPerMs).dp.coerceIn(24.dp, 2000.dp)
            val selected = overlay.id == state.selectedOverlayId
            Box(
                modifier = Modifier
                    .offset { IntOffset(((overlay.startMs + dragOffsetMs) * pxPerMs).roundToLong().toInt(), 0) }
                    .padding(start = 3.dp, top = 2.dp, bottom = 2.dp)
                    .width(blockW)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                    )
                    .pointerInput(overlay.id) {
                        detectTapGestures(onTap = {
                            viewModel.selectOverlay(overlay.id)
                            viewModel.selectClip(null)
                            viewModel.selectAudioClip(null)
                            onOpenTool(if (overlay.isText) Tool.TEXT else Tool.IMAGE)
                        })
                    }
                    .pointerInput(overlay.id, overlay.startMs, overlay.endMs) {
                        detectDragGestures(
                            onDragEnd = {
                                if (dragOffsetMs != 0f) {
                                    val delta = dragOffsetMs.toLong()
                                    viewModel.updateOverlay(overlay.id) {
                                        it.copy(
                                            startMs = (it.startMs + delta).coerceAtLeast(0),
                                            endMs = it.endMs + delta,
                                        )
                                    }
                                    dragOffsetMs = 0f
                                }
                            },
                            onDragCancel = { dragOffsetMs = 0f },
                        ) { change, dragAmount ->
                            change.consume()
                            dragOffsetMs += dragAmount.x * msPerPx(zoom)
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(
                    if (overlay.isText) Icons.Filled.TextFields else Icons.Filled.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(start = 3.dp)
                        .width(12.dp),
                )
                Text(
                    when (overlay.kind) {
                        OverlayKind.TEXT -> overlay.text.take(16)
                        else -> stringResource(R.string.timeline_image_marker)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 17.dp, end = 3.dp),
                )
            }
        }
    }
}

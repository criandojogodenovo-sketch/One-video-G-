package com.onevideog.editor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.onevideog.editor.R
import com.onevideog.editor.data.Clip
import com.onevideog.editor.editor.EditorViewModel
import com.onevideog.editor.editor.TimelineLogic
import com.onevideog.editor.ui.home.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Multi-track timeline: the video track (thumbnails, selection), independent
 * audio track rows and an overlay marker row share one horizontal scroll
 * space and playhead. Widths are proportional to the authoritative
 * [com.onevideog.editor.editor.ProjectMath] durations at the current zoom.
 */
@Composable
fun TimelineStrip(
    viewModel: EditorViewModel,
    onAddAudio: () -> Unit,
    onOpenTool: (Tool) -> Unit,
) {
    val state by viewModel.ui.collectAsState()
    val project = state.project ?: return
    val zoom = state.zoom
    val durationMs = com.onevideog.editor.editor.ProjectMath.projectDurationMs(project)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { viewModel.setZoom(zoom / 1.4f) }) {
                Icon(
                    Icons.Filled.Remove,
                    contentDescription = stringResource(R.string.a11y_zoom_out),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { viewModel.setZoom(zoom * 1.4f) }) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.a11y_zoom_in),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.zoom_label) + " " + String.format("%.1fx", zoom),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.timeline_total) + " " + formatDuration(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val scroll = rememberScrollState()
        val vScroll = rememberScrollState()
        // Keep the playhead visible while playing.
        LaunchedEffect(state.playheadMs, state.isPlaying) {
            if (state.isPlaying) {
                val targetPx = (state.playheadMs / msPerPx(zoom)).toInt()
                if (targetPx < scroll.value || targetPx > scroll.value + 600) {
                    scroll.animateScrollTo(targetPx.coerceAtLeast(0))
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(174.dp),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(vScroll)
                    .horizontalScroll(scroll)
                    .fillMaxHeight()
                    .padding(horizontal = 4.dp),
            ) {
                // Video track
                Row {
                    project.clips.forEachIndexed { index, clip ->
                        ClipBlock(
                            clip = clip,
                            index = index,
                            selected = clip.id == state.selectedClipId,
                            missing = clip.id in state.missingClipIds,
                            zoom = zoom,
                            projectId = project.id,
                            viewModel = viewModel,
                            onClick = {
                                viewModel.selectClip(clip.id)
                                viewModel.selectOverlay(null)
                                viewModel.selectAudioClip(null)
                                viewModel.seekTo(
                                    TimelineLogic.clipStartMs(project.clips, index),
                                )
                            },
                        )
                    }
                    if (project.clips.isEmpty()) {
                        Text(
                            stringResource(R.string.timeline_no_video),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                        )
                    }
                    Spacer(Modifier.width(240.dp))
                }
                // Audio tracks
                project.audioClips.take(4).forEach { audioClip ->
                    AudioTrackRow(
                        audioClip = audioClip,
                        zoom = zoom,
                        selected = audioClip.id == state.selectedAudioClipId,
                        viewModel = viewModel,
                        onOpenTool = onOpenTool,
                    )
                }
                if (project.audioClips.isEmpty()) {
                    Text(
                        stringResource(R.string.timeline_add_audio_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .clickable(onClick = onAddAudio),
                    )
                } else if (project.audioClips.size > 4) {
                    Text(
                        stringResource(
                            R.string.timeline_more_audio,
                            project.audioClips.size - 4,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                // Overlay markers
                OverlayTrackRow(
                    zoom = zoom,
                    viewModel = viewModel,
                    onOpenTool = onOpenTool,
                )
            }
            // Playhead line
            val px = (state.playheadMs / msPerPx(zoom)).dp
            Box(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .offset(x = px + 8.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** Milliseconds represented by one pixel at the given zoom level. */
internal fun msPerPx(zoom: Float): Float = 60f / zoom

@Composable
private fun ClipBlock(
    clip: Clip,
    index: Int,
    selected: Boolean,
    missing: Boolean,
    zoom: Float,
    projectId: String,
    viewModel: EditorViewModel,
    onClick: () -> Unit,
) {
    val widthDp = (clip.effectiveDurationMs / msPerPx(zoom)).dp.coerceIn(48.dp, 620.dp)
    Column(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .width(widthDp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick)
            .padding(3.dp),
    ) {
        val thumbFiles = remember(clip.id, clip.trimEndMs, clip.trimStartMs) {
            viewModel.thumbFilesForClip(clip, projectId)
        }
        if (thumbFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.clip_label, index + 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyRow(modifier = Modifier.height(48.dp)) {
                itemsIndexed(thumbFiles) { _, file ->
                    ThumbImage(path = file.absolutePath, viewModel = viewModel)
                }
            }
        }
        Text(
            (if (missing) stringResource(R.string.editor_clip_missing) + " · " else "") +
                formatDuration(clip.trimStartMs) + "–" + formatDuration(clip.trimEndMs) +
                " · " + formatDuration(clip.effectiveDurationMs) +
                (if (clip.speed != 1f) " · " + String.format("%.2fx", clip.speed) else ""),
            style = MaterialTheme.typography.labelSmall,
            color = if (missing) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun ThumbImage(path: String, viewModel: EditorViewModel) {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) {
            viewModel.loadThumbnail(path)?.asImageBitmap()
        }
    }
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap!!,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(52.dp)
                .fillMaxHeight(),
        )
    } else {
        Box(
            modifier = Modifier
                .width(52.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
        )
    }
}

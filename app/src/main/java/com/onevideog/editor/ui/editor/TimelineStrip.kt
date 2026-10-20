package com.onevideog.editor.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.onevideog.editor.R
import com.onevideog.editor.data.Clip
import com.onevideog.editor.editor.EditorViewModel
import com.onevideog.editor.editor.TimelineScale
import com.onevideog.editor.ui.home.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Multi-track timeline: scrub ruler, video track (proportional thumbnails),
 * independent audio track rows and deterministic overlay lanes share one
 * horizontal scroll space and playhead.
 *
 * Geometry: ONE [TimelineScale] (density-aware, zoom-aware) drives every
 * width, offset, pointer mapping and the playhead — the v0.2.1 mix of dp
 * layout and px pointer math (taps scrubbing to the wrong time at phone
 * densities, blocks misaligned with the ruler) is fixed by construction.
 *
 * Gesture contract:
 *  - The ruler is the ONLY scrub target (tap + drag), through the ViewModel
 *    scrub protocol so player updates can never fight the finger.
 *  - Tapping a clip/bar selects it; bar body drags move; edge handles trim.
 *  - While playing, the viewport follows the playhead; while scrubbing the
 *    finger owns the viewport (no auto-scroll).
 */
@Composable
fun TimelineStrip(
    viewModel: EditorViewModel,
    onAddAudio: () -> Unit,
    onOpenTool: (Tool) -> Unit,
) {
    val state by viewModel.ui.collectAsState()
    val project = state.project ?: return
    val density = LocalDensity.current
    val scale = remember(density.density, state.zoom) { TimelineScale(density.density, state.zoom) }
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
            IconButton(onClick = { viewModel.setZoom(TimelineScale.clampZoom(state.zoom / 1.4f)) }) {
                Icon(
                    Icons.Filled.Remove,
                    contentDescription = stringResource(R.string.a11y_zoom_out),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { viewModel.setZoom(TimelineScale.clampZoom(state.zoom * 1.4f)) }) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.a11y_zoom_in),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.zoom_label) + " " + String.format("%.1fx", state.zoom),
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
        // Real viewport width in px (v0.2.4): the auto-follow window was a
        // hard-coded 600 px — wrong at any density, thrash-prone on 720 px
        // devices like the realme C33. Now the follow window is the actual
        // container width.
        var viewportPx by remember { mutableStateOf(0f) }
        // Keep the playhead visible while playing (instant follow; scrubs do
        // not auto-scroll — the finger owns the viewport while dragging).
        LaunchedEffect(state.playheadMs, state.isPlaying) {
            if (state.isPlaying && !state.isScrubbing) {
                val targetPx = scale.timeToPx(state.playheadMs).toInt()
                val window = (viewportPx.takeIf { it > 0f } ?: 600f) * 0.8f
                if (targetPx < scroll.value || targetPx > scroll.value + window) {
                    scroll.scrollTo(targetPx.coerceAtLeast(0))
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .onGloballyPositioned { viewportPx = it.size.width.toFloat() },
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(vScroll)
                    .horizontalScroll(scroll)
                    .fillMaxHeight()
                    .padding(horizontal = 4.dp),
            ) {
                // Scrub ruler: tap or drag ANYWHERE on it to move the
                // playhead. The one authoritative scrub target — works
                // paused or playing, at any zoom, over any track.
                ScrubRuler(
                    durationMs = durationMs,
                    scale = scale,
                    viewModel = viewModel,
                    zoom = state.zoom,
                    scrollPx = { scroll.value },
                )
                // Video track
                Row {
                    project.clips.forEachIndexed { index, clip ->
                        ClipBlock(
                            clip = clip,
                            index = index,
                            selected = clip.id == state.selectedClipId,
                            missing = clip.id in state.missingClipIds,
                            scale = scale,
                            projectId = project.id,
                            viewModel = viewModel,
                            onClick = {
                                viewModel.selectClip(clip.id)
                                viewModel.selectOverlay(null)
                                viewModel.selectAudioClip(null)
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
                        scale = scale,
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
                // Overlay lanes (deterministic lane packing)
                OverlayTrackLanes(
                    scale = scale,
                    viewModel = viewModel,
                    onOpenTool = onOpenTool,
                )
            }
            // Playhead line — a viewport overlay that tracks the horizontal
            // scroll; the lambda-offset is in px and the scale is px-based,
            // so it lines up with the ruler at every density and zoom.
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (scale.timeToPx(state.playheadMs) + 4.dp.toPx()).roundToInt() -
                                scroll.value,
                            0,
                        )
                    }
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * The time ruler used for scrubbing. Pointer x -> time via the SAME
 * [TimelineScale] as the tracks, clamped to [0, durationMs]. Every write
 * goes through the ViewModel scrub protocol ([EditorViewModel.beginScrub] /
 * [scrubTo] / [endScrub]) so the playhead can never be dragged back by
 * player updates.
 */
@Composable
private fun ScrubRuler(
    durationMs: Long,
    scale: TimelineScale,
    viewModel: EditorViewModel,
    zoom: Float,
    scrollPx: () -> Int,
) {
    val density = LocalDensity.current
    // Ruler covers the project plus 4 s of trailing space.
    val widthDp = with(density) { scale.timeToPx(durationMs + 4_000L).toDp() }
    Box(
        modifier = Modifier
            .width(widthDp)
            .height(26.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .pointerInput(durationMs, scale.zoom, density.density) {
                detectTapGestures(
                    onTap = { offset ->
                        viewModel.seekTo(scale.pxToTimeClamped(offset.x, durationMs))
                    },
                )
            }
            .pointerInput(durationMs, scale.zoom, density.density) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val mapped = scale.pxToTimeClamped(offset.x, durationMs)
                        // v0.2.4: full coordinate evidence — px, mapped time,
                        // scroll, zoom, density — so a device report can
                        // conclusively show whether the mapping or the finger
                        // produced a playhead position.
                        viewModel.beginScrub(
                            pointerXpx = offset.x,
                            mappedMs = mapped,
                            scrollPx = scrollPx(),
                            zoom = zoom,
                            density = density.density,
                            durationMs = durationMs,
                        )
                        viewModel.scrubTo(mapped)
                    },
                    onDragEnd = {
                        viewModel.endScrub(
                            scrollPx = scrollPx(),
                            zoom = zoom,
                            density = density.density,
                            durationMs = durationMs,
                        )
                    },
                    onDragCancel = {
                        viewModel.endScrub(
                            scrollPx = scrollPx(),
                            zoom = zoom,
                            density = density.density,
                            durationMs = durationMs,
                        )
                    },
                ) { change, _ ->
                    change.consume()
                    // Absolute position (not accumulated deltas) so a fast
                    // fling can never desync from the finger.
                    viewModel.scrubTo(scale.pxToTimeClamped(change.position.x, durationMs))
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pxPerSecond = scale.timeToPx(1_000L)
            // Tick every second when zoomed in, every 5 s when zoomed out.
            val stepSeconds = if (pxPerSecond >= 10f) 1 else 5
            val labelStepSeconds = stepSeconds * 5
            val totalSeconds = (durationMs / 1000L).toInt() + 4
            var s = 0
            while (s <= totalSeconds) {
                val x = scale.timeToPx(s * 1000L * stepSeconds)
                if (x > size.width) break
                val isLabel = s % labelStepSeconds == 0
                drawLine(
                    color = if (isLabel) androidx.compose.ui.graphics.Color(0xFF8A8D98)
                    else androidx.compose.ui.graphics.Color(0xFF565964),
                    start = Offset(x, if (isLabel) 6f else size.height - 8f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.5f,
                )
                s += stepSeconds
            }
        }
        // Second labels (only when zoomed in enough to be readable); each
        // label's width is the px-consistent span of 5 seconds.
        if (scale.timeToPx(1_000L) >= 12f) {
            Row(modifier = Modifier.fillMaxHeight()) {
                val labelCount = ((durationMs / 1000L).toInt() + 4) / 5 + 1
                repeat(labelCount.coerceAtMost(600)) { i ->
                    Text(
                        formatDuration(i * 5_000L),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .width(with(density) { scale.timeToPx(5_000L).toDp() })
                            .padding(start = 2.dp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipBlock(
    clip: Clip,
    index: Int,
    selected: Boolean,
    missing: Boolean,
    scale: TimelineScale,
    projectId: String,
    viewModel: EditorViewModel,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    // Proportional to the clip's timeline duration (min width keeps tiny
    // clips touchable; the old 620dp cap that visually compressed long
    // clips and desynced them from the playhead is gone).
    val widthDp = with(density) {
        scale.spanToPx(clip.effectiveDurationMs).toDp().coerceAtLeast(56.dp)
    }
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

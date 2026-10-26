package com.onevideog.editor.ui.editor

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.onevideog.editor.R
import com.onevideog.editor.data.Clip
import com.onevideog.editor.editor.EditorViewModel
import com.onevideog.editor.editor.SpeedRampPlanner
import com.onevideog.editor.editor.TimelineLogic
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
    onOpenTransition: (Pair<String, String>) -> Unit = {},
) {
    val state by viewModel.ui.collectAsState()
    val project = state.project ?: return
    val density = LocalDensity.current
    val scale = remember(density.density, state.zoom) { TimelineScale(density.density, state.zoom) }
    val durationMs = com.onevideog.editor.editor.ProjectMath.projectDurationMs(project)
    // v0.3.0: the timeline renders out to the widest element so bars placed
    // past the video end stay visible and draggable (they render nothing in
    // preview/export — the ruler shades that region). The PLAYHEAD range
    // stays the project duration (video length): the two numbers are
    // intentionally different.
    val spanMs = com.onevideog.editor.editor.ProjectMath.timelineSpanMs(project)

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
                    spanMs = spanMs,
                    scale = scale,
                    viewModel = viewModel,
                    zoom = state.zoom,
                    scrollPx = { scroll.value },
                )
                // Video track — v0.6.0 (H4): between every adjacent pair a
                // TransitionBadge (⚡ when a transition exists, a dim + when
                // the pair has none) opens the TransitionSheet.
                Row {
                    project.clips.forEachIndexed { index, clip ->
                        if (index > 0) {
                            val previous = project.clips[index - 1]
                            val transition = project.transitions.firstOrNull {
                                it.betweenClipIds.first == previous.id &&
                                    it.betweenClipIds.second == clip.id
                            }
                            TransitionBadge(
                                hasTransition = transition != null,
                                onClick = { onOpenTransition(previous.id to clip.id) },
                            )
                        }
                        ClipBlock(
                            clip = clip,
                            index = index,
                            selected = clip.id == state.selectedClipId,
                            missing = clip.id in state.missingClipIds,
                            scale = scale,
                            projectId = project.id,
                            viewModel = viewModel,
                            onClick = {
                                // v0.5.1: unified exclusive selection.
                                viewModel.selectTimelineClip(
                                    com.onevideog.editor.editor.ClipKind.VIDEO, clip.id,
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
                // Audio tracks — v0.8.0 (P2): clips are packed into lanes
                // by AudioLanePacker (deterministic; overlapping clips get
                // different rows, non-overlapping ones share). The lane is
                // DISPLAY-ONLY — it is computed here at render time and
                // never stored in the model; audio still overlaps and mixes
                // in preview/export exactly as before. Every clip stays
                // visible (no row cap — the v0.5.1 contract).
                val videoEndMs = com.onevideog.editor.editor.ProjectMath
                    .videoDurationMs(project.clips)
                val audioAssignments = remember(project.audioClips) {
                    com.onevideog.editor.editor.AudioLanePacker.pack(project.audioClips)
                }
                val audioLaneCount = (audioAssignments.maxOfOrNull { it.lane } ?: -1) + 1
                for (lane in 0 until audioLaneCount) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        audioAssignments
                            .filter { it.lane == lane }
                            .forEach { assignment ->
                                AudioTrackRow(
                                    audioClip = assignment.clip,
                                    scale = scale,
                                    selected = assignment.clip.id == state.selectedAudioClipId,
                                    viewModel = viewModel,
                                    onOpenTool = onOpenTool,
                                    videoEndMs = videoEndMs,
                                )
                            }
                    }
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
 *
 * v0.3.0: the ruler spans [0, spanMs + 4 s] ([spanMs] = widest element) and
 * shades [durationMs, spanMs] as the INACTIVE region — elements placed there
 * persist but render nothing, so the user can see why and drag them back.
 * Scrubbing still clamps to [durationMs] (the playhead never enters the
 * inactive region — there is nothing to preview there).
 */
@Composable
private fun ScrubRuler(
    durationMs: Long,
    spanMs: Long,
    scale: TimelineScale,
    viewModel: EditorViewModel,
    zoom: Float,
    scrollPx: () -> Int,
) {
    val density = LocalDensity.current
    // Ruler covers the widest element plus 4 s of trailing space.
    val widthDp = with(density) { scale.timeToPx(spanMs + 4_000L).toDp() }
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
            // v0.3.0: inactive-region shading — from the video end to the
            // widest element, elements placed there render nothing.
            if (spanMs > durationMs) {
                val deadStart = scale.timeToPx(durationMs)
                drawRect(
                    color = androidx.compose.ui.graphics.Color(0x33FF7A00),
                    topLeft = Offset(deadStart, 0f),
                    size = androidx.compose.ui.geometry.Size(
                        size.width - deadStart,
                        size.height,
                    ),
                )
                // Diagonal hatch marks the region as inactive.
                val step = (6 * density.density)
                var hx = deadStart
                var guard = 0
                while (hx < size.width && guard < 600) {
                    drawLine(
                        color = androidx.compose.ui.graphics.Color(0x55FF7A00),
                        start = Offset(hx, size.height),
                        end = Offset(hx + size.height, 0f),
                        strokeWidth = 1f,
                    )
                    hx += step
                    guard++
                }
            }
            // Tick every second when zoomed in, every 5 s when zoomed out.
            val stepSeconds = if (pxPerSecond >= 10f) 1 else 5
            val labelStepSeconds = stepSeconds * 5
            val totalSeconds = (spanMs / 1000L).toInt() + 4
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
                val labelCount = ((spanMs / 1000L).toInt() + 4) / 5 + 1
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

/**
 * v0.6.0 (H4): the marker between two adjacent video clips. ⚡ when a
 * transition exists (accent tint), a dim + when the pair has none. Tap opens
 * the TransitionSheet for the pair — the badge is a VIEW marker only: the
 * rendered transition lives in the shared effect pipeline (see
 * TransitionEffects), never in this composable.
 */
@Composable
private fun TransitionBadge(hasTransition: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(22.dp)
            .height(48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (hasTransition) Icons.Filled.Bolt else Icons.Filled.Add,
            contentDescription = stringResource(R.string.a11y_transition_badge),
            tint = if (hasTransition) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            },
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * One video clip block. v0.9.3 (CapCut-style trim handles): tapping selects
 * the clip and draws the orange frame with TRIM PEGS on both ends; dragging
 * a peg cuts the start/end in REAL TIME — live block width, live duration
 * chip, a light haptic and a boundary frame preview (the playhead follows
 * the dragged edge). The change commits ONCE at gesture end through the
 * existing [EditorViewModel.trimClip] (one undo step; the split tool and
 * the TRIM sheet keep working exactly as before).
 *
 * Drag math: px -> output ms through the shared [TimelineScale], output ->
 * source ms via the clip speed (exact for constant-speed clips; ramped
 * clips get a close approximation during the drag and the EXACT plan at
 * commit). The preview seek maps the new boundary back through the shared
 * ProjectMath so the player shows the frame at the edge being cut.
 */
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
    val view = LocalView.current
    // Live trim (source ms) — local drag preview; committed once at the end.
    var liveTrimStart by remember(clip.id, clip.trimStartMs) { mutableStateOf<Long?>(null) }
    var liveTrimEnd by remember(clip.id, clip.trimEndMs) { mutableStateOf<Long?>(null) }
    val trimStartMs = liveTrimStart ?: clip.trimStartMs
    val trimEndMs = liveTrimEnd ?: clip.trimEndMs
    val dragging = liveTrimStart != null || liveTrimEnd != null
    val minClipMs = TimelineLogic.MIN_CLIP_SOURCE_MS

    val widthDp = with(density) {
        val liveClip = clip.withTrim(trimStartMs, trimEndMs)
        scale.spanToPx(SpeedRampPlanner.outputDurationMs(liveClip)).toDp().coerceAtLeast(56.dp)
    }

    // Projects the SOURCE boundary ms to the playhead (project time) so the
    // preview shows the frame at the edge being dragged.
    fun seekPreviewToSourceMs(sourceMs: Long) {
        val itemLocal = (sourceMs - clip.trimStartMs).coerceIn(
            0L,
            (clip.trimEndMs - clip.trimStartMs).coerceAtLeast(0),
        )
        val clipStart = TimelineLogic.clipStartMs(
            viewModel.ui.value.project?.clips ?: emptyList(),
            index,
        )
        viewModel.seekTo(clipStart + com.onevideog.editor.editor.ProjectMath
            .itemLocalToProjectMs(clip, itemLocal))
    }

    fun commitLiveTrim() {
        val s = liveTrimStart
        val e = liveTrimEnd
        if (s != null && e != null && (s != clip.trimStartMs || e != clip.trimEndMs)) {
            viewModel.trimClip(clip.id, s, e)
        }
        liveTrimStart = null
        liveTrimEnd = null
    }

    Box(modifier = Modifier.padding(horizontal = 3.dp).width(widthDp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (selected) {
                        Modifier.border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(8.dp),
                        )
                    } else {
                        Modifier
                    },
                )
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
                    formatDuration(trimStartMs) + "–" + formatDuration(trimEndMs) +
                    " · " + formatDuration(trimEndMs - trimStartMs) +
                    (if (clip.speed != 1f) " · " + String.format("%.2fx", clip.speed) else ""),
                style = MaterialTheme.typography.labelSmall,
                color = if (missing) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        // Trim pegs overlay the block edges; the duration chip floats above.
        // Only when the clip is selected — the CapCut interaction contract.
        if (selected) {
            val pegShape = RoundedCornerShape(3.dp)
            // Left peg: drags the clip START.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(22.dp)
                    .height(54.dp)
                    .pointerInput(clip.id, clip.trimStartMs, clip.trimEndMs) {
                        detectDragGestures(
                            onDragStart = { _ ->
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            },
                            onDragEnd = {
                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                commitLiveTrim()
                            },
                            onDragCancel = {
                                liveTrimStart = null
                                liveTrimEnd = null
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            val deltaSource = scale.pxToTime(dragAmount.x) * clip.speed
                            val currentStart = liveTrimStart ?: clip.trimStartMs
                            val maxStart = (clip.trimEndMs - minClipMs).coerceAtLeast(0L)
                            val newStart = (currentStart + deltaSource).roundToLong()
                                .coerceIn(0L, maxStart)
                            if (newStart == currentStart) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            liveTrimStart = newStart
                            liveTrimEnd = clip.trimEndMs
                            seekPreviewToSourceMs(newStart)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(46.dp)
                        .clip(pegShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            // Right peg: drags the clip END.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(22.dp)
                    .height(54.dp)
                    .pointerInput(clip.id, clip.trimStartMs, clip.trimEndMs) {
                        detectDragGestures(
                            onDragStart = { _ ->
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            },
                            onDragEnd = {
                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                commitLiveTrim()
                            },
                            onDragCancel = {
                                liveTrimStart = null
                                liveTrimEnd = null
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            val deltaSource = scale.pxToTime(dragAmount.x) * clip.speed
                            val currentEnd = liveTrimEnd ?: clip.trimEndMs
                            val minEnd = (clip.trimStartMs + minClipMs)
                                .coerceAtMost(clip.sourceDurationMs)
                            val newEnd = (currentEnd + deltaSource).roundToLong()
                                .coerceIn(minEnd, clip.sourceDurationMs)
                            if (newEnd == currentEnd) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            liveTrimStart = clip.trimStartMs
                            liveTrimEnd = newEnd
                            seekPreviewToSourceMs(newEnd - 1)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(46.dp)
                        .clip(pegShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            // Live duration chip (floats above the block while dragging).
            if (dragging) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = (-22).dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        formatDuration(
                            SpeedRampPlanner
                                .outputDurationMs(clip.withTrim(trimStartMs, trimEndMs)),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = com.onevideog.editor.ui.theme.OnOrange,
                    )
                }
            }
        }
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

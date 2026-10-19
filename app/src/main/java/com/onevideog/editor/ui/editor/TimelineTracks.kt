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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.onevideog.editor.R
import com.onevideog.editor.data.AudioClipItem
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind
import com.onevideog.editor.editor.AudioTimeline
import com.onevideog.editor.editor.EditorViewModel
import com.onevideog.editor.editor.LanePacker
import com.onevideog.editor.editor.OverlayEditing
import com.onevideog.editor.editor.TimelineScale
import com.onevideog.editor.ui.home.formatDuration
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Timeline rows for independent audio clips and packed overlay lanes. Every
 * row shares the video track's time scale through [TimelineScale] — the ONE
 * px-consistent mapping (the v0.2.1 density mismatch between dp widths and
 * px offsets/pointer positions is fixed by construction).
 *
 * Overlay lanes: [LanePacker] assigns each overlay a display row so two
 * overlapping overlays can never overwrite each other. Bars are selectable,
 * movable by dragging the body, and trimmable from separate left/right edge
 * handles; draw order stays the explicit z-order property.
 *
 * All edits commit once per gesture (undo-friendly) and immediately affect
 * preview and export.
 */

private const val MIN_AUDIO_MS = 150L

@Composable
internal fun AudioTrackRow(
    audioClip: AudioClipItem,
    scale: TimelineScale,
    selected: Boolean,
    viewModel: EditorViewModel,
    onOpenTool: (Tool) -> Unit,
) {
    var liveStart by remember(audioClip.id, audioClip.startMs) { mutableStateOf<Long?>(null) }
    var liveTrimStart by remember(audioClip.id, audioClip.trimStartMs) { mutableStateOf<Long?>(null) }
    var liveTrimEnd by remember(audioClip.id, audioClip.trimEndMs) { mutableStateOf<Long?>(null) }

    val startMs = liveStart ?: audioClip.startMs
    val trimStartMs = liveTrimStart ?: audioClip.trimStartMs
    val trimEndMs = liveTrimEnd ?: audioClip.trimEndMs
    val durationMs = (trimEndMs - trimStartMs).coerceAtLeast(1)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp),
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(scale.timeToPx(startMs).roundToInt(), 0) }
                .padding(horizontal = 3.dp, vertical = 2.dp)
                .width(with(LocalDensity.current) { scale.spanToPx(durationMs).toDp() })
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
                )
                // Body: tap selects; drag moves the clip in time.
                .pointerInput(audioClip.id, audioClip.startMs) {
                    detectTapGestures(onTap = {
                        viewModel.selectAudioClip(audioClip.id)
                        viewModel.selectClip(null)
                        viewModel.selectOverlay(null)
                        onOpenTool(Tool.AUDIO)
                    })
                }
                .pointerInput(audioClip.id, audioClip.startMs, audioClip.trimStartMs, audioClip.trimEndMs) {
                    detectDragGestures(
                        onDragEnd = {
                            liveStart?.let { viewModel.moveAudioClip(audioClip.id, it) }
                            liveStart = null
                            liveTrimStart = null
                            liveTrimEnd = null
                        },
                        onDragCancel = {
                            liveStart = null
                            liveTrimStart = null
                            liveTrimEnd = null
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        val deltaMs = scale.pxToTime(dragAmount.x)
                        val current = liveStart ?: audioClip.startMs
                        val requested = (current + deltaMs).coerceAtLeast(0)
                        // Moving the bar also slides the source window (time
                        // trim). The slide is bounded by the source range and
                        // by the timeline start.
                        val maxDelta = minOf(
                            audioClip.sourceDurationMs - MIN_AUDIO_MS - audioClip.trimStartMs,
                            audioClip.sourceDurationMs - audioClip.trimEndMs,
                        )
                        val minDelta = maxOf(-audioClip.trimStartMs, -audioClip.startMs)
                        val effectiveDelta = (requested - audioClip.startMs)
                            .coerceIn(minDelta, maxDelta.coerceAtLeast(minDelta))
                        liveTrimStart = audioClip.trimStartMs + effectiveDelta
                        liveTrimEnd = audioClip.trimEndMs + effectiveDelta
                        liveStart = (audioClip.startMs + effectiveDelta).coerceAtLeast(0)
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(14.dp),
                )
                Text(
                    audioClip.displayName + " · " + formatDuration(durationMs) +
                        (if (audioClip.loop) " · ∞" else "") +
                        (if (audioClip.volume != 1f) " · " + (audioClip.volume * 100).toInt() + "%" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 4.dp, end = 20.dp),
                )
            }
            // Left/right trim edge handles (on top of the body).
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(18.dp)
                    .fillMaxHeight()
                    .pointerInput(audioClip.id, audioClip.trimStartMs, audioClip.startMs) {
                        detectDragGestures(
                            onDragEnd = {
                                liveTrimStart?.let {
                                    viewModel.trimAudioClip(
                                        audioClip.id, it, audioClip.trimEndMs, liveStart,
                                    )
                                }
                                liveStart = null
                                liveTrimStart = null
                                liveTrimEnd = null
                            },
                            onDragCancel = {
                                liveStart = null
                                liveTrimStart = null
                                liveTrimEnd = null
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            val deltaMs = scale.pxToTime(dragAmount.x)
                            val currentTrim = liveTrimStart ?: audioClip.trimStartMs
                            val newTrim = (currentTrim + deltaMs)
                                .coerceIn(0L, audioClip.trimEndMs - MIN_AUDIO_MS)
                            val effectiveDelta = newTrim - audioClip.trimStartMs
                            liveTrimStart = newTrim
                            liveTrimEnd = audioClip.trimEndMs
                            liveStart = (audioClip.startMs + effectiveDelta).coerceAtLeast(0)
                        }
                    },
            ) {
                Box(
                    modifier = Modifier
                        .padding(3.dp)
                        .width(3.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.tertiary),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(18.dp)
                    .fillMaxHeight()
                    .pointerInput(audioClip.id, audioClip.trimEndMs) {
                        detectDragGestures(
                            onDragEnd = {
                                liveTrimEnd?.let {
                                    viewModel.trimAudioClip(
                                        audioClip.id, audioClip.trimStartMs, it, null,
                                    )
                                }
                                liveTrimEnd = null
                            },
                            onDragCancel = { liveTrimEnd = null },
                        ) { change, dragAmount ->
                            change.consume()
                            val deltaMs = scale.pxToTime(dragAmount.x)
                            val currentEnd = liveTrimEnd ?: audioClip.trimEndMs
                            liveTrimEnd = (currentEnd + deltaMs)
                                .coerceIn(audioClip.trimStartMs + MIN_AUDIO_MS, audioClip.sourceDurationMs)
                        }
                    },
            ) {
                Box(
                    modifier = Modifier
                        .padding(3.dp)
                        .width(3.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.tertiary),
                )
            }
        }
    }
}

private const val MAX_LANES_DRAWN = 4

/**
 * Overlay timeline lanes: deterministic [LanePacker] rows; overlapping
 * overlays each get their own lane. Every bar shows a type icon + name,
 * keyframe markers, supports select (tap), move (body drag) and trim
 * (left/right edge handles).
 */
@Composable
internal fun OverlayTrackLanes(
    scale: TimelineScale,
    viewModel: EditorViewModel,
    onOpenTool: (Tool) -> Unit,
) {
    val state by viewModel.ui.collectAsState()
    val project = state.project ?: return
    if (project.overlays.isEmpty()) return
    val assignments = remember(project.overlays) { LanePacker.pack(project.overlays) }
    val laneCount = (assignments.maxOfOrNull { it.lane } ?: -1) + 1
    val visibleLanes = laneCount.coerceAtMost(MAX_LANES_DRAWN)

    for (lane in 0 until visibleLanes) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp),
        ) {
            assignments
                .filter { it.lane == lane }
                .forEach { assignment ->
                    OverlayBar(
                        overlay = assignment.overlay,
                        scale = scale,
                        selected = assignment.overlay.id == state.selectedOverlayId,
                        viewModel = viewModel,
                        onOpenTool = onOpenTool,
                    )
                }
        }
    }
    if (laneCount > visibleLanes) {
        Text(
            "+" + (laneCount - visibleLanes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun OverlayBar(
    overlay: OverlayItem,
    scale: TimelineScale,
    selected: Boolean,
    viewModel: EditorViewModel,
    onOpenTool: (Tool) -> Unit,
) {
    var liveStart by remember(overlay.id, overlay.startMs) { mutableStateOf<Long?>(null) }
    var liveEnd by remember(overlay.id, overlay.endMs) { mutableStateOf<Long?>(null) }
    val startMs = liveStart ?: overlay.startMs
    val endMs = liveEnd ?: overlay.endMs
    val duration = (endMs - startMs).coerceAtLeast(1)
    val barW = with(LocalDensity.current) { scale.spanToPx(duration).toDp() }

    Box(
        modifier = Modifier
            .offset { IntOffset(scale.timeToPx(startMs).roundToInt(), 0) }
            .padding(start = 3.dp, top = 2.dp, bottom = 2.dp)
            .width(barW)
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
            )
            // Body: tap selects (and opens the editor); drag moves in time.
            .pointerInput(overlay.id, overlay.startMs) {
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
                        liveStart?.let { viewModel.moveOverlay(overlay.id, it) }
                        liveStart = null
                        liveEnd = null
                    },
                    onDragCancel = {
                        liveStart = null
                        liveEnd = null
                    },
                ) { change, dragAmount ->
                    change.consume()
                    val deltaMs = scale.pxToTime(dragAmount.x)
                    val currentStart = liveStart ?: overlay.startMs
                    val requested = (currentStart + deltaMs).coerceAtLeast(0)
                    liveStart = requested
                    liveEnd = (liveEnd ?: overlay.endMs) + (requested - currentStart)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (overlay.isText) Icons.Filled.TextFields else Icons.Filled.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(start = 3.dp)
                    .size(12.dp),
            )
            Text(
                when (overlay.kind) {
                    OverlayKind.TEXT -> overlay.text.take(16)
                    else -> stringResource(R.string.timeline_image_marker)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.padding(start = 3.dp, end = 20.dp),
            )
        }
        // Keyframe markers: one dot per keyframe at its time inside the bar.
        if (overlay.keyframes.isNotEmpty()) {
            Row {
                overlay.keyframes.sortedBy { it.timeMs }.forEach { keyframe ->
                    val dx = ((keyframe.timeMs - overlay.startMs).coerceAtLeast(0))
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(scale.timeToPx(dx).roundToInt(), 0) }
                            .padding(start = 2.dp)
                            .size(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.secondary),
                    )
                }
            }
        }
        // Left edge trim handle (on top of the body).
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(16.dp)
                .fillMaxHeight()
                .pointerInput(overlay.id, overlay.startMs, overlay.endMs) {
                    detectDragGestures(
                        onDragEnd = {
                            liveStart?.let { viewModel.trimOverlayStart(overlay.id, it) }
                            liveStart = null
                        },
                        onDragCancel = { liveStart = null },
                    ) { change, dragAmount ->
                        change.consume()
                        val deltaMs = scale.pxToTime(dragAmount.x)
                        val currentStart = liveStart ?: overlay.startMs
                        liveStart = OverlayEditing.clampTrimStart(
                            currentStart + deltaMs,
                            overlay.endMs,
                            OverlayEditing.MIN_OVERLAY_MS,
                        )
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .padding(3.dp)
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        // Right edge trim handle.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(16.dp)
                .fillMaxHeight()
                .pointerInput(overlay.id, overlay.startMs, overlay.endMs) {
                    detectDragGestures(
                        onDragEnd = {
                            liveEnd?.let { viewModel.trimOverlayEnd(overlay.id, it) }
                            liveEnd = null
                        },
                        onDragCancel = { liveEnd = null },
                    ) { change, dragAmount ->
                        change.consume()
                        val deltaMs = scale.pxToTime(dragAmount.x)
                        val currentEnd = liveEnd ?: overlay.endMs
                        liveEnd = OverlayEditing.clampTrimEnd(
                            overlay.startMs,
                            currentEnd + deltaMs,
                            OverlayEditing.MIN_OVERLAY_MS,
                        )
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .padding(3.dp)
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

package com.onevideog.editor.ui.editor

import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.TextureView
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.onevideog.editor.R
import com.onevideog.editor.data.OverlayTransform
import com.onevideog.editor.data.ProjectAspect
import com.onevideog.editor.editor.AudioPreviewController
import com.onevideog.editor.editor.ColorMatrixMath
import com.onevideog.editor.editor.EditorViewModel
import com.onevideog.editor.editor.OverlayLayout
import com.onevideog.editor.editor.ProjectMath
import com.onevideog.editor.editor.TimelineLogic
import com.onevideog.editor.ui.home.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Real-time composition preview: the project model is rendered here with the
 * same pure math the export pipeline uses ([ProjectMath], [OverlayLayout],
 * [KeyframeEvaluator], [AudioTimeline]).
 *
 * - Video: ExoPlayer on the active clip (item timelines are 0-based after
 *   clipping — the same time base the export overlay windows use).
 * - Overlays: drawn by [OverlayLayer] on a canvas clipped to the content
 *   rect, so letterboxing behaves exactly like the exported frame.
 * - Audio: [AudioPreviewController] plays independent audio clips with the
 *   export's segment plan.
 * - Colours: a hardware-layer ColorMatrixColorFilter built by the same
 *   [ColorMatrixMath] as the export RgbMatrix (sRGB vs linear-RGB pipeline
 *   difference documented in docs/PREVIEW_EXPORT_PARITY.md).
 */
@Composable
fun PreviewStage(viewModel: EditorViewModel) {
    val state by viewModel.ui.collectAsState()
    val project = state.project ?: return
    val context = LocalContext.current

    val durationMs = ProjectMath.projectDurationMs(project)
    val clipIndex = TimelineLogic.clipIndexAt(project.clips, state.playheadMs)
    val currentClip = project.clips.getOrNull(clipIndex)
    val clipStart = TimelineLogic.clipStartMs(project.clips, clipIndex)

    // Content aspect: project aspect, or the active clip's source aspect.
    val contentAspect = when (val aspect = project.aspectEnum) {
        ProjectAspect.SOURCE -> currentClip?.sourceAspect ?: 16f / 9f
        else -> aspect.ratio() ?: 16f / 9f
    }

    // ------------------------------ audio preview -----------------------------
    val audioController = remember { AudioPreviewController(context) }

    // ------------------------------ video player ------------------------------
    val player = remember {
        ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_OFF }
    }
    DisposableEffect(Unit) {
        onDispose {
            player.release()
            audioController.release()
        }
    }

    var loadedClipId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentClip?.id, viewModel.seekToken.collectAsState().value) {
        if (currentClip == null) {
            player.stop()
            loadedClipId = null
            return@LaunchedEffect
        }
        // Item position is 0-based within the clip window (Media3 resets
        // timestamps at the clipping start), scaled by playback speed.
        val localMs = ProjectMath.projectToItemLocalMs(
            currentClip,
            (state.playheadMs - clipStart).coerceAtLeast(0),
        )
        val item = MediaItem.Builder()
            .setUri(android.net.Uri.parse(currentClip.uri))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(currentClip.trimStartMs)
                    .setEndPositionMs(currentClip.trimEndMs)
                    .build(),
            )
            .build()
        player.setMediaItem(item, localMs.coerceAtMost(currentClip.effectiveDurationMs))
        player.setPlaybackSpeed(currentClip.speed)
        player.prepare()
        player.playWhenReady = state.isPlaying
        loadedClipId = currentClip.id
    }

    // Frame-driven playhead updates while playing (smooth keyframe animation,
    // throttled to ~30fps to stay light on 4 GB devices).
    LaunchedEffect(state.isPlaying, loadedClipId) {
        var lastPublish = 0L
        while (isActive && state.isPlaying && currentClip != null) {
            val now = withFrameNanos { it }
            val pos = player.currentPosition
            val windowDur = currentClip.effectiveDurationMs
            if (pos >= windowDur - 40) {
                if (clipIndex < project.clips.lastIndex) {
                    viewModel.seekTo(clipStart + currentClip.effectiveDurationMs)
                } else {
                    viewModel.setPlaying(false)
                    viewModel.seekTo(durationMs)
                }
                break
            } else if (now - lastPublish >= 33_000_000L) {
                lastPublish = now
                viewModel.setPlayhead(
                    clipStart + ProjectMath.itemLocalToProjectMs(currentClip, pos),
                )
            }
        }
    }

    // ------------------------------ audio preview sync ------------------------
    val audioClips = project.audioClips
    LaunchedEffect(viewModel.seekToken.collectAsState().value, audioClips) {
        withContext(Dispatchers.Default) {
            audioController.sync(audioClips, state.playheadMs, state.isPlaying, ProjectMath.videoDurationMs(project.clips))
        }
    }
    LaunchedEffect(state.isPlaying, audioClips) {
        while (isActive) {
            audioController.sync(
                audioClips, state.playheadMs, state.isPlaying,
                ProjectMath.videoDurationMs(project.clips),
            )
            delay(120)
        }
    }

    // ------------------------------ colour filter -----------------------------
    val colorPaint = remember { Paint() }

    // ------------------------------ viewport ----------------------------------
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val containerW = with(density) { maxWidth.toPx() }
        val containerH = with(density) { maxHeight.toPx() }
        val fitted = OverlayLayout.fitRect(containerW, containerH, contentAspect)
        val fittedW = with(density) { fitted.width.toDp() }
        val fittedH = with(density) { fitted.height.toDp() }

        Box(
            modifier = Modifier
                .width(fittedW)
                .height(fittedH),
        ) {
            AndroidView(
                factory = { ctx ->
                    android.view.LayoutInflater.from(ctx)
                        .inflate(R.layout.view_player_texture, null, false) as
                        androidx.media3.ui.PlayerView
                },
                update = { view ->
                    view.player = player
                    val color = currentClip?.color
                    val texture = view.videoSurfaceView as? TextureView
                    if (color != null && !color.isDefault) {
                        colorPaint.colorFilter = ColorMatrixColorFilter(
                            android.graphics.ColorMatrix(
                                ColorMatrixMath.androidColorMatrix45(
                                    color.brightness, color.contrast, color.saturation,
                                ),
                            ),
                        )
                        texture?.setLayerType(View.LAYER_TYPE_HARDWARE, colorPaint)
                    } else {
                        texture?.setLayerType(View.LAYER_TYPE_NONE, null)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            var gestureState by remember {
                mutableStateOf<Pair<String, OverlayTransform>?>(null)
            }
            OverlayLayer(
                overlays = project.overlays,
                playheadMs = { state.playheadMs },
                contentAspect = { contentAspect },
                contentWidthPx = { fitted.width },
                contentHeightPx = { fitted.height },
                selectedOverlayId = { state.selectedOverlayId },
                gestureOverride = { gestureState },
                onTap = { overlay ->
                    viewModel.selectOverlay(overlay?.id)
                    overlay?.let { viewModel.selectClip(null); viewModel.selectAudioClip(null) }
                },
                onGesture = { overlay, transform ->
                    gestureState = overlay.id to transform
                },
                onGestureEnd = { overlay, transform ->
                    gestureState = null
                    viewModel.updateOverlay(overlay.id) {
                        it.copy(transform = transform.withClampedValues())
                    }
                },
            )
        }
    }

    // ------------------------------ transport ---------------------------------
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = { viewModel.setPlaying(!state.isPlaying) }) {
            Icon(
                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (state.isPlaying) R.string.a11y_pause else R.string.a11y_play,
                ),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp),
            )
        }
        Text(
            formatDuration(state.playheadMs) + " / " + formatDuration(durationMs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (project.audioClips.isNotEmpty()) {
            Text(
                stringResource(R.string.preview_audio_active, project.audioClips.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

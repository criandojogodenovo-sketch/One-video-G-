package com.onevideog.editor.ui.editor

import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.TextureView
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Real-time composition preview: the project model is rendered here with the
 * same pure math the export pipeline uses ([ProjectMath], [OverlayLayout],
 * [KeyframeEvaluator], [AudioTimeline]).
 *
 * Playback contract:
 * - The ViewModel owns ONE authoritative playhead (`state.playheadMs`).
 * - `state.isPlaying` is mirrored to the ExoPlayer in its own effect, so the
 *   Play/Pause button always starts/stops the player (v0.2.0 only set
 *   `playWhenReady` inside the media-load effect, so the button did nothing).
 * - Scrubbing: while `state.isScrubbing`, the user's pointer owns the
 *   playhead and the periodic publisher is disabled; the player is only
 *   seeked when the drag ends (or by a transport seek). The publisher also
 *   refuses to publish while the player is not actually `isPlaying`, so an
 *   async seek can never drag the playhead back to the old position.
 * - Same-clip seeks call `player.seekTo` (fast, keeps the decoder); the media
 *   item is only rebuilt when the active clip changes.
 * - Audio: [AudioPreviewController] is driven from one main-thread loop and
 *   reports decoder failures via [EditorViewModel.audioPreviewError].
 * - The transport row lives in [EditorScreen] BELOW the canvas — the preview
 *   area itself stays free for overlay manipulation.
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
    val audioController = remember {
        AudioPreviewController(context).apply {
            onClipError = { _, code -> viewModel.audioPreviewError.value = code }
        }
    }

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

    // Keep the icon/state and the player in lock-step: STATE_ENDED and decode
    // errors flip `isPlaying` off so the UI never shows a phantom "playing".
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) viewModel.setPlaying(false)
            }

            override fun onPlayerError(error: PlaybackException) {
                viewModel.setPlaying(false)
                viewModel.playbackError.value = error.errorCodeName
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
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
        if (loadedClipId == currentClip.id) {
            // Same clip: a seek is enough — never tear the decoder down
            // (the v0.2.0 full reload made every seek slow and let the old
            // position leak back into the playhead).
            player.setPlaybackSpeed(currentClip.speed)
            player.seekTo(localMs.coerceAtMost(currentClip.effectiveDurationMs))
            player.playWhenReady = state.isPlaying
        } else {
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
    }

    // Play/Pause MUST reach the player even when nothing else changed —
    // this dedicated effect is the actual button → player wiring.
    LaunchedEffect(state.isPlaying) {
        player.playWhenReady = state.isPlaying
    }

    // Frame-driven playhead updates while playing (smooth keyframe animation,
    // throttled to ~30fps to stay light on 4 GB devices). Guards:
    // - `state.isScrubbing`: the finger owns the playhead;
    // - `!player.isPlaying`: seeking/buffering/ended — publishing the stale
    //   player position here is exactly the "scrub reverts" bug.
    LaunchedEffect(state.isPlaying, loadedClipId) {
        var lastPublish = 0L
        while (isActive && state.isPlaying) {
            val now = withFrameNanos { it }
            if (state.isScrubbing) continue
            val clip = currentClip ?: break
            if (!player.isPlaying) continue
            val pos = player.currentPosition
            val windowDur = clip.effectiveDurationMs
            if (pos >= windowDur - 40) {
                if (clipIndex < project.clips.lastIndex) {
                    viewModel.seekTo(clipStart + windowDur)
                } else {
                    viewModel.setPlaying(false)
                    viewModel.seekTo(durationMs)
                }
                break
            } else if (now - lastPublish >= 33_000_000L) {
                lastPublish = now
                viewModel.setPlayhead(
                    clipStart + ProjectMath.itemLocalToProjectMs(clip, pos),
                )
            }
        }
    }

    // ------------------------ audio preview sync (main) -----------------------
    val audioClips = project.audioClips
    val videoDurationMs = ProjectMath.videoDurationMs(project.clips)
    LaunchedEffect(state.isPlaying, audioClips, viewModel.seekToken.collectAsState().value) {
        while (isActive) {
            audioController.sync(
                audioClips, state.playheadMs, state.isPlaying, videoDurationMs,
            )
            delay(100)
        }
    }

    // ------------------------------ colour filter -----------------------------
    val colorPaint = remember { Paint() }

    // ------------------------------ viewport ----------------------------------
    BoxWithConstraints(
        modifier = Modifier
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
}

package com.onevideog.editor.ui.editor

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
import com.onevideog.editor.editor.ClipEffectsFactory
import com.onevideog.editor.editor.EditorViewModel
import com.onevideog.editor.editor.OverlayLayout
import com.onevideog.editor.editor.ProjectMath
import com.onevideog.editor.editor.TimelineLogic
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Real-time composition preview.
 *
 * Rendering pipeline (v0.2.2): the active clip's video AND its overlays and
 * colour adjustments are rendered by the Media3 GL pipeline through
 * `ExoPlayer.setVideoEffects(...)` with the SAME effect list the export
 * composition builds ([ClipEffectsFactory]) — one shared implementation, no
 * duplicated timeline/effect math. The Compose overlay canvas draws only
 * editor chrome + live gesture previews, plus a fallback content render when
 * there is no active video (overlays over black) or the player is not ready.
 *
 * Effects lifecycle (verified against Media3 1.4.1 sources):
 *  - `setVideoEffects` MUST be called before `prepare` for the first item;
 *  - a changed effect list applies at the NEXT input stream, so after any
 *    overlay/colour/speed edit the list is rebuilt and the current item is
 *    re-prepared at the current position (one decoder init per commit).
 *
 * Playback contract (unchanged from v0.2.1):
 * - The ViewModel owns ONE authoritative playhead (`state.playheadMs`).
 * - `state.isPlaying` is mirrored to the player in its own effect.
 * - While `state.isScrubbing`, the pointer owns the playhead; the periodic
 *   publisher refuses to publish unless the player is actually playing, so
 *   an async seek can never drag the playhead back to the old position.
 * - Same-clip seeks call `player.seekTo` (keeps the decoder); the media item
 *   is only rebuilt when the active clip (or the effects) change.
 * - Audio: [AudioPreviewController] is driven from one main-thread loop and
 *   reports decoder failures via [EditorViewModel.audioPreviewError].
 * - The transport row lives in [EditorScreen] BELOW the canvas.
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
    // The preview effect list ends with the same Presentation the export
    // applies, so the GL frame geometry matches the exported frame exactly.
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

    // Player readiness drives the canvas fallback (overlays must stay visible
    // during media switches and for overlay-only projects over black).
    var playerReady by remember { mutableStateOf(false) }
    // Keep the icon/state and the player in lock-step: STATE_ENDED and decode
    // errors flip `isPlaying` off so the UI never shows a phantom "playing".
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                playerReady = playbackState == Player.STATE_READY
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

    // ------------------- shared effect pipeline (preview) ---------------------
    // Revision key: clip identity + every project input the effect list
    // depends on. Any overlay add/delete/edit, colour, speed or aspect change
    // rebuilds the list and forces a new input stream so Media3 applies it.
    val effectsRevision = remember(currentClip, project.aspect, project.overlays) {
        listOf(
            currentClip?.id,
            currentClip?.trimStartMs,
            currentClip?.trimEndMs,
            currentClip?.speed,
            currentClip?.color,
            project.aspect,
            project.overlays.hashCode(),
        ).hashCode()
    }

    fun mediaItemFor(clip: com.onevideog.editor.data.Clip): MediaItem =
        MediaItem.Builder()
            .setUri(android.net.Uri.parse(clip.uri))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.trimStartMs)
                    .setEndPositionMs(clip.trimEndMs)
                    .build(),
            )
            .build()

    var loadedClipId by remember { mutableStateOf<String?>(null) }

    // Effect application — MUST run before the media-load effect below so the
    // first prepare() happens with the effects already set.
    LaunchedEffect(effectsRevision) {
        val clip = currentClip ?: return@LaunchedEffect
        val effects = ClipEffectsFactory.effectsFor(
            context = context,
            project = project,
            clip = clip,
            clipStartMs = clipStart,
            forPreview = true,
        )
        player.setVideoEffects(effects)
        // When the same clip is already loaded, force a new input stream so
        // the pending effects apply immediately; otherwise the media-load
        // effect will prepare the item with the fresh list.
        if (loadedClipId == clip.id) {
            val pos = runCatching { player.currentPosition }.getOrDefault(0L).coerceAtLeast(0)
            player.setMediaItem(mediaItemFor(clip), pos)
            player.setPlaybackSpeed(clip.speed)
            player.prepare()
            player.playWhenReady = viewModel.ui.value.isPlaying
        }
    }

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
            // Same clip: a seek is enough — never tear the decoder down.
            player.setPlaybackSpeed(currentClip.speed)
            player.seekTo(localMs.coerceAtMost(currentClip.effectiveDurationMs))
            player.playWhenReady = state.isPlaying
        } else {
            player.setMediaItem(
                mediaItemFor(currentClip),
                localMs.coerceAtMost(currentClip.effectiveDurationMs),
            )
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
                renderContentFallback = { currentClip == null || !playerReady },
                onTap = { overlay ->
                    // Clear any stale gesture override (a tap ends the
                    // gesture without committing a transform).
                    gestureState = null
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
                onDelete = { overlay ->
                    viewModel.selectOverlay(null)
                    viewModel.removeOverlay(overlay.id)
                },
            )
        }
    }
}

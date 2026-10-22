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
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.onevideog.editor.R
import com.onevideog.editor.data.OverlayTransform
import com.onevideog.editor.data.ProjectAspect
import com.onevideog.editor.diag.DiagContext
import com.onevideog.editor.diag.DiagSanitizer
import com.onevideog.editor.diag.DiagnosticsLogger
import com.onevideog.editor.diag.OpRegistry
import com.onevideog.editor.editor.AudioPreviewController
import com.onevideog.editor.editor.ClipEffectPlan
import com.onevideog.editor.editor.ClipEffectsFactory
import com.onevideog.editor.editor.EditorViewModel
import com.onevideog.editor.editor.OverlayLayout
import com.onevideog.editor.editor.PlayheadGate
import com.onevideog.editor.editor.ProjectMath
import com.onevideog.editor.editor.TimelineLogic
import com.onevideog.editor.editor.ViewportGeometryTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Bounded playback-state name for diagnostics (no reflection, no churn). */
private fun playbackStateName(state: Int): String = when (state) {
    Player.STATE_IDLE -> "IDLE"
    Player.STATE_BUFFERING -> "BUFFERING"
    Player.STATE_READY -> "READY"
    Player.STATE_ENDED -> "ENDED"
    else -> "#$state"
}

/** The ONE MediaItem builder for the preview player (clip window preserved). */
private fun mediaItemFor(clip: com.onevideog.editor.data.Clip): MediaItem =
    MediaItem.Builder()
        .setUri(android.net.Uri.parse(clip.uri))
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(clip.trimStartMs)
                .setEndPositionMs(clip.trimEndMs)
                .build(),
        )
        .build()

/** v0.5.7 (ponto 4): bounded onPlayerError recovery attempts before giving up. */
private const val PLAYER_RECOVERY_MAX_ATTEMPTS = 3

/**
 * Real-time composition preview.
 *
 * Rendering pipeline (v0.3.1): the active clip's video, speed and colour
 * adjustments are rendered by the Media3 GL pipeline through
 * `ExoPlayer.setVideoEffects(...)` from the SAME factory the export
 * composition builds ([ClipEffectsFactory]) — one shared implementation, no
 * duplicated timeline/effect math. Overlay pixels are drawn by the Compose
 * [OverlayLayer] on top of the PlayerView, ALWAYS visible for every overlay
 * active at the current playhead (v0.3.1 device fix — the ExoPlayer GL
 * pipeline did not composite overlay textures on the affected device, so
 * overlays only appeared while selected/manipulated; the export path was
 * unaffected). Both renderers share the normalized-position model, so the
 * preview matches the exported frame.
 *
 * Effects lifecycle (verified against Media3 1.4.1 sources):
 *  - `setVideoEffects` MUST be called before `prepare` for the first item;
 *  - a changed effect list applies at the NEXT input stream, so after any
 *    colour/speed/aspect edit the list is rebuilt and the current item is
 *    re-prepared at the current position (one decoder init per commit).
 *    v0.3.1: overlay edits do NOT rebuild — the preview list has no
 *    OverlayEffect and the Compose layer re-renders from project state.
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
    // v0.2.4: the surface detach timeout is raised from the 2 s default for
    // this device class. Media3 1.4.1 (ExoPlayerImpl.setVideoOutputInternal)
    // blocks up to detachSurfaceTimeoutMs waiting for the playback thread to
    // detach the TextureView; the GL effects pipeline re-init on a Unisoc
    // T612 can exceed 2 s, which produced the reported
    // ExoTimeoutException("Detaching surface timed out.") via onPlayerError.
    val player = remember {
        ExoPlayer.Builder(context)
            .setDetachSurfaceTimeoutMs(10_000)
            .build()
            .apply { repeatMode = Player.REPEAT_MODE_OFF }
    }
    // One preview correlation id per composition instance: every effect
    // rebuild / player error / activation event of this session shares it.
    val previewOpId = remember { OpRegistry.newOpId("pvw") }
    DisposableEffect(Unit) {
        onDispose {
            // v0.2.4 dispose sequence: quiesce BEFORE release so the playback
            // thread is not mid-frame when the surface detaches (reduces the
            // detach-timeout window), then release. A release failure on a
            // slow GL pipeline is RECORDED with full detail and must not
            // crash composition teardown — the player keeps releasing in the
            // background; rethrowing here would kill the editor AFTER the
            // player is already gone. Not a silent swallow: it is logged.
            runCatching { player.playWhenReady = false }
            try {
                player.release()
            } catch (t: Throwable) {
                DiagnosticsLogger.e(
                    "playback", "PLAYER_RELEASE", previewOpId,
                    "player release threw (surface detach timed out; " +
                        "release continues in background)",
                    t,
                )
            }
            audioController.release()
        }
    }

    // v0.2.5 geometry evidence: the three REAL dimension sources are tracked
    // separately (master-prompt item 2 — "PREVIEW_RENDER w=0 h=0"):
    //   video  = decoder output geometry (onVideoSizeChanged)
    //   surface= the TextureView/PlayerView layout size
    //   viewport = the fitted content box inside the stage container
    var videoSize by remember { mutableStateOf(VideoSize.UNKNOWN) }
    var surfaceSize by remember { mutableStateOf(0 to 0) }
    val geometryTracker = remember { ViewportGeometryTracker() }
    // v0.5.7 (ponto 5): the CLIP's declared video geometry (probed at import
    // and persisted with the project) is authoritative when the decoder has
    // not reported a size (video=none in every VIEWPORT_GEOMETRY of the
    // 2026-10-21 report — onVideoSizeChanged never fired even with clips).
    // The preview layout never depended on the player (fitRect uses
    // contentAspect); this makes the reported GEOMETRY equally independent.
    val clipVideoSize = remember(currentClip?.id) {
        val c = currentClip
        if (c != null && c.width > 0 && c.height > 0) {
            VideoSize(c.width, c.height, 0, 1f)
        } else {
            VideoSize.UNKNOWN
        }
    }
    val effectiveVideoSize =
        if (videoSize.width > 0 && videoSize.height > 0) videoSize else clipVideoSize
    // The clip currently prepared in the player (null until the first load).
    // Declared BEFORE the player listener so the v0.5.7 error recovery can
    // read it (the listener outlives one composition's captured values).
    var loadedClipId by remember { mutableStateOf<String?>(null) }
    // v0.5.7 (ponto 4): last effect list handed to the player + bounded
    // recovery budget. onPlayerError re-attaches the list, re-prepares at the
    // playhead and lets the user resume — instead of stalling on a dead GL
    // pipeline. STATE_READY refunds the budget so a healthy session never
    // runs out; a persistently broken stream stops after MAX tries (no loop).
    var lastEffects by remember {
        mutableStateOf<List<androidx.media3.common.Effect>?>(null)
    }
    var recoveryAttempts by remember { mutableStateOf(0) }
    // Keep the icon/state and the player in lock-step: STATE_ENDED and decode
    // errors flip `isPlaying` off so the UI never shows a phantom "playing".
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                // Keep the icon/state and the player in lock-step: STATE_ENDED
                // flips `isPlaying` off so the UI never shows a phantom
                // "playing" (and pressing play can restart the item).
                if (playbackState == Player.STATE_ENDED) viewModel.setPlaying(false)
                // v0.5.7 (ponto 4): a READY pipeline proves the current
                // effect list + media item work — refund the recovery budget.
                if (playbackState == Player.STATE_READY) recoveryAttempts = 0
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                // v0.2.4: real-render evidence — this fires when the decoder
                // has output configured for the surface, distinguishing
                // "frame actually produced" from "item prepared/effects list
                // attached". v0.2.5: a 0x0 callback is NO LONGER reported as
                // geometry (it means no video track was decoded for this
                // item — e.g. an audio-only item) so the report cannot
                // misread w=0 as a decode failure without evidence.
                // v0.3.0: the event carries the playback context (state +
                // playhead vs video range) so "parked at the end / player
                // stopped" can never be misread as "the video was lost".
                videoSize = size
                if (size.width <= 0 || size.height <= 0) {
                    val videoEnd = ProjectMath.videoDurationMs(project.clips)
                    DiagnosticsLogger.d(
                        "preview", "PREVIEW_RENDER", previewOpId,
                        "no video track geometry for this item (video=none)",
                        meta = listOf(
                            "video" to "none",
                            "playerState" to playbackStateName(player.playbackState),
                            "playheadMs" to state.playheadMs.toString(),
                            "videoEndMs" to videoEnd.toString(),
                            "playheadInsideVideo" to
                                (state.playheadMs < videoEnd && project.clips.isNotEmpty())
                                    .toString(),
                        ),
                    )
                } else {
                    DiagnosticsLogger.d(
                        "preview", "PREVIEW_RENDER", previewOpId,
                        "decoder produced output geometry",
                        meta = listOf(
                            "w" to size.width.toString(),
                            "h" to size.height.toString(),
                            "pixelWH" to (size.pixelWidthHeightRatio.toString()),
                        ),
                    )
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                viewModel.setPlaying(false)
                viewModel.playbackError.value = error.errorCodeName
                DiagnosticsLogger.e(
                    "playback", "PLAYER_ERROR", previewOpId,
                    "preview player error",
                    throwable = error,
                    meta = listOf("code" to error.errorCodeName),
                )
                // v0.5.7 (ponto 4): recover instead of stalling. The GL
                // pipeline occasionally dies with GlException 0x3006
                // (EGL_BAD_CONTEXT) reported as
                // VIDEO_FRAME_PROCESSING_FAILED — usually when the effect
                // list / surface churned while the player was BUFFERING.
                // Recovery: re-attach the last known-good effect list,
                // re-prepare the loaded clip AT THE PLAYHEAD and leave
                // playing to the user. Bounded attempts (no error loop);
                // fresh state comes from the ViewModel, never stale captures.
                if (recoveryAttempts >= PLAYER_RECOVERY_MAX_ATTEMPTS) {
                    DiagnosticsLogger.w(
                        "playback", "PLAYER_RECOVERY_SKIPPED", previewOpId,
                        "recovery budget exhausted for this session stretch",
                        meta = listOf("code" to error.errorCodeName),
                    )
                    return
                }
                val ui = viewModel.ui.value
                val proj = ui.project
                val clipId = loadedClipId
                if (proj == null || clipId == null) return
                val index = TimelineLogic.clipIndexAt(proj.clips, ui.playheadMs)
                val clip = proj.clips.getOrNull(index) ?: return
                if (clip.id != clipId) return
                recoveryAttempts += 1
                runCatching {
                    player.playWhenReady = false
                    lastEffects?.let { player.setVideoEffects(it) }
                    val clipStartMs = TimelineLogic.clipStartMs(proj.clips, index)
                    val localMs = ProjectMath.projectToItemLocalMs(
                        clip,
                        (ui.playheadMs - clipStartMs).coerceAtLeast(0),
                    )
                    player.setMediaItem(
                        mediaItemFor(clip),
                        localMs.coerceAtMost(clip.effectiveDurationMs),
                    )
                    player.setPlaybackSpeed(clip.speed)
                    player.prepare()
                }.onSuccess {
                    DiagnosticsLogger.i(
                        "playback", "PLAYER_RECOVERED", previewOpId,
                        "player re-prepared at playhead after error",
                        meta = listOf(
                            "code" to error.errorCodeName,
                            "attempt" to recoveryAttempts.toString(),
                            "clip" to DiagSanitizer.fingerprint(clip.id),
                            "playheadMs" to ui.playheadMs.toString(),
                        ),
                    )
                }.onFailure { t ->
                    DiagnosticsLogger.e(
                        "playback", "PLAYER_RECOVERY_FAILED", previewOpId,
                        "recovery prepare threw",
                        t,
                        meta = listOf("code" to error.errorCodeName),
                    )
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // ------------------- shared effect pipeline (preview) ---------------------
    // v0.2.5 revision key: the EFFECTIVE signature for THIS clip
    // ([ClipEffectPlan.effectiveSignature]) — clip identity/trim/speed/colour
    // + aspect. Overlays are NOT an input (v0.3.1): the preview GL list has
    // no OverlayEffect — overlay pixels come from the Compose layer, which
    // re-reads project state every frame, so keyframe/transform value edits
    // can never require a GL rebuild.
    // Recomputed every recomposition (cheap; overlay counts are small) so a
    // new project snapshot with an unchanged effective config keeps the same
    // Int key and LaunchedEffect does NOT restart.
    val effectsRevision =
        ClipEffectPlan.effectiveSignature(project, currentClip, clipStart)

    // v0.5.7 (keyframe preview crash hardening — GlException 0x3006
    // EGL_BAD_CONTEXT -> PLAYER_ERROR VIDEO_FRAME_PROCESSING_FAILED):
    // revision already applied to the player. A restart with the SAME
    // revision must never re-attach/re-prepare (ponto 1: an identical list
    // is a no-op at best and an EGL hazard at worst).
    var lastAppliedRevision by remember { mutableStateOf<Int?>(null) }

    // Effect application — MUST run before the media-load effect below so the
    // first prepare() happens with the effects already set.
    // v0.5.7 hardening of the LIVE-pipeline rebuild path (pontos 1–3):
    //  1. no-op guard — the same revision is never re-attached;
    //  2. a same-clip rebuild debounces 300 ms and PAUSES the player before
    //     the re-attach (restored afterwards) — the decoder is no longer
    //     hit mid-BUFFERING; every player call runs on the player's own
    //     application-looper thread (the build is trivial since v0.3.1 — the
    //     preview list is video-only — so it stays on that thread too);
    //  3. the debounce is a suspension point: a newer revision cancels this
    //     coroutine (LaunchedEffect key change) and applies instead — two
    //     rebuilds can never run in parallel, the last one wins.
    // First attach / clip change stays immediate (no debounce) so the fresh
    // list is always set BEFORE the media-load effect prepares the item.
    LaunchedEffect(effectsRevision) {
        val clip = currentClip ?: return@LaunchedEffect
        if (lastAppliedRevision == effectsRevision) return@LaunchedEffect
        val sameClipRebuild = loadedClipId == clip.id
        if (sameClipRebuild) {
            delay(300)
            if (!isActive) return@LaunchedEffect
        }
        val effects = DiagContext.withOp(previewOpId) {
            ClipEffectsFactory.effectsFor(
                context = context,
                project = project,
                clip = clip,
                clipStartMs = clipStart,
                forPreview = true,
            )
        }
        val wasPlaying = player.playWhenReady
        if (sameClipRebuild) {
            // Pause BEFORE touching the effect list: re-attach + re-prepare
            // happen against a quiesced pipeline.
            player.playWhenReady = false
        }
        player.setVideoEffects(effects)
        // v0.5.7 (ponto 4): keep the list for the error-recovery re-attach.
        lastEffects = effects
        DiagnosticsLogger.i(
            "preview", "PREVIEW_REBUILD", previewOpId,
            "preview effect list rebuilt and attached",
            meta = listOf(
                "clip" to DiagSanitizer.fingerprint(clip.id),
                "overlays" to project.overlays.size.toString(),
                "revision" to effectsRevision.toString(),
                "debounced" to sameClipRebuild.toString(),
                "thread" to Thread.currentThread().name,
            ),
        )
        if (sameClipRebuild) {
            // Same clip: force a new input stream so the pending list applies
            // immediately, at the current position; restore the play state
            // the pause suspended.
            val pos = runCatching { player.currentPosition }.getOrDefault(0L).coerceAtLeast(0)
            player.setMediaItem(mediaItemFor(clip), pos)
            player.setPlaybackSpeed(clip.speed)
            player.prepare()
            player.playWhenReady = wasPlaying
        }
        lastAppliedRevision = effectsRevision
    }

    LaunchedEffect(currentClip?.id, viewModel.seekToken.collectAsState().value) {
        if (currentClip == null) {
            player.stop()
            loadedClipId = null
            return@LaunchedEffect
        }
        // Stage K evidence (cold: once per seek/clip change): which overlays
        // are active at the playhead this seek lands on.
        DiagnosticsLogger.d(
            "preview", "ACTIVATION", previewOpId,
            "seek applied",
            meta = listOf(
                "playheadMs" to state.playheadMs.toString(),
                "activeOverlays" to project.overlays
                    .count { state.playheadMs >= it.startMs && state.playheadMs < it.endMs }
                    .toString(),
                "clip" to DiagSanitizer.fingerprint(currentClip.id),
            ),
        )
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
    // v0.3.0 replay-after-end fix: ExoPlayer with REPEAT_MODE_OFF stays in
    // STATE_ENDED when playWhenReady is set on an ended player — pressing
    // play after playback finished silently did NOTHING (device-reported
    // "bug ao reproduzir"). A play request on an ENDED player restarts the
    // item from position 0.
    LaunchedEffect(state.isPlaying) {
        if (state.isPlaying && player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
        }
        player.playWhenReady = state.isPlaying
    }

    // Frame-driven playhead updates while playing (smooth keyframe animation,
    // throttled to ~30fps to stay light on 4 GB devices). Guards:
    // - `state.isScrubbing`: the finger owns the playhead;
    // - `!player.isPlaying`: seeking/buffering/ended — publishing the stale
    //   player position here is exactly the "scrub reverts" bug;
    // - v0.2.4 [PlayheadGate]: even when the player reports playing, a pending
    //   async seek leaves currentPosition at the OLD position (device evidence:
    //   scrub ended at 120 ms, playhead snapped back to ~10163 ms). Publishing
    //   is skipped while the mapped position is far from the authoritative
    //   playhead — it resumes once the seek lands.
    LaunchedEffect(state.isPlaying, loadedClipId) {
        var lastPublish = 0L
        while (isActive && state.isPlaying) {
            val now = withFrameNanos { it }
            if (state.isScrubbing) continue
            val clip = currentClip ?: break
            if (!player.isPlaying) continue
            val pos = player.currentPosition
            val projectPos = clipStart + ProjectMath.itemLocalToProjectMs(clip, pos)
            if (!PlayheadGate.shouldPublish(state.playheadMs, projectPos)) continue
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

    // -------------------- clip-less playback (v0.3.0) -------------------------
    // Audio/overlay-only projects have no video publisher: without this
    // driver the playhead froze at 0 while audio played, and the audio
    // scheduler then drift-seeked the clip back every ~300 ms (stutter).
    // With clips present the frame publisher below owns the playhead and
    // this loop is idle, so the two can never fight each other.
    // NOTE: the state is re-read from the ViewModel every frame — the
    // composition-captured `state` object is stale inside a long loop.
    if (project.clips.isEmpty()) {
        LaunchedEffect(state.isPlaying, durationMs) {
            if (!state.isPlaying || durationMs <= 0L) return@LaunchedEffect
            // v0.5.2 replay-after-end (same rule as the video path's v0.3.0
            // fix): a play request while parked AT the end must restart from
            // 0 — otherwise the clock's very next frame sees
            // next >= durationMs and stops again, so play appears dead.
            if (viewModel.ui.value.playheadMs >= durationMs) {
                viewModel.setPlayhead(0)
            }
            var lastFrame = 0L
            while (isActive) {
                val now = withFrameNanos { it }
                val current = viewModel.ui.value
                if (!current.isPlaying || current.isScrubbing) break
                if (lastFrame != 0L) {
                    val next = current.playheadMs + (now - lastFrame) / 1_000_000L
                    if (next >= durationMs) {
                        viewModel.setPlaying(false)
                        viewModel.seekTo(durationMs)
                        break
                    }
                    viewModel.setPlayhead(next)
                }
                lastFrame = now
            }
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
                    (android.view.LayoutInflater.from(ctx)
                        .inflate(R.layout.view_player_texture, null, false) as
                        androidx.media3.ui.PlayerView).apply {
                        // v0.2.5: surface target dimensions (the view that
                        // hosts the TextureView) tracked for the geometry
                        // evidence event.
                        addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                            surfaceSize = v.width to v.height
                        }
                    }
                },
                update = { view ->
                    view.player = player
                },
                modifier = Modifier.fillMaxSize(),
            )

            var gestureState by remember {
                mutableStateOf<Pair<String, OverlayTransform>?>(null)
            }
            // v0.2.5: one bounded evidence event whenever ANY of the three
            // dimension sources changes (deduped by the tracker — layout
            // passes fire far more often than the geometry actually moves).
            LaunchedEffect(effectiveVideoSize, surfaceSize, containerW, containerH, fitted.width, fitted.height, contentAspect) {
                if (geometryTracker.shouldLog(
                        ViewportGeometryTracker.Geometry(
                            videoW = effectiveVideoSize.width,
                            videoH = effectiveVideoSize.height,
                            surfaceW = surfaceSize.first,
                            surfaceH = surfaceSize.second,
                            viewportW = fitted.width.toInt(),
                            viewportH = fitted.height.toInt(),
                            contentAspect = contentAspect,
                        ),
                    )
                ) {
                    // v0.5.7 (ponto 5): videoSource names the geometry origin —
                    // "decoder" (onVideoSizeChanged fired) or "clip" (the
                    // clip's persisted probe dims) — so the next device report
                    // can never misread video=none as a lost video track.
                    val videoSource =
                        if (videoSize.width > 0 && videoSize.height > 0) "decoder"
                        else if (clipVideoSize.width > 0) "clip" else "none"
                    DiagnosticsLogger.i(
                        "preview", "VIEWPORT_GEOMETRY", previewOpId,
                        "video vs surface vs viewport dimensions",
                        meta = listOf(
                            "videoW" to effectiveVideoSize.width.toString(),
                            "videoH" to effectiveVideoSize.height.toString(),
                            "videoSource" to videoSource,
                            // v0.5.5 (docs/AUDIT_v0.5.4_report_followup.md §5):
                            // the 2026-10-21 report had videoW=0 in EVERY
                            // geometry event, even for projects with clips,
                            // and no PREVIEW_RENDER with w>0 — the decoder
                            // never reported a size. playerState at log time
                            // distinguishes "player not prepared yet" from
                            // "prepared but the size callback never fired".
                            "playerState" to playbackStateName(player.playbackState),
                            "surfaceW" to surfaceSize.first.toString(),
                            "surfaceH" to surfaceSize.second.toString(),
                            "viewportW" to fitted.width.toInt().toString(),
                            "viewportH" to fitted.height.toInt().toString(),
                            "contentAspect" to contentAspect.toString(),
                            "containerW" to containerW.toInt().toString(),
                            "containerH" to containerH.toInt().toString(),
                        ),
                    )
                }
            }

            // v0.5.0 (G4): ONE expansion for the preview render list — text
            // overlays with an entrance animation render as word/letter
            // segments; everything else passes through unchanged. The build
            // is memoised per project revision (overlay edits never touch
            // the decoder — effectiveSignature excludes overlays, v0.3.1).
            val expanded = remember(project) {
                com.onevideog.editor.editor.ExpandedOverlays.build(project)
            }

            OverlayLayer(
                overlays = expanded.overlays,
                playheadMs = { state.playheadMs },
                contentAspect = { contentAspect },
                contentWidthPx = { fitted.width },
                contentHeightPx = { fitted.height },
                selectedOverlayId = { state.selectedOverlayId },
                gestureOverride = { gestureState },
                parents = { project.overlays },
                opId = previewOpId,
                onTap = { overlay ->
                    // Clear any stale gesture override (a tap ends the
                    // gesture without committing a transform). The overlay
                    // here is already the gesture/selection TARGET (the
                    // parent for animated text segments).
                    gestureState = null
                    if (overlay == null) {
                        // v0.5.1: unified exclusive selection (tap on empty
                        // stage clears every kind).
                        viewModel.clearSelection()
                    } else {
                        viewModel.selectTimelineClip(
                            if (overlay.isText) {
                                com.onevideog.editor.editor.ClipKind.TEXT
                            } else {
                                com.onevideog.editor.editor.ClipKind.IMAGE
                            },
                            overlay.id,
                        )
                    }
                },
                onGesture = { overlay, transform ->
                    gestureState = overlay.id to transform
                },
                onGestureEnd = { overlay, transform ->
                    gestureState = null
                    DiagnosticsLogger.d(
                        "timeline", "OVERLAY_TRANSFORM", previewOpId,
                        "overlay transform committed",
                        meta = listOf(
                            "ovId" to DiagSanitizer.fingerprint(overlay.id),
                            "x" to transform.x.toString(),
                            "y" to transform.y.toString(),
                            "scale" to transform.scale.toString(),
                            "rotDeg" to transform.rotationDeg.toString(),
                            "opacity" to transform.opacity.toString(),
                        ),
                    )
                    // v0.5.0 (F1): auto-key commit — with keyframes the
                    // gesture lands on the keyframe at the playhead (or
                    // creates one); without, it writes the base transform
                    // exactly as v0.4.0. ONE undo step per gesture.
                    viewModel.applyTransformEdit(overlay.id) {
                        transform.withClampedValues()
                    }
                },
                onDelete = { overlay ->
                    // v0.5.1: unified selection — deleting deselects every kind.
                    viewModel.clearSelection()
                    viewModel.removeOverlay(overlay.id)
                },
            )
        }
    }
}

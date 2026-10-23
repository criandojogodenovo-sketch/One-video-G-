package com.onevideog.editor.editor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.onevideog.editor.data.AudioClipItem
import com.onevideog.editor.diag.DiagSanitizer
import com.onevideog.editor.diag.DiagnosticsLogger

/**
 * Schedules independent audio clip playback against the preview playhead.
 *
 * Threading contract (crash-critical): every ExoPlayer here is created,
 * accessed and released on the MAIN thread. The controller enforces this
 * itself by marshalling [sync] to the main looper, so callers may invoke it
 * from any thread. This removes the v0.2.0 crash where two concurrent
 * sync paths (composition LaunchedEffect on Dispatchers.Default + a
 * main-thread timer) mutated the same player map and drove ExoPlayer
 * off its application thread.
 *
 * One small ExoPlayer per audio clip (projects hold a handful of clips; each
 * idle player is paused and prepared, costing little). The controller is the
 * preview mirror of the export audio plan ([AudioTimeline] +
 * [CompositionFactory]): same segments, same offsets, same volumes — so the
 * user hears during editing what the export will contain.
 *
 * v0.8.0 (Q2): every player's DefaultAudioSink now carries ONE
 * [GainAutomationAudioProcessor] (the SAME class the export items use, fed
 * by the SAME [AudioGainMath]) — fades, volume keyframes and the noise gate
 * preview LIVE: the processor profile is swapped on every sync tick and the
 * gain timeline is re-anchored to the player clock ([anchorTo]), so edits
 * apply without re-preparing any decoder. The sink is injected through
 * DefaultRenderersFactory.buildAudioSink — the 1.4.1 three-argument form,
 * verified against the 1.4.1 sources (the 1.5+ variant drops
 * enableFloatOutput and MUST NOT be used here).
 *
 * Failure policy: a player that fails to decode (unsupported/missing file,
 * no codec on the device) reports via [onClipError] and is dropped; the
 * editor never crashes because one audio clip failed.
 *
 * Volume note: with the gain processor in the sink, the preview applies the
 * same gain the export applies (including > 1.0 amplification, clipped at
 * the 16-bit domain exactly like the export); player.volume stays the
 * 0..1 cap as a final safety bound.
 */
@UnstableApi
class AudioPreviewController(context: Context) {

    /** Sanitized failure report: clip id + Media3 error code name. */
    var onClipError: ((clipId: String, errorCodeName: String) -> Unit)? = null

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val players = LinkedHashMap<String, ExoPlayer>()
    private val processors = LinkedHashMap<String, GainAutomationAudioProcessor>()
    private val failedClips = HashSet<String>()
    private val lastSyncLogAt = HashMap<String, Long>()

    /** Reconciles player state with the desired timeline position. */
    fun sync(
        audioClips: List<AudioClipItem>,
        playheadMs: Long,
        playing: Boolean,
        videoDurationMs: Long,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { sync(audioClips, playheadMs, playing, videoDurationMs) }
            return
        }
        runCatching { syncOnMain(audioClips, playheadMs, playing, videoDurationMs) }
            .onFailure { Log.w(TAG, "audio sync skipped: ${it.javaClass.simpleName}") }
    }

    /** Main-thread implementation. Callers must be on the main looper. */
    private fun syncOnMain(
        audioClips: List<AudioClipItem>,
        playheadMs: Long,
        playing: Boolean,
        videoDurationMs: Long,
    ) {
        // Drop players of removed or failed clips.
        val validIds = audioClips.map { it.id }.toSet()
        players.keys.filterNot { it in validIds }.forEach { id ->
            players.remove(id)?.safeRelease()
            processors.remove(id)
            lastSyncLogAt.remove(id)
        }

        for (clip in audioClips) {
            if (clip.uri.isBlank() || clip.id in failedClips) continue
            // v0.5.1: window-local coordinates. The player is created with
            // ClippingConfiguration (trimStart..trimEnd) and Media3 rebases
            // item timestamps to 0 at the window start, so currentPosition
            // and seekTo are 0-based within the window — the same contract
            // the video preview path relies on. The previous code compared
            // currentPosition against the ABSOLUTE source position
            // (sourcePositionAt), so any clip with trimStartMs > 0 computed
            // a permanent false drift of trimStartMs and was re-seeked
            // every sync tick (stutter / silence whenever the bar was
            // dragged). AudioTimeline.windowPositionAt does the conversion.
            val windowPositionMs = AudioTimeline.windowPositionAt(
                clip, playheadMs, videoDurationMs,
            )
            val player = players[clip.id]
            if (windowPositionMs == null) {
                player?.let { if (it.isPlaying) it.pause() }
                continue
            }
            val active = player
                ?: createPlayer(clip)?.also {
                    players[clip.id] = it
                } ?: continue
            if (active.playbackState == Player.STATE_IDLE) active.prepare()
            active.volume = clip.volume.coerceIn(0f, 1f)
            // v0.8.0 (Q2): live gain automation — swap the profile (fades,
            // keyframes, gate) and re-anchor the gain timeline to the
            // player's playback clock. The anchor makes the processor's
            // clip-local time follow seeks and loop wraps without any
            // re-prepare (error bounded by one sync tick; documented).
            processors[clip.id]?.let { processor ->
                processor.updateProfile(GainAutomationAudioProcessor.profileOf(clip))
                processor.anchorTo(active.currentPosition)
            }
            val drift = kotlin.math.abs(active.currentPosition - windowPositionMs)
            if (drift > DRIFT_TOLERANCE_MS) {
                active.seekTo(windowPositionMs)
                // v0.8.0 (Q5): the audio↔video offset evidence — the drift
                // between the audio player's clock and the preview playhead
                // at re-seek time, for device-side sync validation. Rate
                // limited per clip so the diagnostics ring is not flooded.
                val now = android.os.SystemClock.elapsedRealtime()
                val last = lastSyncLogAt[clip.id] ?: 0L
                if (now - last >= SYNC_LOG_INTERVAL_MS) {
                    lastSyncLogAt[clip.id] = now
                    DiagnosticsLogger.i(
                        "playback", "AUDIO_SYNC", null,
                        "audio preview drift re-seek (audio-video offset evidence)",
                        meta = listOf(
                            "clip" to DiagSanitizer.fingerprint(clip.id),
                            "driftMs" to drift.toString(),
                            "targetMs" to windowPositionMs.toString(),
                            "playing" to playing.toString(),
                        ),
                    )
                }
            }
            if (playing) {
                if (!active.isPlaying) active.play()
            } else {
                if (active.isPlaying) active.pause()
            }
        }
    }

    /** Releases all players (composition disposal). Must be called on main. */
    fun release() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { release() }
            return
        }
        players.values.forEach { it.safeRelease() }
        players.clear()
        processors.clear()
        failedClips.clear()
        lastSyncLogAt.clear()
    }

    /**
     * Builds the player for [clip]; null when the document is unusable
     * (blank/unreadable URI). A [Player.Listener] forwards decoder failures
     * as sanitized reports instead of letting the editor crash.
     *
     * v0.8.0 (Q2): the RenderersFactory rebuilds the audio sink with the
     * clip's [GainAutomationAudioProcessor] (alwaysActive = true — live
     * profile edits never re-configure the sink). The processor is created
     * BEFORE the player and registered in [processors].
     */
    private fun createPlayer(clip: AudioClipItem): ExoPlayer? = try {
        val processor = GainAutomationAudioProcessor(
            GainAutomationAudioProcessor.profileOf(clip),
            alwaysActive = true,
        )
        val renderersFactory = object : DefaultRenderersFactory(appContext) {
            // Media3 1.4.1 signature (verified against the 1.4.1 sources):
            // buildAudioSink(Context, boolean enableFloatOutput, boolean
            // enableAudioTrackPlaybackParams). The rebuilt sink preserves
            // the float-output decision and swaps in the gain processor;
            // audio-track playback params stay default (these players never
            // change playback speed).
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setAudioProcessors(arrayOf(processor))
                    .build()
        }
        ExoPlayer.Builder(appContext, renderersFactory).build().apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "audio player error ${error.errorCodeName}")
                    failedClips.add(clip.id)
                    players.remove(clip.id)
                    processors.remove(clip.id)
                    onClipError?.invoke(clip.id, error.errorCodeName)
                }
            })
            setMediaItem(
                MediaItem.Builder()
                    .setUri(android.net.Uri.parse(clip.uri))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.trimStartMs)
                            .setEndPositionMs(clip.trimEndMs)
                            .build(),
                    )
                    .build(),
            )
            repeatMode = if (clip.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            playWhenReady = false
            prepare()
        }.also { processors[clip.id] = processor }
    } catch (t: Throwable) {
        Log.w(TAG, "audio player init failed: ${t.javaClass.simpleName}")
        failedClips.add(clip.id)
        onClipError?.invoke(clip.id, "INIT_FAILED")
        null
    }

    private fun ExoPlayer.safeRelease() {
        runCatching {
            release()
        }.onFailure { Log.w(TAG, "audio player release failed: ${it.javaClass.simpleName}") }
    }

    private companion object {
        const val DRIFT_TOLERANCE_MS = 250L
        const val SYNC_LOG_INTERVAL_MS = 3_000L
        const val TAG = "OvgAudioPreview"
    }
}

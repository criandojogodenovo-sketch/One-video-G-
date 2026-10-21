package com.onevideog.editor.editor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.onevideog.editor.data.AudioClipItem

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
 * Failure policy: a player that fails to decode (unsupported/missing file,
 * no codec on the device) reports via [onClipError] and is dropped; the
 * editor never crashes because one audio clip failed.
 *
 * Volume note: ExoPlayer caps output gain at 1.0; a clip volume above 1.0
 * therefore only reaches full loudness in the export (where
 * [VolumeAudioProcessor] amplifies the PCM). Documented limitation.
 */
@UnstableApi
class AudioPreviewController(context: Context) {

    /** Sanitized failure report: clip id + Media3 error code name. */
    var onClipError: ((clipId: String, errorCodeName: String) -> Unit)? = null

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val players = LinkedHashMap<String, ExoPlayer>()
    private val failedClips = HashSet<String>()

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
            val active = player ?: createPlayer(clip)?.also { players[clip.id] = it } ?: continue
            if (active.playbackState == Player.STATE_IDLE) active.prepare()
            active.volume = clip.volume.coerceIn(0f, 1f)
            val drift = kotlin.math.abs(active.currentPosition - windowPositionMs)
            if (drift > DRIFT_TOLERANCE_MS) {
                active.seekTo(windowPositionMs)
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
        failedClips.clear()
    }

    /**
     * Builds the player for [clip]; null when the document is unusable
     * (blank/unreadable URI). A [Player.Listener] forwards decoder failures
     * as sanitized reports instead of letting the editor crash.
     */
    private fun createPlayer(clip: AudioClipItem): ExoPlayer? = try {
        ExoPlayer.Builder(appContext).build().apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "audio player error ${error.errorCodeName}")
                    failedClips.add(clip.id)
                    players.remove(clip.id)?.safeRelease()
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
        }
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
        const val TAG = "OvgAudioPreview"
    }
}

package com.onevideog.editor.editor

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.onevideog.editor.data.AudioClipItem

/**
 * Schedules independent audio clip playback against the preview playhead.
 *
 * One small ExoPlayer per audio clip (projects hold a handful of clips; each
 * idle player is paused and prepared, costing little). The controller is the
 * preview mirror of the export audio plan ([AudioTimeline] +
 * [CompositionFactory]): same segments, same offsets, same volumes — so the
 * user hears during editing what the export will contain.
 *
 * Volume note: ExoPlayer caps output gain at 1.0; a clip volume above 1.0
 * therefore only reaches full loudness in the export (where
 * [VolumeAudioProcessor] amplifies the PCM). Documented limitation.
 */
@UnstableApi
class AudioPreviewController(context: Context) {

    private val appContext = context.applicationContext
    private val players = LinkedHashMap<String, ExoPlayer>()

    /** Reconciles player state with the desired timeline position. */
    fun sync(
        audioClips: List<AudioClipItem>,
        playheadMs: Long,
        playing: Boolean,
        videoDurationMs: Long,
    ) {
        // Drop players of removed clips.
        val validIds = audioClips.map { it.id }.toSet()
        players.keys.filterNot { it in validIds }.forEach { id ->
            players.remove(id)?.release()
        }

        for (clip in audioClips) {
            val state = desiredState(clip, playheadMs, playing, videoDurationMs)
            if (state == null) {
                players[clip.id]?.let { if (it.isPlaying) it.pause() }
                continue
            }
            val player = players.getOrPut(clip.id) { createPlayer(clip) }
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.volume = clip.volume.coerceIn(0f, 1f)
            val drift = kotlin.math.abs(player.currentPosition - state.sourcePositionMs)
            if (drift > DRIFT_TOLERANCE_MS) {
                player.seekTo(state.sourcePositionMs)
            }
            if (playing) {
                if (!player.isPlaying) player.play()
            } else {
                if (player.isPlaying) player.pause()
            }
        }
    }

    /** Releases all players (composition disposal). */
    fun release() {
        players.values.forEach { it.release() }
        players.clear()
    }

    private data class Desired(val sourcePositionMs: Long)

    /**
     * Where the clip should be right now, or null when it is silent at
     * [playheadMs]. Looping clips wrap across the trimmed range.
     */
    private fun desiredState(
        clip: AudioClipItem,
        playheadMs: Long,
        playing: Boolean,
        videoDurationMs: Long,
    ): Desired? {
        val duration = clip.trimmedDurationMs
        if (duration <= 0) return null
        val local = playheadMs - clip.startMs
        if (local < 0) return null
        val sourceOffset: Long = if (clip.loop) {
            val fillEnd = if (videoDurationMs > 0) videoDurationMs else clip.endMs
            if (playheadMs >= fillEnd) return null
            clip.trimStartMs + (local % duration)
        } else {
            if (local >= duration) return null
            clip.trimStartMs + local
        }
        return Desired(sourcePositionMs = sourceOffset.coerceAtMost(clip.sourceDurationMs))
    }

    private fun createPlayer(clip: AudioClipItem): ExoPlayer =
        ExoPlayer.Builder(appContext).build().apply {
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

    private companion object {
        const val DRIFT_TOLERANCE_MS = 250L
    }
}

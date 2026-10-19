package com.onevideog.editor.editor

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.StatFs
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import com.onevideog.editor.data.Clip
import com.onevideog.editor.data.ExportResolutionEnum
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.ProjectAspect
import com.onevideog.editor.editor.ExportConfig.Quality
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Real offline video export built on Media3 Transformer.
 *
 * Pipeline per project:
 *   - one EditedMediaItem per clip: ClippingConfiguration (trim) + per-clip
 *     effects (speed, volume, colour, overlays)
 *   - one EditedMediaItemSequence holding the clips (concatenation)
 *   - optional second, looping audio-only sequence for background music
 *   - composition-level Presentation effect for resolution/aspect ratio
 *   - H.264 video + AAC audio, bitrate via VideoEncoderSettings
 *
 * The output is written to a cache file, validated with
 * MediaMetadataRetriever, then streamed to the user-chosen SAF document.
 * Nothing here requires network access.
 */
@UnstableApi
class ExportManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    enum class Phase { IDLE, RUNNING, VERIFYING, DONE, FAILED, CANCELLED }

    sealed interface State {
        data object Idle : State
        data class Running(val progressPercent: Int) : State
        data object Verifying : State
        data class Done(val outputUri: Uri, val durationMs: Long) : State
        data class Failed(val message: String) : State
        data object Cancelled : State
    }

    /** Errors mapped to localized UI strings. */
    enum class Failure { INSUFFICIENT_STORAGE, EXPORT_ERROR, COPY_ERROR, VALIDATION_FAILED, GENERIC }

    var onState: ((State) -> Unit)? = null

    private var transformer: Transformer? = null
    private var progressJob: Job? = null
    private var running = false
    private var cancelled = false
    private var completion: CompletableDeferred<Boolean>? = null

    val isRunning: Boolean get() = running

    fun start(
        project: OvgProject,
        resolution: ExportResolutionEnum,
        quality: Quality,
        destinationUri: Uri,
    ) {
        if (running) return
        val problem = ExportConfig.validateForExport(project)
        if (problem != null) {
            emit(State.Failed(failureMessage(Failure.GENERIC)))
            return
        }
        val totalMs = project.totalDurationMs
        val needed = ExportConfig.estimatedBytes(resolution, quality, totalMs)
        val stat = StatFs(context.cacheDir.absolutePath)
        if (stat.availableBytes < needed + 50L * 1024 * 1024) {
            emit(State.Failed(failureMessage(Failure.INSUFFICIENT_STORAGE)))
            return
        }

        running = true
        cancelled = false
        emit(State.Running(0))

        val tempFile = File(context.cacheDir, "export_${System.currentTimeMillis()}.mp4")
        val completionDeferred = CompletableDeferred<Boolean>()
        completion = completionDeferred
        progressJob = scope.launch {
            try {
                startTransformer(project, resolution, quality, tempFile, completionDeferred)
                pollProgress(completionDeferred)
                val succeeded = completionDeferred.await()
                if (cancelled) {
                    tempFile.delete()
                    emit(State.Cancelled)
                    return@launch
                }
                if (!succeeded) {
                    tempFile.delete()
                    emit(State.Failed(failureMessage(Failure.EXPORT_ERROR)))
                    return@launch
                }
                val outputExists = withContext(Dispatchers.IO) {
                    tempFile.exists() && tempFile.length() > 0L
                }
                if (!outputExists) {
                    tempFile.delete()
                    emit(State.Failed(failureMessage(Failure.EXPORT_ERROR)))
                    return@launch
                }
                emit(State.Verifying)
                val verified = withContext(Dispatchers.IO) {
                    verifyPlayable(tempFile) && copyToDestination(tempFile, destinationUri)
                }
                tempFile.delete()
                if (verified) {
                    emit(State.Done(destinationUri, totalMs))
                } else {
                    emit(State.Failed(failureMessage(Failure.COPY_ERROR)))
                }
            } catch (t: Throwable) {
                tempFile.delete()
                emit(State.Failed(failureMessage(Failure.EXPORT_ERROR)))
            } finally {
                running = false
                progressJob = null
                completion = null
            }
        }
    }

    fun cancel() {
        if (!running || cancelled) return
        cancelled = true
        runCatching { transformer?.cancel() }
        completion?.complete(false)
    }

    private fun startTransformer(
        project: OvgProject,
        resolution: ExportResolutionEnum,
        quality: Quality,
        outputPath: File,
        completionDeferred: CompletableDeferred<Boolean>,
    ) {
        val clips = project.clips
        val totalMs = clips.sumOf { it.effectiveDurationMs }

        val videoItems = ImmutableList.builder<EditedMediaItem>()
        for (index in clips.indices) {
            videoItems.add(buildClipItem(project, clips[index], index))
        }
        val sequences = ImmutableList.builder<EditedMediaItemSequence>()
        sequences.add(EditedMediaItemSequence(videoItems.build()))

        project.music?.let { music ->
            val musicEndMs = minOf(music.durationMs, totalMs)
            if (musicEndMs > 0) {
                val musicMediaItem = MediaItem.Builder()
                    .setUri(Uri.parse(music.uri))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(0)
                            .setEndPositionMs(musicEndMs)
                            .build(),
                    )
                    .build()
                val musicItem = EditedMediaItem.Builder(musicMediaItem)
                    .setEffects(
                        Effects(
                            listOf(VolumeAudioProcessor(music.volume)),
                            emptyList(),
                        ),
                    )
                    .build()
                val loops = music.durationMs < totalMs
                sequences.add(EditedMediaItemSequence(listOf(musicItem), loops))
            }
        }

        val compositionEffects = mutableListOf<androidx.media3.common.Effect>()
        val aspect = project.aspectEnum
        if (aspect == ProjectAspect.SOURCE) {
            compositionEffects.add(Presentation.createForHeight(resolution.targetHeight))
        } else {
            val size = ExportConfig.outputSize(resolution, aspect)
            compositionEffects.add(
                Presentation.createForWidthAndHeight(
                    size.width,
                    size.height,
                    Presentation.LAYOUT_SCALE_TO_FIT,
                ),
            )
        }

        val composition = Composition.Builder(sequences.build())
            .setEffects(Effects(emptyList(), compositionEffects))
            .build()

        val encoderSettings = VideoEncoderSettings.Builder()
            .setBitrate(ExportConfig.videoBitrate(resolution, quality))
            .build()

        transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setTransformationRequest(
                TransformationRequest.Builder().build(),
            )
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(encoderSettings)
                    .build(),
            )
            .addListener(
                object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        completionDeferred.complete(true)
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException,
                    ) {
                        completionDeferred.complete(false)
                    }
                },
            )
            .build()

        transformer?.start(composition, outputPath.absolutePath)
    }

    private fun buildClipItem(project: OvgProject, clip: Clip, index: Int): EditedMediaItem {
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(clip.uri))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.trimStartMs)
                    .setEndPositionMs(clip.trimEndMs)
                    .build(),
            )
            .build()

        val audioProcessors = mutableListOf<androidx.media3.common.audio.AudioProcessor>()
        audioProcessors.add(VolumeAudioProcessor(clip.volume))
        if (clip.speed != 1f) {
            audioProcessors.add(
                androidx.media3.common.audio.SonicAudioProcessor().apply { setSpeed(clip.speed) },
            )
        }

        val videoEffects = mutableListOf<androidx.media3.common.Effect>()
        if (clip.speed != 1f) videoEffects.add(SpeedChangeEffect(clip.speed))
        if (!clip.color.isDefault) {
            videoEffects.add(
                ColorAdjustmentEffect(clip.color.brightness, clip.color.contrast, clip.color.saturation),
            )
        }

        // Overlays intersecting this clip, converted to clip-local time.
        val clipStart = TimelineLogic.clipStartMs(project.clips, index)
        val clipEnd = clipStart + clip.effectiveDurationMs
        val overlays = ImmutableList.builder<androidx.media3.effect.TextureOverlay>()

        val frameW = clip.width.takeIf { it > 0 } ?: 1280
        val frameH = clip.height.takeIf { it > 0 } ?: 720

        for (text in project.textOverlays) {
            val start = maxOf(text.startMs, clipStart)
            val end = minOf(text.endMs, clipEnd)
            if (start >= end) continue
            val bitmap = OverlayRenderer.renderText(
                frameW, frameH, text.text, text.colorArgb,
                runCatching { com.onevideog.editor.data.OverlayPosition.valueOf(text.position) }
                    .getOrDefault(com.onevideog.editor.data.OverlayPosition.BOTTOM),
            )
            overlays.add(
                TimeWindowedBitmapOverlay(
                    visibleBitmap = bitmap,
                    startUs = clip.trimStartMs + Math.round((start - clipStart) * clip.speed) * 1000,
                    endUs = clip.trimStartMs + Math.round((end - clipStart) * clip.speed) * 1000,
                ),
            )
        }

        for (image in project.imageOverlays) {
            val start = maxOf(image.startMs, clipStart)
            val end = minOf(image.endMs, clipEnd)
            if (start >= end) continue
            val decoded = OverlayRenderer.decodeImage(context, Uri.parse(image.uri)) ?: continue
            val bitmap = OverlayRenderer.renderImage(
                frameW, frameH, decoded,
                runCatching { com.onevideog.editor.data.OverlayPosition.valueOf(image.position) }
                    .getOrDefault(com.onevideog.editor.data.OverlayPosition.BOTTOM),
                image.scale,
            )
            if (bitmap !== decoded) decoded.recycle()
            overlays.add(
                TimeWindowedBitmapOverlay(
                    visibleBitmap = bitmap,
                    startUs = clip.trimStartMs + Math.round((start - clipStart) * clip.speed) * 1000,
                    endUs = clip.trimStartMs + Math.round((end - clipStart) * clip.speed) * 1000,
                ),
            )
        }

        val overlayList = overlays.build()
        if (overlayList.isNotEmpty()) {
            videoEffects.add(OverlayEffect(overlayList))
        }

        return EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(audioProcessors, videoEffects))
            .build()
    }

    private suspend fun pollProgress(completionDeferred: CompletableDeferred<Boolean>) {
        val holder = ProgressHolder()
        while (coroutineContext.isActive && !completionDeferred.isCompleted) {
            val t = transformer ?: break
            val state = t.getProgress(holder)
            val pct = when (state) {
                Transformer.PROGRESS_STATE_AVAILABLE -> holder.progress.coerceIn(0, 100)
                Transformer.PROGRESS_STATE_NO_TRANSFORMATION -> 100
                else -> 0
            }
            emit(State.Running(pct))
            delay(200)
        }
    }

    private fun verifyPlayable(file: File): Boolean = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            hasVideo && duration > 0
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrDefault(false)

    private fun copyToDestination(source: File, destinationUri: Uri): Boolean = runCatching {
        context.contentResolver.openOutputStream(destinationUri, "wt")?.use { out ->
            source.inputStream().use { input -> input.copyTo(out) }
            out.flush()
            true
        } ?: false
    }.getOrDefault(false)

    private fun failureMessage(failure: Failure): String = when (failure) {
        Failure.INSUFFICIENT_STORAGE -> "error_storage"
        Failure.EXPORT_ERROR -> "error_export_failed"
        Failure.COPY_ERROR -> "error_file_unreadable"
        Failure.VALIDATION_FAILED -> "error_no_video"
        Failure.GENERIC -> "error_generic"
    }

    private fun emit(state: State) {
        onState?.invoke(state)
    }
}

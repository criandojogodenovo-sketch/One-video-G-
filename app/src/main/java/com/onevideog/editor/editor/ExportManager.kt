package com.onevideog.editor.editor

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.StatFs
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.onevideog.editor.data.ExportResolutionEnum
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.editor.ExportConfig.Quality
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Real offline video export built on Media3 Transformer.
 *
 * Long-export reliability notes (see docs/DEVICE_TEST_PLAN.md):
 *  - The caller owns [scope]; production exports run inside a foreground
 *    service ([ExportService]) so screen-off/background process freezing
 *    cannot cancel or stall the pipeline mid-transform.
 *  - Preflight cleans stale temp output, then checks free space for the full
 *    transient footprint (temp file + SAF copy coexist before cleanup).
 *  - Failures carry a mapped [Failure] plus a sanitized diagnostics line
 *    appended to cacheDir/exports/diagnostics.log (no media contents, no
 *    document URIs).
 *  - Success is only reported after the MP4 finalizes, parses, and the copy
 *    to the user-chosen destination is size-verified.
 *
 * Pipeline per project: [CompositionFactory.build] → Transformer (H.264/AAC)
 * → progress polling → MediaMetadataRetriever validation → streamed copy to
 * the SAF destination. Fully offline, no watermark.
 */
@UnstableApi
class ExportManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    enum class Phase { IDLE, QUEUED, RUNNING, FINALIZING, DONE, FAILED, CANCELLED }

    /** Actionable failure categories (mapped from ExportException codes). */
    enum class Failure {
        INSUFFICIENT_STORAGE,
        IO_READ,
        IO_WRITE,
        ENCODING,
        DECODING,
        MUXING,
        EXPORT_ERROR,
        COPY_ERROR,
        VALIDATION_FAILED,
        GENERIC,
    }

    sealed interface State {
        data object Idle : State
        data object Queued : State
        data class Running(val progressPercent: Int) : State
        data object Finalizing : State
        data class Done(val outputUri: Uri, val durationMs: Long) : State
        data class Failed(val failure: Failure, val detail: String? = null) : State
        data object Cancelled : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> get() = _state

    /** Optional callback observer kept for simple callers. */
    var onState: ((State) -> Unit)? = null

    private var transformer: Transformer? = null
    private var job: Job? = null
    private var cancelled = false
    private var completion: CompletableDeferred<Boolean>? = null
    /** Latest technical detail for diagnostics/UI (single-writer coroutine). */
    private var pendingDetail: String? = null

    val isRunning: Boolean
        get() = job?.isActive == true

    private fun exportsDir(): File = File(context.cacheDir, "exports").apply { mkdirs() }

    fun start(
        project: OvgProject,
        resolution: ExportResolutionEnum,
        quality: Quality,
        destinationUri: Uri,
    ) {
        if (isRunning) return
        val problem = ExportConfig.validateForExport(project)
        if (problem != null) {
            emit(State.Failed(Failure.VALIDATION_FAILED, problem.name))
            return
        }
        val durationMs = ProjectMath.projectDurationMs(project)

        // Preflight: drop stale output from earlier crashed/cancelled runs.
        val removed = ExportPreflight.cleanupStaleFiles(exportsDir())
        val required = ExportPreflight.requiredBytes(resolution, quality, durationMs)
        val stat = StatFs(context.cacheDir.absolutePath)
        if (stat.availableBytes < required) {
            writeDiagnostics(
                phase = "preflight",
                failure = Failure.INSUFFICIENT_STORAGE,
                detail = "needed=$required free=${stat.availableBytes} removedStale=$removed",
                durationMs = durationMs,
                resolution = resolution,
                quality = quality,
            )
            emit(State.Failed(Failure.INSUFFICIENT_STORAGE, "needed=$required"))
            return
        }

        cancelled = false
        emit(State.Queued)

        val tempFile = File(exportsDir(), "export_${System.currentTimeMillis()}.mp4")
        val workDir = File(exportsDir(), "work").apply { mkdirs() }
        val completionDeferred = CompletableDeferred<Boolean>()
        completion = completionDeferred

        job = scope.launch {
            var plan: CompositionFactory.Plan? = null
            try {
                emit(State.Running(0))
                plan = withContext(Dispatchers.Default) {
                    CompositionFactory.build(context, project, resolution, workDir)
                }
                startTransformer(
                    plan = plan,
                    resolution = resolution,
                    quality = quality,
                    outputPath = tempFile,
                    completionDeferred = completionDeferred,
                )
                pollProgress(completionDeferred)
                val succeeded = completionDeferred.await()

                when {
                    cancelled -> {
                        tempFile.delete()
                        emit(State.Cancelled)
                    }
                    !succeeded || !tempFile.isFile || tempFile.length() == 0L -> {
                        tempFile.delete()
                        emit(State.Failed(Failure.EXPORT_ERROR, pendingDetail))
                    }
                    else -> {
                        emit(State.Finalizing)
                        val verified = withContext(Dispatchers.IO) {
                            verifyPlayable(tempFile, durationMs) &&
                                copyToDestination(tempFile, destinationUri)
                        }
                        tempFile.delete()
                        if (verified) {
                            writeDiagnostics(
                                phase = "done",
                                failure = null,
                                detail = "duration=$durationMs size=${destinationSize(destinationUri)}",
                                durationMs = durationMs,
                                resolution = resolution,
                                quality = quality,
                            )
                            emit(State.Done(destinationUri, durationMs))
                        } else {
                            writeDiagnostics(
                                phase = "verify_or_copy",
                                failure = Failure.COPY_ERROR,
                                detail = pendingDetail,
                                durationMs = durationMs,
                                resolution = resolution,
                                quality = quality,
                            )
                            emit(State.Failed(Failure.COPY_ERROR, pendingDetail))
                        }
                    }
                }
            } catch (t: Throwable) {
                tempFile.delete()
                val failure = Failure.GENERIC
                writeDiagnostics(
                    phase = "exception",
                    failure = failure,
                    detail = t.javaClass.simpleName + ": " + (t.message ?: "").take(300),
                    durationMs = durationMs,
                    resolution = resolution,
                    quality = quality,
                )
                emit(State.Failed(failure, t.javaClass.simpleName))
            } finally {
                // Release pipeline state if the coroutine dies abnormally
                // (e.g. scope cancellation); cancel() is a no-op when the
                // transformer already completed.
                if (!completionDeferred.isCompleted) {
                    runCatching { transformer?.cancel() }
                    completionDeferred.complete(false)
                }
                transformer = null
                completion = null
                SilenceWav.cleanup(workDir)
                plan?.tempFiles?.forEach { it.delete() }
                job = null
            }
        }
    }

    fun cancel() {
        if (!isRunning || cancelled) return
        cancelled = true
        runCatching { transformer?.cancel() }
        completion?.complete(false)
    }

    // ------------------------------------------------------------------ engine

    private fun startTransformer(
        plan: CompositionFactory.Plan,
        resolution: ExportResolutionEnum,
        quality: Quality,
        outputPath: File,
        completionDeferred: CompletableDeferred<Boolean>,
    ) {
        val encoderSettings = VideoEncoderSettings.Builder()
            .setBitrate(ExportConfig.videoBitrate(resolution, quality))
            .build()

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, result: ExportResult) {
                completionDeferred.complete(true)
            }

            override fun onError(
                composition: Composition,
                result: ExportResult,
                exception: ExportException,
            ) {
                val failure = ExportFailure.fromExportException(exception)
                pendingDetail = "code=${exception.errorCode} ${exception.message.orEmpty().take(200)}"
                writeDiagnostics(
                    phase = "transformer_error",
                    failure = failure,
                    detail = pendingDetail,
                    durationMs = plan.plannedDurationMs,
                    resolution = null,
                    quality = quality,
                )
                completionDeferred.complete(false)
            }
        }

        try {
            transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setTransformationRequest(TransformationRequest.Builder().build())
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(encoderSettings)
                        .build(),
                )
                .addListener(listener)
                .build()
            transformer?.start(plan.composition, outputPath.absolutePath)
        } catch (t: Throwable) {
            // Synchronous start failures (no decoder surface, invalid
            // composition, codec init) must not leave a zombie state.
            val failure = ExportFailure.fromThrowable(t)
            pendingDetail = "start: ${t.javaClass.simpleName} ${(t.message ?: "").take(200)}"
            writeDiagnostics(
                phase = "transformer_start",
                failure = failure,
                detail = pendingDetail,
                durationMs = plan.plannedDurationMs,
                resolution = null,
                quality = quality,
            )
            completionDeferred.complete(false)
        }
    }

    private suspend fun pollProgress(completionDeferred: CompletableDeferred<Boolean>) {
        val holder = ProgressHolder()
        while (currentCoroutineContext().isActive && !completionDeferred.isCompleted) {
            val t = transformer ?: break
            val progressState = t.getProgress(holder)
            val pct = when (progressState) {
                Transformer.PROGRESS_STATE_AVAILABLE -> holder.progress.coerceIn(0, 100)
                Transformer.PROGRESS_STATE_NO_TRANSFORMATION -> 100
                else -> 0
            }
            emit(State.Running(pct))
            delay(250)
        }
    }

    // ------------------------------------------------------------- validation

    /**
     * Parses the finished MP4 and checks it is plausible: decodable, has
     * video, positive duration, and not wildly shorter than planned (which
     * would indicate a truncated export).
     */
    private fun verifyPlayable(file: File, plannedDurationMs: Long): Boolean = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val hasVideo =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val plausible = if (plannedDurationMs > 0) {
                duration >= plannedDurationMs * 0.6 && duration <= plannedDurationMs * 2.0 + 2_000
            } else {
                duration > 0
            }
            pendingDetail = "verify: hasVideo=$hasVideo duration=$duration planned=$plannedDurationMs"
            hasVideo && duration > 0 && plausible
        } finally {
            runCatching { retriever.release() }
        }
    }.onFailure {
        pendingDetail = "verify exception: ${it.javaClass.simpleName}"
    }.getOrDefault(false)

    private fun destinationSize(uri: Uri): Long = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    }.getOrDefault(-1L)

    /** Streams [source] to the SAF document, then size-verifies the copy. */
    private fun copyToDestination(source: File, destinationUri: Uri): Boolean = runCatching {
        context.contentResolver.openOutputStream(destinationUri, "wt")?.use { out ->
            source.inputStream().use { input -> input.copyTo(out) }
            out.flush()
        } ?: return@runCatching false
        val copied = destinationSize(destinationUri)
        pendingDetail = "copy: source=${source.length()} dest=$copied"
        copied == source.length()
    }.onFailure {
        pendingDetail = "copy exception: ${it.javaClass.simpleName} ${(it.message ?: "").take(120)}"
    }.getOrDefault(false)

    // ------------------------------------------------------------ diagnostics

    /**
     * Appends a sanitized, developer-oriented line to
     * cacheDir/exports/diagnostics.log. Never logs media contents or
     * document URIs.
     */
    private fun writeDiagnostics(
        phase: String,
        failure: Failure?,
        detail: String?,
        durationMs: Long,
        resolution: ExportResolutionEnum?,
        quality: Quality,
    ) {
        runCatching {
            val dir = exportsDir()
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val freeMb = runCatching {
                StatFs(context.cacheDir.absolutePath).availableBytes / (1024 * 1024)
            }.getOrDefault(-1)
            val line = buildString {
                append(ts).append(" | ").append(phase)
                append(" | failure=").append(failure?.name ?: "-")
                append(" | detail=").append(detail?.take(300) ?: "-")
                append(" | durationMs=").append(durationMs)
                append(" | res=").append(resolution?.name ?: "-")
                append(" | quality=").append(quality.name)
                append(" | freeMb=").append(freeMb)
                append(" | device=").append(Build.MODEL)
                append(" | sdk=").append(Build.VERSION.SDK_INT)
            }
            File(dir, "diagnostics.log").appendText(line + "\n")
        }
    }

    private fun emit(state: State) {
        _state.value = state
        onState?.invoke(state)
    }
}

/**
 * Preflight helpers, kept pure for unit tests.
 */
object ExportPreflight {

    /**
     * Transient footprint: the export temp file plus the SAF copy that
     * coexists with it before cleanup, with headroom for muxer overhead and
     * filesystem slack. Never a duration limit — long exports just need the
     * space for roughly two copies of the output.
     */
    fun requiredBytes(
        resolution: ExportResolutionEnum,
        quality: Quality,
        durationMs: Long,
    ): Long = (ExportConfig.estimatedBytes(resolution, quality, durationMs) * 2.2).toLong() +
        64L * 1024 * 1024

    /** Deletes stale export_*.mp4 files from previous runs. Returns count. */
    fun cleanupStaleFiles(exportsDir: File): Int =
        exportsDir.listFiles { f -> f.isFile && f.name.startsWith("export_") && f.name.endsWith(".mp4") }
            ?.count { it.delete() } ?: 0
}

/** Maps Media3 export errors to actionable [ExportManager.Failure] categories. */
object ExportFailure {

    fun fromExportException(exception: ExportException): ExportManager.Failure =
        when (exception.errorCode) {
            ExportException.ERROR_CODE_IO_UNSPECIFIED,
            ExportException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            ExportException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            ExportException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            ExportException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
            ExportException.ERROR_CODE_IO_NO_PERMISSION,
            ExportException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            ExportException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            -> ExportManager.Failure.IO_READ

            ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
            ExportException.ERROR_CODE_ENCODING_FAILED,
            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
            -> ExportManager.Failure.ENCODING

            ExportException.ERROR_CODE_DECODER_INIT_FAILED,
            ExportException.ERROR_CODE_DECODING_FAILED,
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            -> ExportManager.Failure.DECODING

            ExportException.ERROR_CODE_MUXING_FAILED,
            ExportException.ERROR_CODE_MUXING_TIMEOUT,
            ExportException.ERROR_CODE_MUXING_APPEND,
            -> ExportManager.Failure.MUXING

            ExportException.ERROR_CODE_AUDIO_PROCESSING_FAILED,
            ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
            -> ExportManager.Failure.EXPORT_ERROR

            else -> ExportManager.Failure.EXPORT_ERROR
        }

    fun fromThrowable(t: Throwable): ExportManager.Failure =
        when (t) {
            is ExportException -> fromExportException(t)
            is java.io.IOException -> ExportManager.Failure.IO_WRITE
            is IllegalStateException, is IllegalArgumentException -> ExportManager.Failure.EXPORT_ERROR
            else -> ExportManager.Failure.GENERIC
        }
}

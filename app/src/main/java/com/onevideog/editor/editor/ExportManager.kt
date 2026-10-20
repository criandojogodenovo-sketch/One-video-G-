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
import com.onevideog.editor.diag.DiagContext
import com.onevideog.editor.diag.DiagSanitizer
import com.onevideog.editor.diag.DiagnosticsLogger
import com.onevideog.editor.diag.ExportOp
import com.onevideog.editor.diag.OpRegistry
import com.onevideog.editor.diag.Severity
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
    /**
     * The single thread Media3 Transformer operations run on (v0.2.4).
     * Media3 1.4.1 captures Util.getCurrentOrMainLooper() at build time and
     * throws "Transformer is accessed on the wrong thread" when start /
     * getProgress / cancel happen elsewhere — the export coroutine used to
     * run on Dispatchers.Default (no Looper), which is exactly the device
     * failure. Injectable for JVM tests.
     */
    private val transformerThread: TransformerThread = MainTransformerThread(),
    /** Per-manager concurrency gate (v0.2.4). Injectable for JVM tests. */
    private val gate: ExportGate = ExportGate(),
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

    /** Correlation handle for the current export (null when idle). */
    private var op: ExportOp? = null

    private val opRegistry = OpRegistry()

    /** Wall-clock start of the current export (for total-duration evidence). */
    @Volatile private var exportStartedMs: Long = 0L

    private fun freeMemoryMb(): Long {
        val rt = Runtime.getRuntime()
        return (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) / (1024 * 1024)
    }

    /**
     * Stage C evidence: every source URI (video + audio) must still open.
     * Logs per-clip sanitized results; NEVER logs document URIs or names.
     */
    private fun checkSourceMedia(project: OvgProject, exportOp: ExportOp) {
        var unreadable = 0
        val videoOk = project.clips.count { clip ->
            val ok = isUriReadable(Uri.parse(clip.uri))
            if (!ok) unreadable++
            ok
        }
        val audioOk = project.audioClips.count { clip ->
            val ok = isUriReadable(Uri.parse(clip.uri))
            if (!ok) unreadable++
            ok
        }
        DiagnosticsLogger.i(
            "export", ExportOp.Stages.SOURCE_MEDIA, exportOp.opId,
            if (unreadable == 0) "all source media readable" else "SOME SOURCE MEDIA UNREADABLE",
            meta = listOf(
                "videoReadable" to "$videoOk/${project.clips.size}",
                "audioReadable" to "$audioOk/${project.audioClips.size}",
                "unreadable" to unreadable.toString(),
            ),
        )
    }

    private fun isUriReadable(uri: Uri): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    val isRunning: Boolean
        get() = job?.isActive == true

    private fun exportsDir(): File = File(context.cacheDir, "exports").apply { mkdirs() }

    fun start(
        project: OvgProject,
        resolution: ExportResolutionEnum,
        quality: Quality,
        destinationUri: Uri,
    ) {
        // v0.2.4: atomic single-export gate, claimed BEFORE any slow work.
        // The old `if (isRunning) return` raced (job was only assigned after
        // slow main-thread preflight), letting two near-simultaneous requests
        // start two Transformers (device ops exp-b7bf477d / exp-b96c0125).
        if (!gate.tryBegin()) {
            DiagnosticsLogger.w(
                "export", "START_REJECTED", op?.opId,
                "export start rejected: another export is already in flight",
                meta = listOf(
                    "thread" to Thread.currentThread().name,
                    "gate" to "manager",
                ),
            )
            return
        }
        val exportOp = ExportOp(OpRegistry.newOpId("exp"), opRegistry)
        op = exportOp
        // Cheap, pure validation stays synchronous; ALL I/O (source media,
        // StatFs, stale cleanup) moved inside the coroutine below so the gate
        // is never held open by slow main-thread work and duplicate requests
        // are rejected immediately.
        val problem = ExportConfig.validateForExport(project)
        DiagnosticsLogger.i(
            "export", ExportOp.Stages.VALIDATE, exportOp.opId,
            if (problem == null) "project validated for export" else "project validation FAILED",
            meta = listOf(
                "problem" to (problem?.name ?: "none"),
                "durationMs" to ProjectMath.projectDurationMs(project).toString(),
                "clips" to project.clips.size.toString(),
                "overlays" to project.overlays.size.toString(),
                "audio" to project.audioClips.size.toString(),
                "resolution" to resolution.name, "quality" to quality.name,
                "thread" to Thread.currentThread().name,
            ),
        )
        if (problem != null) {
            gate.end()
            exportOp.fail(Failure.VALIDATION_FAILED.name, problem.name)
            DiagnosticsLogger.noteExportState(
                exportOp.opId,
                "state=FAILED stage=VALIDATE cat=VALIDATION_FAILED detail=${problem.name}",
            )
            emit(State.Failed(Failure.VALIDATION_FAILED, problem.name))
            return
        }
        val durationMs = ProjectMath.projectDurationMs(project)

        cancelled = false
        exportStartedMs = System.currentTimeMillis()
        exportOp.transition(ExportOp.Stages.SERVICE_START)
        emit(State.Queued)

        val tempFile = File(exportsDir(), "export_${System.currentTimeMillis()}.mp4")
        val workDir = File(exportsDir(), "work").apply { mkdirs() }
        val completionDeferred = CompletableDeferred<Boolean>()
        completion = completionDeferred

        job = scope.launch {
            // Stage C + storage preflight now run OFF the main thread.
            checkSourceMedia(project, exportOp)
            val removed = withContext(Dispatchers.IO) {
                ExportPreflight.cleanupStaleFiles(exportsDir())
            }
            val required = ExportPreflight.requiredBytes(resolution, quality, durationMs)
            val stat = withContext(Dispatchers.IO) { StatFs(context.cacheDir.absolutePath) }
            DiagnosticsLogger.i(
                "export", ExportOp.Stages.PREFLIGHT, exportOp.opId,
                "storage preflight",
                meta = listOf(
                    "neededBytes" to required.toString(),
                    "freeBytes" to stat.availableBytes.toString(),
                    "removedStale" to removed.toString(),
                ),
            )
            if (stat.availableBytes < required) {
                writeDiagnostics(
                    phase = "preflight",
                    failure = Failure.INSUFFICIENT_STORAGE,
                    detail = "needed=$required free=${stat.availableBytes} removedStale=$removed",
                    durationMs = durationMs,
                    resolution = resolution,
                    quality = quality,
                )
                exportOp.fail(Failure.INSUFFICIENT_STORAGE.name, "needed=$required free=${stat.availableBytes}")
                DiagnosticsLogger.noteExportState(
                    exportOp.opId,
                    "state=FAILED stage=PREFLIGHT cat=INSUFFICIENT_STORAGE needed=$required free=${stat.availableBytes}",
                )
                emit(State.Failed(Failure.INSUFFICIENT_STORAGE, "needed=$required"))
                // Terminal path before the try/finally — the gate MUST be
                // released here too, otherwise every later start would be
                // rejected forever.
                gate.end()
                return@launch
            }
            var plan: CompositionFactory.Plan? = null
            try {
                emit(State.Running(0))
                val planStarted = System.currentTimeMillis()
                plan = withContext(Dispatchers.Default) {
                    DiagContext.withOp(exportOp.opId) {
                        CompositionFactory.build(context, project, resolution, workDir)
                    }
                }
                DiagnosticsLogger.i(
                    "export", ExportOp.Stages.PLAN, exportOp.opId,
                    "composition plan built",
                    meta = listOf(
                        "plannedDurationMs" to plan.plannedDurationMs.toString(),
                        "tempFiles" to plan.tempFiles.size.toString(),
                        "elapsedMs" to (System.currentTimeMillis() - planStarted).toString(),
                    ),
                )
                exportOp.transition(ExportOp.Stages.TRANSFORMER_START)
                startTransformer(
                    plan = plan,
                    resolution = resolution,
                    quality = quality,
                    outputPath = tempFile,
                    completionDeferred = completionDeferred,
                    exportOp = exportOp,
                )
                pollProgress(completionDeferred, exportOp)
                val succeeded = completionDeferred.await()

                when {
                    cancelled -> {
                        tempFile.delete()
                        exportOp.transition(
                            ExportOp.Stages.CANCELLED,
                            Severity.INFO,
                            "export cancelled by user",
                        )
                        DiagnosticsLogger.clearExportMarker()
                        DiagnosticsLogger.noteExportState(exportOp.opId, "state=CANCELLED stage=TRANSFORM")
                        emit(State.Cancelled)
                    }
                    !succeeded || !tempFile.isFile || tempFile.length() == 0L -> {
                        tempFile.delete()
                        exportOp.fail(
                            Failure.EXPORT_ERROR.name,
                            "transformer reported success but output missing/empty (${pendingDetail ?: "no detail"})",
                        )
                        DiagnosticsLogger.e(
                            "export", ExportOp.Stages.COMPLETED, exportOp.opId,
                            "output validation failed: transformer done but file missing/empty",
                            meta = listOf("detail" to (pendingDetail ?: "-")),
                        )
                        DiagnosticsLogger.clearExportMarker()
                        DiagnosticsLogger.noteExportState(
                            exportOp.opId,
                            "state=FAILED stage=FINALIZE cat=EXPORT_ERROR detail=${pendingDetail ?: "-"}",
                        )
                        emit(State.Failed(Failure.EXPORT_ERROR, pendingDetail))
                    }
                    else -> {
                        emit(State.Finalizing)
                        exportOp.transition(
                            ExportOp.Stages.FINALIZE, Severity.INFO,
                            "validating and copying output",
                        )
                        DiagnosticsLogger.markExportLive(exportOp.opId, "FINALIZING")
                        val verifyStarted = System.currentTimeMillis()
                        val verified = withContext(Dispatchers.IO) {
                            verifyPlayable(tempFile, durationMs) &&
                                copyToDestination(tempFile, destinationUri)
                        }
                        tempFile.delete()
                        if (verified) {
                            val outSize = destinationSize(destinationUri)
                            writeDiagnostics(
                                phase = "done",
                                failure = null,
                                detail = "duration=$durationMs size=$outSize",
                                durationMs = durationMs,
                                resolution = resolution,
                                quality = quality,
                            )
                            exportOp.transition(
                                ExportOp.Stages.DONE, Severity.INFO,
                                "export completed and verified",
                            )
                            DiagnosticsLogger.i(
                                "export", ExportOp.Stages.DONE, exportOp.opId,
                                "export COMPLETED (output verified)",
                                meta = listOf(
                                    "durationMs" to durationMs.toString(),
                                    "outputBytes" to outSize.toString(),
                                    "finalizeMs" to (System.currentTimeMillis() - verifyStarted).toString(),
                                    "totalMs" to (System.currentTimeMillis() - exportStartedMs).toString(),
                                ),
                            )
                            DiagnosticsLogger.clearExportMarker()
                            DiagnosticsLogger.noteExportState(
                                exportOp.opId,
                                "state=SUCCESS durationMs=$durationMs outputBytes=$outSize",
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
                            exportOp.fail(Failure.COPY_ERROR.name, pendingDetail)
                            DiagnosticsLogger.e(
                                "export", ExportOp.Stages.FINALIZE, exportOp.opId,
                                "output verify/copy FAILED", null,
                                listOf("detail" to (pendingDetail ?: "-")),
                            )
                            DiagnosticsLogger.clearExportMarker()
                            DiagnosticsLogger.noteExportState(
                                exportOp.opId,
                                "state=FAILED stage=FINALIZE cat=COPY_ERROR detail=${pendingDetail ?: "-"}",
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
                exportOp.fail(failure.name, t.javaClass.simpleName)
                DiagnosticsLogger.e(
                    "export", "EXCEPTION", exportOp.opId,
                    "export coroutine threw (stage=${exportOp.stage})", t,
                    listOf("memoryFreeMb" to freeMemoryMb().toString()),
                )
                DiagnosticsLogger.clearExportMarker()
                DiagnosticsLogger.noteExportState(
                    exportOp.opId,
                    "state=FAILED stage=EXCEPTION cat=GENERIC exc=${t.javaClass.simpleName}",
                )
                emit(State.Failed(failure, t.javaClass.simpleName))
            } finally {
                // Release pipeline state if the coroutine dies abnormally
                // (e.g. scope cancellation); cancel() is a no-op when the
                // transformer already completed. The cancel is POSTED to the
                // transformer application thread (Media3 1.4.1 enforces it).
                if (!completionDeferred.isCompleted) {
                    cancelTransformerOnApplicationThread()
                    completionDeferred.complete(false)
                }
                transformer = null
                completion = null
                SilenceWav.cleanup(workDir)
                plan?.tempFiles?.forEach { it.delete() }
                job = null
                // v0.2.4: the single-export gate is ALWAYS released, on every
                // terminal path (done/failed/cancelled/exception/scope death).
                gate.end()
            }
        }
    }

    fun cancel() {
        if (!isRunning || cancelled) return
        cancelled = true
        DiagnosticsLogger.w(
            "export", "CANCEL_REQUESTED", op?.opId,
            "user requested export cancellation",
        )
        cancelTransformerOnApplicationThread()
        completion?.complete(false)
    }

    /**
     * Cancels the Transformer on its application thread. Safe from ANY
     * caller thread: ExportService.onStartCommand / onDestroy arrive on the
     * main thread (direct call would be legal), but the coroutine finally
     * block runs on Dispatchers.Default — posting keeps every access on the
     * thread Media3 1.4.1 requires. Captures the reference first so a late
     * post cannot miss the instance before it is nulled in the finally.
     */
    private fun cancelTransformerOnApplicationThread() {
        val current = transformer
        transformerThread.post {
            runCatching { current?.cancel() }
                .onFailure {
                    DiagnosticsLogger.w(
                        "export", "TRANSFORMER_CANCEL", op?.opId,
                        "transformer cancel threw (recorded, not fatal)",
                    )
                }
        }
    }

    // ------------------------------------------------------------------ engine

    private suspend fun startTransformer(
        plan: CompositionFactory.Plan,
        resolution: ExportResolutionEnum,
        quality: Quality,
        outputPath: File,
        completionDeferred: CompletableDeferred<Boolean>,
        exportOp: ExportOp,
    ) {
        val encoderSettings = VideoEncoderSettings.Builder()
            .setBitrate(ExportConfig.videoBitrate(resolution, quality))
            .build()

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, result: ExportResult) {
                // Completion is recorded but NOT trusted alone: the caller
                // still validates the output file exists/ non-empty/plausible
                // before reporting success (spec section 4, item L).
                DiagnosticsLogger.i(
                    "export", ExportOp.Stages.COMPLETED, exportOp.opId,
                    "Transformer onCompleted",
                    meta = listOf(
                        "processedBytes" to result.fileSizeBytes.toString(),
                        "durationMs" to result.durationMs.toString(),
                    ),
                )
                exportOp.transition(
                    ExportOp.Stages.COMPLETED, Severity.INFO,
                    "transformer completed",
                )
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
                exportOp.fail(failure.name, "code=${exception.errorCode}")
                // Full nested cause chain + memory pressure evidence — the
                // original exception is preserved, never replaced by a
                // generic one (spec section 4, item J/K).
                DiagnosticsLogger.e(
                    "export", ExportOp.Stages.TRANSFORMER_ERROR, exportOp.opId,
                    "Transformer onError (stage=${exportOp.stage}, elapsedMs=" +
                        (System.currentTimeMillis() - exportStartedMs) + ")",
                    exception,
                    listOf(
                        "errorCode" to exception.errorCode.toString(),
                        "failureCat" to failure.name,
                        "memoryFreeMb" to freeMemoryMb().toString(),
                    ),
                )
                DiagnosticsLogger.clearExportMarker()
                DiagnosticsLogger.noteExportState(
                    exportOp.opId,
                    "state=FAILED stage=TRANSFORMER_ERROR cat=$failure code=${exception.errorCode} " +
                        "elapsedMs=${System.currentTimeMillis() - exportStartedMs}",
                )
                completionDeferred.complete(false)
            }
        }

        try {
            // v0.2.4: build AND start run on the ONE transformer application
            // thread (EngineLauncher) — Media3 1.4.1 captures the looper at
            // build time and enforces it on start/getProgress/cancel. The
            // ORIGINAL synchronous failure propagates unchanged into the
            // catch below (real exception preserved, never replaced).
            transformer = EngineLauncher.launchOn(
                thread = transformerThread,
                factory = {
                    Transformer.Builder(context)
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
                },
                starter = { it.start(plan.composition, outputPath.absolutePath) },
            )
            // Durable "export in flight" marker: if the process dies before
            // a terminal state clears it, the next launch reports the export
            // as INTERRUPTED (distinct from a confirmed failure).
            DiagnosticsLogger.markExportLive(exportOp.opId, "RUNNING")
            DiagnosticsLogger.i(
                "export", ExportOp.Stages.TRANSFORMER_START, exportOp.opId,
                "Transformer started",
                meta = listOf(
                    "plannedDurationMs" to plan.plannedDurationMs.toString(),
                    "outputFile" to DiagSanitizer.sanitizeFileName(outputPath.name),
                    "memoryFreeMb" to freeMemoryMb().toString(),
                    "engineThread" to transformerThread.description,
                ),
            )
        } catch (t: Throwable) {
            // Synchronous start failures (wrong thread, no decoder surface,
            // invalid composition, codec init) must not leave a zombie state.
            // The ORIGINAL exception is logged with its full nested cause
            // chain — never replaced by a generic one.
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
            exportOp.fail(failure.name, "start: ${t.javaClass.simpleName}")
            DiagnosticsLogger.e(
                "export", ExportOp.Stages.TRANSFORMER_START, exportOp.opId,
                "Transformer start FAILED synchronously", t,
                listOf(
                    "failureCat" to failure.name,
                    "memoryFreeMb" to freeMemoryMb().toString(),
                ),
            )
            DiagnosticsLogger.clearExportMarker()
            DiagnosticsLogger.noteExportState(
                exportOp.opId,
                "state=FAILED stage=TRANSFORMER_START cat=$failure exc=${t.javaClass.simpleName}",
            )
            completionDeferred.complete(false)
        }
    }

    private suspend fun pollProgress(completionDeferred: CompletableDeferred<Boolean>, exportOp: ExportOp) {
        val holder = ProgressHolder()
        var lastLoggedStep = -10
        while (currentCoroutineContext().isActive && !completionDeferred.isCompleted) {
            val t = transformer ?: break
            // v0.2.4: getProgress is a Transformer application-thread method
            // (verifyApplicationThread in Media3 1.4.1) — route it, do not
            // call it from the Dispatchers.Default worker.
            val progressState = transformerThread.run { t.getProgress(holder) }
            val pct = when (progressState) {
                Transformer.PROGRESS_STATE_AVAILABLE -> holder.progress.coerceIn(0, 100)
                Transformer.PROGRESS_STATE_NO_TRANSFORMATION -> 100
                else -> 0
            }
            emit(State.Running(pct))
            // Stage H evidence at 10% steps (NOT every 250 ms callback —
            // keeps the log bounded and the export fast).
            val step = pct / 10 * 10
            if (step > lastLoggedStep) {
                lastLoggedStep = step
                DiagnosticsLogger.d(
                    "export", ExportOp.Stages.PROGRESS, exportOp.opId,
                    "progress $pct%",
                    meta = listOf(
                        "elapsedMs" to (System.currentTimeMillis() - exportStartedMs).toString(),
                    ),
                )
            }
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

package com.onevideog.editor.diag

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.onevideog.editor.BuildConfig
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Central, temporary diagnostics logger (v0.2.3 — evidence-collection build).
 *
 * Architecture (concurrency-safe by construction):
 *  - producers call [log]/[e]/[w]/[i]/[d] from ANY thread; the call only
 *    enqueues into a BOUNDED queue (256 events, oldest dropped) under a lock
 *    — it never blocks on IO and never allocates for the payload beyond the
 *    event object itself;
 *  - ONE dedicated writer [HandlerThread] drains the queue into the
 *    [DiagLogStore] (single serialized writer), mirroring each event to
 *    Logcat with the stable tag `OvgDiag`;
 *  - persistence is incremental (one line per event), rotating (1 MB per
 *    file, current + 1 previous ≈ 2 MB total cap) and app-private
 *    (`files/diagnostics`);
 *  - failures of the logging system itself are swallowed (Logcat-only
 *    warning inside [safeTag]) and can never crash the editor or recurse
 *    into the logger;
 *  - OFF by default; the Settings toggle persists into the same
 *    SharedPreferences file the rest of the app uses.
 *
 * Non-goals (documented in docs/DIAGNOSTICS.md): no telemetry, no network,
 * no native-crash capture, no OS-kill detection beyond the interruption
 * marker.
 */
object DiagnosticsLogger {

    private const val TAG = "OvgDiag"
    private const val PREFS_FILE = "one_video_g_settings"
    private const val PREF_KEY = "diagnostics_enabled"
    private const val QUEUE_CAPACITY = 256
    private const val MARKER_FILE = "export-interrupted.txt"

    /** Log-tail bound for registry replay after a process restart. */
    private const val REPLAY_LINES = 2_000

    @Volatile private var appContext: Context? = null
    private var store: DiagLogStore? = null
    private var registry = OpRegistry()
    private var writerHandler: Handler? = null
    private var writerThread: HandlerThread? = null

    private val lock = Any()
    private val queue = ArrayDeque<DiagnosticEvent>()
    @Volatile private var drainScheduled = false

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> get() = _enabled

    /** Stats snapshot for the Settings section. */
    data class Stats(val events: Long, val approxKb: Long, val rotated: Boolean)

    private val _stats = MutableStateFlow(Stats(0, 0, false))
    val stats: StateFlow<Stats> get() = _stats

    @Volatile var lastExportStateLine: String? = null
        private set

    @Volatile private var interruptedExportLine: String? = null

    // ------------------------------------------------------------- lifecycle

    /** Idempotent. Called once from [com.onevideog.editor.OvgApp.onCreate]. */
    fun init(context: Context) {
        synchronized(lock) {
            if (appContext != null) return
            val app = context.applicationContext
            appContext = app
            val storeDir = File(app.filesDir, "diagnostics")
            store = DiagLogStore(storeDir)
            val prefs = app.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            val persisted = prefs.getBoolean(PREF_KEY, false)
            store?.setEnabled(persisted)
            _enabled.value = persisted
            writerThread = HandlerThread("OvgDiagWriter").apply { start() }
            writerHandler = Handler(writerThread!!.looper)
            consumeInterruptedMarker()
            refreshStats()
            // v0.2.5 restart recovery: the previous implementation kept the
            // event count and the op registry ONLY in RAM while the log
            // FILES persist — a report copied after a restart showed real
            // events in its tail but `events_recorded=0` and "Recent
            // operations: none recorded". Recount from disk + replay the
            // persisted tail into the registry (writer thread; UI reads
            // fresh values via stats/buildReport whenever they land).
            writerHandler?.post {
                runCatching {
                    store?.recountFromDisk()
                    val lines = store?.readRecentLines(REPLAY_LINES) ?: emptyList()
                    DiagRegistryReplay.replay(lines, registry)
                    refreshStats()
                }
            }
        }
    }

    /** Enable/disable (Settings toggle). Persisted; safe from any thread. */
    fun setEnabled(value: Boolean) {
        val ctx = appContext ?: return
        runCatching {
            ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_KEY, value).apply()
        }
        store?.setEnabled(value)
        _enabled.value = value
        if (!value) synchronized(lock) { queue.clear() }
        i("diag", "STATE", null, if (value) "diagnostics enabled" else "diagnostics disabled")
    }

    // ------------------------------------------------------------- logging

    fun d(
        subsystem: String,
        stage: String,
        opId: String?,
        message: String,
        throwable: Throwable? = null,
        meta: List<Pair<String, String>> = emptyList(),
    ) = log(Severity.DEBUG, subsystem, stage, opId, message, meta, throwable)

    fun i(
        subsystem: String,
        stage: String,
        opId: String?,
        message: String,
        meta: List<Pair<String, String>> = emptyList(),
    ) = log(Severity.INFO, subsystem, stage, opId, message, meta, null)

    fun w(
        subsystem: String,
        stage: String,
        opId: String?,
        message: String,
        throwable: Throwable? = null,
        meta: List<Pair<String, String>> = emptyList(),
    ) = log(Severity.WARN, subsystem, stage, opId, message, meta, throwable)

    fun e(
        subsystem: String,
        stage: String,
        opId: String?,
        message: String,
        throwable: Throwable? = null,
        meta: List<Pair<String, String>> = emptyList(),
    ) = log(Severity.ERROR, subsystem, stage, opId, message, meta, throwable)

    /** Full-form logging call (metadata included). */
    fun log(
        severity: Severity,
        subsystem: String,
        stage: String,
        opId: String?,
        message: String?,
        meta: List<Pair<String, String>> = emptyList(),
        throwable: Throwable? = null,
    ) {
        if (!_enabled.value) return
        val event = DiagnosticEvent(
            epochMs = System.currentTimeMillis(),
            severity = severity,
            subsystem = subsystem.take(24),
            stage = stage.take(40),
            opId = opId?.take(24),
            message = message,
            meta = meta,
            throwable = throwable,
        )
        var dropped = false
        synchronized(lock) {
            if (queue.size >= QUEUE_CAPACITY) {
                queue.removeFirst()
                dropped = true
            }
            queue.addLast(event)
        }
        if (opId != null) {
            registry.record(
                opId = event.opId ?: opId,
                kind = kindFor(subsystem),
                stage = event.stage,
                severity = severity,
                atMs = event.epochMs,
                finalState = finalStateFor(stage, severity),
                detail = message,
            )
        }
        scheduleDrain()
        if (dropped) {
            // Meta-event about the drop goes straight to Logcat only (no
            // recursion into the queue).
            Log.w(TAG, "queue full; oldest diagnostic event dropped")
        }
    }

    private fun scheduleDrain() {
        val handler = writerHandler ?: return
        synchronized(lock) {
            if (drainScheduled) return
            drainScheduled = true
        }
        handler.post {
            drain()
            refreshStats()
            synchronized(lock) { drainScheduled = false }
        }
    }

    private fun drain() {
        while (true) {
            val event = synchronized(lock) { queue.removeFirstOrNull() } ?: return
            val s = store ?: return
            runCatching { s.append(event) }
                .onFailure { Log.w(safeTag(), "persist failed: ${it.javaClass.simpleName}") }
            mirrorToLogcat(event)
        }
    }

    private fun mirrorToLogcat(event: DiagnosticEvent) {
        runCatching {
            val line = buildString {
                append(event.subsystem).append('/').append(event.stage)
                event.opId?.let { append(" op=").append(it) }
                event.message?.let { append(" :: ").append(it) }
                event.throwable?.let { append(" :: ").append(it.javaClass.name) }
            }
            when (event.severity) {
                Severity.DEBUG -> Log.d(TAG, line, event.throwable)
                Severity.INFO -> Log.i(TAG, line)
                Severity.WARN -> Log.w(TAG, line)
                Severity.ERROR -> Log.e(TAG, line, event.throwable)
            }
        }
    }

    private fun refreshStats() {
        val s = store ?: return
        val bytes = runCatching { s.approximateTotalBytes() }.getOrDefault(-1L)
        _stats.value = Stats(
            events = s.eventCount(),
            approxKb = if (bytes < 0) -1 else bytes / 1024,
            rotated = s.hasRotated(),
        )
    }

    // ------------------------------------------------------ crash path

    /**
     * Synchronous, bounded crash record (called from the uncaught-exception
     * handler BEFORE delegating to the previous handler). Appends directly
     * to the store bypassing the queue; respects the enable gate (crash
     * capture follows the user's diagnostics toggle). Never throws and
     * never recurses into the async logger.
     */
    fun writeCrashNow(thread: Thread?, throwable: Throwable) {
        val s = store ?: return
        runCatching {
            s.append(
                DiagnosticEvent(
                    epochMs = System.currentTimeMillis(),
                    severity = Severity.ERROR,
                    subsystem = "crash",
                    stage = "UNCAUGHT",
                    opId = OpRegistry.newOpId("crash", SystemClock.uptimeMillis()),
                    message = "uncaught exception on thread=${thread?.name ?: "?"}",
                    throwable = throwable,
                ),
            )
        }
    }

    // ------------------------------------------- export interruption marker

    /**
     * Durably records that export [opId] is RUNNING/FINALIZING. If the
     * process dies before [clearExportMarker], the next launch reports the
     * export as interrupted (distinguishable from a confirmed failure).
     * Small synchronous write on the caller's (service) thread.
     */
    fun markExportLive(opId: String, stage: String) {
        val ctx = appContext ?: return
        runCatching {
            val dir = File(ctx.filesDir, "diagnostics")
            if (!dir.exists() && !dir.mkdirs()) return
            File(dir, MARKER_FILE).writeText("op=$opId stage=$stage at=${System.currentTimeMillis()}")
        }
    }

    /** Clears the marker on confirmed success/failure/cancel. */
    fun clearExportMarker() {
        val ctx = appContext ?: return
        runCatching { File(File(ctx.filesDir, "diagnostics"), MARKER_FILE).delete() }
    }

    /** Reads + clears the marker at startup; keeps the line for the report. */
    private fun consumeInterruptedMarker() {
        val ctx = appContext ?: return
        runCatching {
            val file = File(File(ctx.filesDir, "diagnostics"), MARKER_FILE)
            if (!file.isFile) return
            val content = file.readText().trim()
            file.delete()
            if (content.isBlank()) return
            interruptedExportLine = "warning=previous export was INTERRUPTED (process death) — $content"
            i("export", "INTERRUPTED_PREVIOUS", opIdPart(content), interruptedExportLine ?: "")
        }
    }

    private fun opIdPart(markerLine: String): String? =
        markerLine.split(' ').firstOrNull { it.startsWith("op=") }?.substringAfter('=')

    /** Called by ExportManager on every terminal state. */
    fun noteExportState(opId: String?, line: String) {
        lastExportStateLine = "op=${opId ?: "-"} $line"
    }

    // ------------------------------------------------------------- report

    /** Builds the clipboard report. Handles empty logs gracefully. */
    fun buildReport(): String {
        val ctx = appContext ?: return "diagnostics not initialized"
        val s = store ?: return "diagnostics not initialized"
        val lines = runCatching { s.readRecentLines(DiagReportBuilder.MAX_LOG_LINES) }
            .getOrDefault(emptyList())
        return DiagReportBuilder.build(
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            androidVersion = android.os.Build.VERSION.RELEASE ?: "?",
            sdkInt = android.os.Build.VERSION.SDK_INT,
            enabled = s.isEnabled(),
            eventCount = s.eventCount(),
            approximateKb = runCatching { s.approximateTotalBytes() / 1024 }.getOrDefault(-1),
            rotated = s.hasRotated(),
            lastWriteFailed = s.lastAppendFailed(),
            opSummaries = registry.snapshot().map { op ->
                "op=${op.opId} kind=${op.kind} stage=${op.lastStage} " +
                    "severity=${op.lastSeverity} events=${op.events} " +
                    "state=${op.finalState ?: "-"}"
            },
            lastExportState = lastExportStateLine,
            interruptedExport = interruptedExportLine,
            logLines = lines,
        )
    }

    /** User-invoked clear: files + registry, never throws. */
    fun clearAll() {
        runCatching { store?.clearAll() }
        synchronized(lock) { queue.clear() }
        registry.clear()
        lastExportStateLine = null
        interruptedExportLine = null
        clearExportMarker()
        refreshStats()
        i("diag", "CLEAR", null, "diagnostic logs cleared by user")
    }

    private fun kindFor(subsystem: String): String = when (subsystem) {
        "import" -> OpRegistry.KIND_IMAGE_IMPORT
        "export" -> OpRegistry.KIND_EXPORT
        else -> subsystem
    }

    private fun finalStateFor(stage: String, severity: Severity): String? = when {
        stage == "DONE" || stage == "COMPLETED_OK" -> "success"
        stage == "FAILED" || severity == Severity.ERROR -> "failure"
        else -> null
    }

    private fun safeTag() = TAG
}

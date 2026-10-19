package com.onevideog.editor.diag

/**
 * Correlation registry for long operations (image import, export).
 *
 * Every operation gets an id (`img-xxxxxxxx` / `exp-xxxxxxxx`); all its log
 * events carry that id, and [OpRegistry] keeps a bounded, in-memory summary
 * of the most recent operations for the clipboard report. Pure JVM code.
 *
 * Durability note: the registry is intentionally in-memory (bounded RAM);
 * the durable cross-restart evidence is the export-interruption marker file
 * handled by [DiagnosticsLogger]. Documented limitation, not an accident.
 */
class OpRegistry(private val capacity: Int = DEFAULT_CAPACITY) {

    data class Operation(
        val opId: String,
        val kind: String,
        val firstMs: Long,
        var lastMs: Long,
        var lastStage: String,
        var lastSeverity: Severity,
        var events: Int,
        var finalState: String? = null,
        var detail: String? = null,
    )

    private val lock = Any()
    private val ops = LinkedHashMap<String, Operation>()

    /** Records one event against an operation, creating it on first sight. */
    fun record(
        opId: String,
        kind: String,
        stage: String,
        severity: Severity,
        atMs: Long,
        finalState: String? = null,
        detail: String? = null,
    ) {
        synchronized(lock) {
            val existing = ops[opId]
            if (existing != null) {
                existing.lastMs = atMs
                existing.lastStage = stage
                existing.lastSeverity = severity
                existing.events++
                if (finalState != null) existing.finalState = finalState
                if (detail != null) existing.detail = detail
            } else {
                ops[opId] = Operation(
                    opId = opId,
                    kind = kind,
                    firstMs = atMs,
                    lastMs = atMs,
                    lastStage = stage,
                    lastSeverity = severity,
                    events = 1,
                    finalState = finalState,
                    detail = detail,
                )
                while (ops.size > capacity) {
                    val eldest = ops.keys.firstOrNull() ?: break
                    ops.remove(eldest)
                }
            }
        }
    }

    /** Newest op first. */
    fun snapshot(): List<Operation> = synchronized(lock) { ops.values.reversed().toList() }

    fun find(opId: String): Operation? = synchronized(lock) { ops[opId] }

    fun clear() = synchronized(lock) { ops.clear() }

    companion object {
        const val DEFAULT_CAPACITY = 24
        const val KIND_IMAGE_IMPORT = "image-import"
        const val KIND_EXPORT = "export"

        /** New correlation id: `<prefix>-<8 hex>`. */
        fun newOpId(prefix: String, seed: Long = System.nanoTime()): String =
            "$prefix-" + Integer.toHexString(seed.hashCode()).padStart(8, '0')
    }
}

/**
 * Pure lifecycle tracker for one export operation — used by [ExportManager]
 * instrumentation so the stage model and the "did we reach the failure
 * stage" classification are unit-testable without Media3/Android.
 */
class ExportOp(
    val opId: String,
    private val registry: OpRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Ordered lifecycle stages of the export pipeline. */
    object Stages {
        const val REQUEST = "REQUEST"
        const val VALIDATE = "VALIDATE"
        const val SOURCE_MEDIA = "SOURCE_MEDIA"
        const val PLAN = "PLAN"
        const val PREFLIGHT = "PREFLIGHT"
        const val SERVICE_START = "SERVICE_START"
        const val TRANSFORMER_START = "TRANSFORMER_START"
        const val PROGRESS = "PROGRESS"
        const val COMPLETED = "COMPLETED"
        const val TRANSFORMER_ERROR = "TRANSFORMER_ERROR"
        const val CANCELLED = "CANCELLED"
        const val FINALIZE = "FINALIZE"
        const val DONE = "DONE"
        const val FAILED = "FAILED"
    }

    var stage: String = Stages.REQUEST
        private set
    var failureCategory: String? = null
        private set
    var reachedTransformer: Boolean = false
        private set
    private val lock = Any()

    fun transition(newStage: String, severity: Severity = Severity.INFO, detail: String? = null) {
        synchronized(lock) {
            stage = newStage
            if (newStage == Stages.TRANSFORMER_START) reachedTransformer = true
            registry.record(
                opId = opId,
                kind = OpRegistry.KIND_EXPORT,
                stage = newStage,
                severity = severity,
                atMs = clock(),
                finalState = when (newStage) {
                    Stages.DONE -> "success"
                    Stages.FAILED -> "failure"
                    Stages.CANCELLED -> "cancelled"
                    else -> null
                },
                detail = detail,
            )
        }
    }

    fun fail(category: String, detail: String?) {
        synchronized(lock) {
            if (failureCategory != null) return // first failure wins
            failureCategory = category
            val failedAt = stage
            stage = Stages.FAILED
            registry.record(
                opId = opId,
                kind = OpRegistry.KIND_EXPORT,
                stage = "FAILED@$failedAt",
                severity = Severity.ERROR,
                atMs = clock(),
                finalState = "failure",
                detail = detail?.let { "cat=$it" },
            )
        }
    }

    /** One-line summary for the clipboard report. */
    fun summaryLine(): String {
        val op = registry.find(opId)
        val state = failureCategory?.let { "FAILED/$it" } ?: when (stage) {
            Stages.DONE -> "success"
            else -> stage
        }
        val events = op?.events ?: 0
        val last = op?.lastStage ?: stage
        return "op=$opId kind=export state=$state stage=$last events=$events"
    }
}

/**
 * Ordered stage list for one image-import operation (spec section 3 A–L).
 * Pure — used by ViewModel instrumentation and unit tests.
 */
object ImageImportStages {
    const val PICKER_RESULT = "PICKER_RESULT"          // A
    const val URI_META = "URI_META"                    // B/E
    const val PERMISSION = "PERMISSION"                // C
    const val ACCESS = "ACCESS"                        // D
    const val BOUNDS = "BOUNDS"                        // E
    const val DECODE_START = "DECODE_START"            // F
    const val DECODE_END = "DECODE_END"                // F
    const val DECODE_FAIL = "DECODE_FAIL"              // G
    const val OVERLAY_CREATE = "OVERLAY_CREATE"        // H
    const val PREVIEW_REBUILD = "PREVIEW_REBUILD"      // I
    const val EFFECT_ATTACH = "EFFECT_ATTACH"          // J
    const val ACTIVATION = "ACTIVATION"                // K
    const val RENDER_FAIL = "RENDER_FAIL"              // L
}

package com.onevideog.editor.diag

/**
 * Thread-local correlation context for instrumentation sites that are too
 * deep in the pipeline to thread an opId through their signatures
 * (e.g. [com.onevideog.editor.editor.ClipEffectsFactory] -> bitmap decode).
 *
 * The caller that owns the operation wraps the deep call:
 * ```
 * val previous = DiagContext.setOpId(opId)
 * try { deepCall() } finally { DiagContext.setOpId(previous) }
 * ```
 * or uses [withOp]. Pure JVM, no Android imports.
 */
object DiagContext {

    private val opId = ThreadLocal<String?>()

    /** Sets the current op id; returns the previous value (for restore). */
    fun setOpId(id: String?): String? {
        val previous = opId.get()
        opId.set(id)
        return previous
    }

    fun currentOpId(): String? = opId.get()

    /** Runs [block] with [id] as the current op id, restoring afterwards. */
    inline fun <T> withOp(id: String?, block: () -> T): T {
        val previous = setOpId(id)
        return try {
            block()
        } finally {
            setOpId(previous)
        }
    }
}

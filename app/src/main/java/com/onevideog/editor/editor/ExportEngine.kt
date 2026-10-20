package com.onevideog.editor.editor

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Transformer engine plumbing (v0.2.4) — the thread-safety and concurrency
 * core extracted from [ExportManager] so it is pure and JVM-testable.
 *
 * Media3 1.4.1 contract (verified against Transformer.java sources):
 *  - `Transformer.Builder(Context)` captures `Util.getCurrentOrMainLooper()`
 *    at build time; on a thread without a Looper (any Dispatchers.Default /
 *    IO worker) this resolves to the MAIN looper;
 *  - `verifyApplicationThread()` then throws
 *    `IllegalStateException("Transformer is accessed on the wrong thread.")`
 *    for every `start()` / `getProgress()` / `cancel()` that is not on that
 *    captured thread.
 *
 * Device evidence (realme RMX3624 report, 20/10/2026): the export coroutine
 * ran on Dispatchers.Default, so `start` blew up at ExportManager line 498
 * exactly as this contract predicts. Every engine access is therefore routed
 * through ONE application thread ([MainTransformerThread]) instead of
 * relying on the caller's dispatcher.
 */

/** Which single thread Media3 Transformer operations must run on. */
interface TransformerThread {

    /** Runs [block] on the transformer application thread, suspending the caller. */
    suspend fun <T> run(block: () -> T): T

    /**
     * Fire-and-forget execution on the transformer application thread.
     * Must never block the caller (used by cancel paths from any thread).
     */
    fun post(block: () -> Unit)

    /** Human-readable identity for diagnostics events. */
    val description: String
}

/** Production thread: the Android main (application) looper. */
class MainTransformerThread : TransformerThread {

    private val handler = Handler(Looper.getMainLooper())

    override suspend fun <T> run(block: () -> T): T =
        withContext(Dispatchers.Main.immediate) { block() }

    override fun post(block: () -> Unit) {
        handler.post(block)
    }

    override val description: String = "main"
}

/**
 * Builds and starts the export engine on ONE application thread.
 *
 * Both [factory] (Transformer build) and [starter] (Transformer.start) run
 * inside [thread].run — this is what guarantees the Media3 1.4.1 contract:
 * the looper captured at build time and the thread that calls start() are
 * the same. The ORIGINAL synchronous failure (e.g. IllegalStateException from
 * a contract violation, or a codec init error) propagates unchanged — it is
 * never wrapped and never swallowed; the caller records it.
 */
object EngineLauncher {

    suspend fun <T> launchOn(
        thread: TransformerThread,
        factory: () -> T,
        starter: (T) -> Unit,
    ): T {
        val engine = thread.run(factory)
        thread.run { starter(engine) }
        return engine
    }
}

/**
 * Atomic single-export gate. Prevents accidental concurrent exports:
 * `tryBegin` succeeds for exactly one owner at a time (CAS), [end] releases
 * it. Replaces the racy `if (job?.isActive == true) return` guard whose check
 * window opened before the job was even assigned (after slow main-thread
 * preflight work), which allowed two near-simultaneous export requests to
 * start two Transformers on the user's device (ops exp-b7bf477d/exp-b96c0125).
 */
class ExportGate {

    private val inFlight = AtomicBoolean(false)

    /**
     * Atomically claims the single export slot. Returns false when an export
     * is already in flight — the caller MUST reject/deduplicate the request.
     */
    fun tryBegin(): Boolean = inFlight.compareAndSet(false, true)

    /** Releases the gate. Idempotent; safe from any thread. */
    fun end() {
        inFlight.set(false)
    }

    val isActive: Boolean
        get() = inFlight.get()
}

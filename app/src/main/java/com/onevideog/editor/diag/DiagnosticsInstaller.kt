package com.onevideog.editor.diag

/**
 * Installs the temporary diagnostics crash capture (v0.2.3).
 *
 * Contract (spec section 5):
 *  - the PREVIOUS default uncaught-exception handler is preserved and always
 *    invoked, so normal Android crash behaviour (dialog, ANR reporting,
 *    tombstones, restart) is unchanged;
 *  - one sanitized crash record is written synchronously and best-effort;
 *  - a recursion/loop guard prevents re-entry if the crash write itself
 *    throws;
 *  - only application-level uncaught exceptions are captured; native
 *    crashes, OS kills (LMK/freezer) and abrupt power loss are NOT captured
 *    (documented limitation).
 */
object DiagnosticsInstaller {

    @Volatile private var installed = false

    fun install() {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Re-entrancy guard: if the crash path itself crashes, do not
            // loop — fall through to the previous handler immediately.
            runCatching { DiagnosticsLogger.writeCrashNow(thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }
}

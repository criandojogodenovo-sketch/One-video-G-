package com.onevideog.editor.diag

/**
 * Builds the clipboard diagnostic report (pure JVM, unit-tested).
 *
 * Contains only sanitized material: app/device identity, diagnostics state,
 * bounded recent log lines (already redacted at write time), operation
 * summaries with correlation ids, the last known export state (including an
 * interrupted-export marker when present), and a short instruction block for
 * the developer. No keystore data, tokens, media contents or unredacted
 * paths are ever passed in by the callers.
 */
object DiagReportBuilder {

    /** Cap on embedded log lines so the clipboard stays manageable. */
    const val MAX_LOG_LINES = 300

    fun build(
        appVersion: String,
        device: String,
        androidVersion: String,
        sdkInt: Int,
        enabled: Boolean,
        eventCount: Long,
        approximateKb: Long,
        rotated: Boolean,
        lastWriteFailed: Boolean,
        opSummaries: List<String>,
        lastExportState: String?,
        interruptedExport: String?,
        logLines: List<String>,
        maxLines: Int = MAX_LOG_LINES,
    ): String {
        val sb = StringBuilder(2048)
        sb.appendLine("=== One Video G — Diagnostic Report ===")
        sb.appendLine("app_version=$appVersion")
        sb.appendLine("device=$device")
        sb.appendLine("android=$androidVersion (SDK $sdkInt)")
        sb.appendLine("diagnostics_enabled=$enabled")
        sb.appendLine("events_recorded=$eventCount")
        sb.appendLine("approx_log_size_kb=$approximateKb")
        if (rotated) {
            sb.appendLine("warning=log rotated; oldest events were truncated (size cap ~2 MB)")
        }
        if (lastWriteFailed) {
            sb.appendLine("warning=one or more log writes FAILED (storage?) — report may be incomplete")
        }
        sb.appendLine()
        sb.appendLine("--- Last known export state ---")
        sb.appendLine(lastExportState ?: "none recorded")
        if (!interruptedExport.isNullOrBlank()) {
            sb.appendLine(interruptedExport)
        }
        sb.appendLine()
        sb.appendLine("--- Recent operations (correlation ids) ---")
        if (opSummaries.isEmpty()) {
            sb.appendLine("none recorded")
        } else {
            opSummaries.forEach { sb.appendLine(it) }
        }
        sb.appendLine()
        sb.appendLine("--- Log tail (max $maxLines lines, sanitized) ---")
        if (logLines.isEmpty()) {
            sb.appendLine("no events recorded — enable Diagnostics in Settings, reproduce the problem, then copy again")
        } else {
            val truncated = logLines.size > maxLines
            val shown = if (truncated) logLines.takeLast(maxLines) else logLines
            shown.forEach { sb.appendLine(it) }
            if (truncated) sb.appendLine("… older lines omitted (report cap $maxLines)")
        }
        sb.appendLine()
        sb.appendLine("--- For the developer ---")
        sb.appendLine("Reproduce the failure with the SAME steps; every import/export above has an op= id")
        sb.appendLine("linking its full event trail. Send this report together with a short description of")
        sb.appendLine("what was done just before the problem (screens/buttons pressed).")
        return sb.toString()
    }
}

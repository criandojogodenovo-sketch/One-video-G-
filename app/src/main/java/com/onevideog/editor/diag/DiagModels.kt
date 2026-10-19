package com.onevideog.editor.diag

/**
 * Severity of a structured diagnostic event (temporary diagnostics system,
 * v0.2.3). Ordered so filters/reports can highlight the highest severity.
 */
enum class Severity { DEBUG, INFO, WARN, ERROR }

/**
 * One structured diagnostic record.
 *
 * Everything is already sanitized before construction ([DiagSanitizer] runs
 * at the instrumentation call sites and inside [EventFormatter.format] for
 * exception chains) so this object is safe to persist and to place on the
 * clipboard.
 *
 * @param epochMs    wall-clock timestamp (device local time, may jump).
 * @param severity   [Severity] of the event.
 * @param subsystem  short stable tag, e.g. "import", "export", "timeline".
 * @param stage      lifecycle stage, e.g. "DECODE", "TRANSFORMER_ERROR".
 * @param opId       correlation id connecting all events of one operation
 *                   (import/export/crash); null for app-level events.
 * @param message    concise, already-sanitized event text (may be null).
 * @param meta       ordered key=value metadata (counts, durations, sizes).
 * @param throwable  optional exception; formatted (class + sanitized message
 *                   + nested causes + sanitized frames) by [EventFormatter].
 */
data class DiagnosticEvent(
    val epochMs: Long,
    val severity: Severity,
    val subsystem: String,
    val stage: String,
    val opId: String? = null,
    val message: String? = null,
    val meta: List<Pair<String, String>> = emptyList(),
    val throwable: Throwable? = null,
)

/**
 * Pure formatter: one event -> one single-line record.
 *
 * Line shape (fields omitted when null/empty):
 * `2026-10-19 18:22:33.512 | INFO | export | TRANSFORMER_START | op=exp-ab12 | k=v k2=v2 | message | exc=...`
 *
 * Pure JVM code (no Android imports) so the exact persisted format is unit
 * tested. Uses SimpleDateFormat (not java.time) because minSdk is 24 and the
 * project does not enable core-library desugaring.
 */
object EventFormatter {

    /** Bound for one formatted line; keeps rotated files predictable. */
    const val MAX_LINE_CHARS = 4000

    /** Max nested causes walked in a throwable chain. */
    const val MAX_CAUSES = 4

    /** Max sanitized stack frames appended after an exception chain. */
    const val MAX_FRAMES = 8

    private const val SEP = " | "

    private val lock = Any()
    private var sharedFormat: java.text.SimpleDateFormat? = null

    /** ISO-ish local timestamp used in every persisted line. */
    fun formatTimestamp(epochMs: Long): String {
        val fmt = synchronized(lock) {
            sharedFormat ?: java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                java.util.Locale.US,
            ).also { sharedFormat = it }
        }
        return synchronized(fmt) { fmt.format(java.util.Date(epochMs)) }
    }

    fun format(event: DiagnosticEvent): String {
        val sb = StringBuilder(160)
        sb.append(formatTimestamp(event.epochMs))
        sb.append(SEP).append(event.severity.name)
        sb.append(SEP).append(sanitizeField(event.subsystem))
        sb.append(SEP).append(sanitizeField(event.stage))
        event.opId?.takeIf { it.isNotBlank() }?.let { sb.append(SEP).append("op=").append(sanitizeField(it)) }
        for ((k, v) in event.meta) {
            sb.append(SEP).append(sanitizeField(k)).append('=').append(sanitizeValue(v))
        }
        event.message?.takeIf { it.isNotBlank() }?.let {
            // Redact (URIs/paths/tokens/length) AND flatten field-breaking
            // characters so the one-line record stays parseable.
            sb.append(SEP).append(sanitizeField(DiagSanitizer.redact(it)))
        }
        event.throwable?.let { sb.append(SEP).append(sanitizeField(formatThrowable(it))) }
        return truncateLine(sb.toString())
    }

    /**
     * Flattens a throwable chain into `Class(msg) <- caused by Class(msg) ...`
     * plus up to [MAX_FRAMES] sanitized stack frames. Never throws.
     */
    fun formatThrowable(t: Throwable): String = runCatching {
        val sb = StringBuilder(128)
        var current: Throwable? = t
        var depth = 0
        while (current != null && depth <= MAX_CAUSES) {
            if (depth > 0) sb.append(" <- caused by ")
            sb.append("exc=").append(current.javaClass.name)
            current.message?.takeIf { it.isNotBlank() }?.let {
                sb.append('(').append(DiagSanitizer.redact(it)).append(')')
            }
            current = current.cause?.takeIf { it !== current }
            depth++
        }
        val frames = t.stackTrace
            .take(MAX_FRAMES)
            .map { DiagSanitizer.sanitizeFrame(it) }
            .filter { it.isNotBlank() }
        if (frames.isNotEmpty()) sb.append(" frames=").append(frames.joinToString(";"))
        sb.toString()
    }.getOrDefault("exc=<format-failure:${t.javaClass.simpleName}>")

    /** Newlines/pipe characters would corrupt the one-line format. */
    private fun sanitizeField(raw: String): String =
        raw.replace('\n', ' ').replace('\r', ' ').replace('|', '/').trim()

    private fun sanitizeValue(raw: String): String =
        DiagSanitizer.redact(sanitizeField(raw))

    private fun truncateLine(line: String): String =
        if (line.length <= MAX_LINE_CHARS) line else line.take(MAX_LINE_CHARS) + "…<truncated>"
}

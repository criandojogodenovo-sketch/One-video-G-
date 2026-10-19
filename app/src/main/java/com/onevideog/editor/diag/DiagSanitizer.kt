package com.onevideog.editor.diag

/**
 * Privacy sanitization for the temporary diagnostics system.
 *
 * Everything that reaches persistent log files or the clipboard passes
 * through [redact] (free text) / [sanitizeUri] (URIs) / [sanitizeFrame]
 * (stack frames). Design rules:
 *  - never log image/video/audio bytes or rendered overlay text;
 *  - never log full content URIs (they can carry document ids / filenames) —
 *    a stable fingerprint of the URI is logged instead;
 *  - never log filesystem paths of user media — only the basename shape
 *    (extension + length) or a placeholder;
 *  - collapse long opaque runs (tokens, base64, document ids).
 */
object DiagSanitizer {

    /** Absolute cap applied to any single sanitized text field. */
    const val MAX_TEXT_CHARS = 240

    private val URI_PATTERN = Regex("(content|file|android\\.resource)://[^\\s,;)\"]+")
    private val PATH_PATTERN = Regex("/(?:[A-Za-z0-9._-]+/){1,}[A-Za-z0-9._-]+")
    private val OPAQUE_RUN = Regex("[A-Za-z0-9+/=_-]{48,}")

    /**
     * Redacts free text: URIs -> `<uri:fp>`, absolute paths -> `<path>`,
     * opaque token-like runs -> `<tok>`, newlines -> spaces, bounded length.
     * Never throws; returns a non-null string for any input.
     */
    fun redact(raw: String?, maxChars: Int = MAX_TEXT_CHARS): String {
        if (raw.isNullOrBlank()) return ""
        var text = raw
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
        text = URI_PATTERN.replace(text) { m -> "<uri:${fingerprint(m.value.take(128))}>" }
        text = PATH_PATTERN.replace(text) { m -> "<path:${fingerprint(m.value)}>" }
        text = OPAQUE_RUN.replace(text) { "<tok>" }
        return if (text.length <= maxChars) text else text.take(maxChars) + "…"
    }

    /**
     * A stable, non-reversible, short representation of a URI: scheme +
     * host (provider authority — needed to distinguish SAF providers) + a
     * 8-hex-char fingerprint of the full uri. The path/query (document id,
     * display name) is never included.
     */
    fun sanitizeUri(uri: String?): String {
        if (uri.isNullOrBlank()) return "<null>"
        return runCatching {
            val scheme = uri.substringBefore(":", "").ifBlank { "?" }
            val afterScheme = uri.substringAfter("://", "")
            val host = afterScheme.substringBefore('/').take(40)
            "uri:$scheme://${host.ifBlank { "?" }}#${fingerprint(uri)}"
        }.getOrDefault("uri:<opaque>")
    }

    /**
     * File-path-safe variant used when the code already has a File: logs
     * name + size category only, never directory structure.
     */
    fun sanitizeFileName(name: String?): String {
        if (name.isNullOrBlank()) return "<null>"
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val ext = base.substringAfterLast('.', "")
        return "name#${fingerprint(base)}" + (if (ext.isNotBlank()) ".$ext" else "")
    }

    /**
     * Stack frame -> `Class.method(Class.kt:12)`; frames outside the app
     * package AND framework classes are kept (they identify Media3/Android
     * internals) but any path-looking element inside the file column is
     * dropped. Never throws.
     */
    fun sanitizeFrame(frame: StackTraceElement?): String {
        if (frame == null) return ""
        return runCatching {
            val file = frame.fileName?.let { it.substringAfterLast('/') } ?: "?"
            val line = if (frame.lineNumber >= 0) ":${frame.lineNumber}" else ""
            "${frame.className}.${frame.methodName}($file$line)"
        }.getOrDefault("<frame>")
    }

    /**
     * Short non-reversible fingerprint (first 8 hex chars of a string hash)
     * used to correlate identical URIs/paths across events without exposing
     * them. Java's String.hashCode is stable and sufficient for correlation
     * here (collision risk acceptable for a temporary diagnostic system).
     */
    fun fingerprint(value: String): String =
        Integer.toHexString(value.hashCode()).padStart(8, '0')
}

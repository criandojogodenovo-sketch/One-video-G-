package com.onevideog.editor.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.SystemClock
import android.util.LruCache
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.TextFontFamily
import com.onevideog.editor.diag.DiagContext
import com.onevideog.editor.diag.DiagSanitizer
import com.onevideog.editor.diag.DiagnosticsLogger
import com.onevideog.editor.diag.ImageImportStages

/**
 * Renders overlay content bitmaps (text blocks, images) used identically by
 * the preview canvas and the export composition — same bitmap, same transform
 * math, so what the user sees while editing is what gets exported.
 *
 * Text is rendered at a canonical resolution (height [CANONICAL_H]) and
 * wrapped at 90% of the canonical width; the overlay's transform.scale then
 * maps the bitmap onto the content area. Placement/rotation/opacity are
 * applied downstream ([OverlayLayout] / Media3 OverlaySettings), never baked
 * into the bitmap, so keyframes stay cheap on both paths.
 */
object OverlayBitmapRenderer {

    /** Canonical rendering height (content short side) for text bitmaps. */
    const val CANONICAL_H = 1080

    /**
     * Canonical rendering width. Text bitmaps always use this fixed 16:9
     * canonical frame in BOTH preview and export so the wrapped text bitmap
     * is bit-identical regardless of project aspect.
     */
    const val CANONICAL_W = 1920

    /** Text wraps at this fraction of the canonical width. */
    const val WRAP_FRAC = 0.9f

    // ---- v0.5.0 shared text-layout constants (single source for the block
    // renderer AND TextSegmentPlanner — one implementation, parity by
    // construction) ----

    /** Line spacing as a fraction of font size (renderTextBlock contract). */
    const val LINE_SPACING_FACTOR = 0.22f

    /** First-baseline offset as a fraction of font size. */
    const val BASELINE_FACTOR = 0.82f

    /** Inner wrap fraction applied to the block width (renderTextBlock). */
    const val WRAP_INNER_FRACTION = 0.98f

    private const val IMAGE_MAX_DIM = 1024

    /**
     * Bounded in-memory cache (approx 20 MB) for overlay bitmaps. Keyed by a
     * stable content hash so edits re-render automatically.
     */
    val cache = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Stable cache/render key for an overlay's content. */
    fun contentKey(overlay: OverlayItem): String = when (overlay.kind) {
        com.onevideog.editor.data.OverlayKind.TEXT -> buildString {
            append("t|")
            // v0.5.1: the FULL text instead of text.hashCode() — a 32-bit
            // hash let two DIFFERENT texts (same styling) collide and reuse
            // the wrong bitmap. Key strings are bounded by the LruCache.
            append(overlay.text).append('|')
            append(overlay.colorArgb).append('|')
            append(overlay.fontFamily).append('|')
            append(overlay.bold).append(overlay.italic).append('|')
            append(overlay.sizeFrac).append('|')
            append(overlay.bgArgb).append('|')
            append(overlay.bgPaddingFrac).append('|')
            append(overlay.bgRadiusFrac)
        }
        com.onevideog.editor.data.OverlayKind.IMAGE -> "i|${overlay.uri}|${overlay.id}"
    }

    /**
     * Pure per-overlay text layout metrics (v0.5.0) — the ONE source for the
     * block renderer ([renderTextBlock] via [textBitmap]) and for
     * [com.onevideog.editor.editor.TextSegmentPlanner], so segment assembly
     * and block rendering cannot drift. No Android classes — JVM-testable.
     */
    data class TextMetrics(
        val fontPx: Float,
        val padX: Float,
        val padY: Float,
        val lineSpacing: Float,
        val wrapMaxWidth: Float,
        val hasBg: Boolean,
    )

    /** Layout metrics for a text overlay (see [TextMetrics]). */
    fun textMetrics(overlay: OverlayItem): TextMetrics {
        val fontPx = CANONICAL_H * overlay.sizeFrac.coerceIn(0.02f, 0.3f)
        val hasBg = (overlay.bgArgb ushr 24) != 0
        val bgPadPx = CANONICAL_H * overlay.bgPaddingFrac.coerceIn(0f, 0.1f)
        return TextMetrics(
            fontPx = fontPx,
            padX = if (hasBg) bgPadPx * 0.9f else 2f,
            padY = if (hasBg) bgPadPx else 2f,
            lineSpacing = fontPx * LINE_SPACING_FACTOR,
            wrapMaxWidth = (CANONICAL_W * WRAP_FRAC).toInt() * WRAP_INNER_FRACTION,
            hasBg = hasBg,
        )
    }

    /** Typeface for a [TextFontFamily] id. */
    fun typefaceFor(familyId: String, bold: Boolean, italic: Boolean): Typeface {
        val base = when (TextFontFamily.fromId(familyId)) {
            TextFontFamily.SANS -> Typeface.DEFAULT
            TextFontFamily.SERIF -> Typeface.SERIF
            TextFontFamily.MONO -> Typeface.MONOSPACE
            TextFontFamily.CONDENSED -> runCatching {
                Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            }.getOrDefault(Typeface.DEFAULT)
        }
        val style = (if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0)
        return if (style == 0) base else Typeface.create(base, style)
    }

    /**
     * Renders the text overlay block at canonical resolution. The result is
     * cached; the render is content-only (aspect-independent).
     */
    fun textBitmap(overlay: OverlayItem): Bitmap {
        val key = contentKey(overlay)
        cache.get(key)?.let { return it }
        val bitmap = renderTextBlock(
            width = (CANONICAL_W * WRAP_FRAC).toInt(),
            text = overlay.text,
            colorArgb = overlay.colorArgb,
            fontPx = (CANONICAL_H * overlay.sizeFrac.coerceIn(0.02f, 0.3f)),
            familyId = overlay.fontFamily,
            bold = overlay.bold,
            italic = overlay.italic,
            bgArgb = overlay.bgArgb,
            bgPadPx = (CANONICAL_H * overlay.bgPaddingFrac.coerceIn(0f, 0.1f)),
            bgRadiusPx = (CANONICAL_H * overlay.bgRadiusFrac.coerceIn(0f, 0.2f)),
        )
        cache.put(key, bitmap)
        return bitmap
    }

    /** Renders an image overlay bitmap (bounded decode), cached per URI. */
    fun imageBitmap(context: Context, overlay: OverlayItem): Bitmap? {
        val key = contentKey(overlay)
        cache.get(key)?.let { return it }
        // Correlate the decode with the owning operation: the thread-local
        // op id set by PreviewStage/ExportManager, or the overlay id as a
        // fallback correlation anchor.
        val decoded = decodeImage(
            context,
            Uri.parse(overlay.uri),
            opId = DiagContext.currentOpId() ?: "ov-${DiagSanitizer.fingerprint(overlay.id)}",
        ) ?: return null
        cache.put(key, decoded)
        return decoded
    }

    /**
     * Cache-only lookup for image overlays (v0.2.5): returns the cached
     * bitmap or null WITHOUT decoding. Used by the editor canvas
     * ([OverlayCanvas]) so the render/gesture paths can never trigger a
     * decode on the main/render thread, and a failed decode cannot be
     * retried once per frame (the v0.2.4 report's main-thread decode +
     * "image never appears" symptom). Populating the cache is the
     * pre-warm path's job (Dispatchers.Default) — or the effects-pipeline
     * build, which shares this cache.
     */
    fun peekImageBitmap(overlay: OverlayItem): Bitmap? =
        if (overlay.kind == com.onevideog.editor.data.OverlayKind.IMAGE) {
            cache.get(contentKey(overlay))
        } else {
            null
        }

    /** Aspect (w/h) of the rendered content bitmap. */
    fun bitmapAspect(bitmap: Bitmap): Float =
        if (bitmap.height <= 0) 1f else bitmap.width.toFloat() / bitmap.height

    /** Evicts a single overlay's bitmap (after content edits that change size). */
    fun evict(overlay: OverlayItem) {
        cache.remove(contentKey(overlay))
    }

    /**
     * Decodes an image URI sampled to at most [IMAGE_MAX_DIM] on its longest
     * side. v0.2.3: every stage/failure is instrumented (bounds, sample
     * factor, elapsed, exception incl. OOM) — failures were previously
     * silently converted to null, which made image overlays disappear
     * without any evidence. The opId comes from the caller (thread-local
     * operation context) so decode events correlate with the import or the
     * preview/export rebuild that triggered them.
     */
    fun decodeImage(context: Context, uri: Uri, opId: String? = DiagContext.currentOpId()): Bitmap? {
        val startedAt = SystemClock.elapsedRealtime()
        // ---- stage F: bounds pass (no pixels) ----
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        var boundsStreamOpened = false
        runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                // NOTE: with inJustDecodeBounds=true decodeStream returns
                // null by design; success == the stream opened. The real
                // validity check is outWidth/outHeight below.
                boundsStreamOpened = true
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }
        if (!boundsStreamOpened) {
            DiagnosticsLogger.e(
                "import", ImageImportStages.DECODE_FAIL, opId,
                "image bounds pass failed (stream unreadable or decode error)",
                meta = listOf("uri" to DiagSanitizer.sanitizeUri(uri.toString())),
            )
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            DiagnosticsLogger.e(
                "import", ImageImportStages.DECODE_FAIL, opId,
                "image bounds invalid (not a decodable image)",
                meta = listOf(
                    "uri" to DiagSanitizer.sanitizeUri(uri.toString()),
                    "w" to bounds.outWidth.toString(),
                    "h" to bounds.outHeight.toString(),
                ),
            )
            return null
        }
        var sample = 1
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        while (largest / (sample * 2) >= IMAGE_MAX_DIM) sample *= 2
        DiagnosticsLogger.i(
            "import", ImageImportStages.DECODE_START, opId,
            "image decode started",
            meta = listOf(
                "uri" to DiagSanitizer.sanitizeUri(uri.toString()),
                "srcW" to bounds.outWidth.toString(),
                "srcH" to bounds.outHeight.toString(),
                "sample" to sample.toString(),
            ),
        )
        // ---- stage F: full decode ----
        val decoded = runCatching {
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        return when {
            decoded.isFailure -> {
                val t = decoded.exceptionOrNull()
                DiagnosticsLogger.e(
                    "import", ImageImportStages.DECODE_FAIL, opId,
                    "image decode threw (oom=${t is OutOfMemoryError})",
                    t,
                    listOf(
                        "uri" to DiagSanitizer.sanitizeUri(uri.toString()),
                        "elapsedMs" to elapsedMs.toString(),
                    ),
                )
                null
            }
            decoded.getOrNull() == null -> {
                DiagnosticsLogger.e(
                    "import", ImageImportStages.DECODE_FAIL, opId,
                    "image decode returned NULL bitmap",
                    meta = listOf(
                        "uri" to DiagSanitizer.sanitizeUri(uri.toString()),
                        "elapsedMs" to elapsedMs.toString(),
                    ),
                )
                null
            }
            else -> {
                val bmp = decoded.getOrNull()!!
                DiagnosticsLogger.i(
                    "import", ImageImportStages.DECODE_END, opId,
                    "image decode succeeded",
                    meta = listOf(
                        "w" to bmp.width.toString(),
                        "h" to bmp.height.toString(),
                        "elapsedMs" to elapsedMs.toString(),
                        "thread" to Thread.currentThread().name,
                    ),
                )
                bmp
            }
        }
    }

    /**
     * Draws the text block (with optional background pill) on a transparent
     * bitmap, word-wrapped at [width]. Shared by preview and export paths.
     *
     * v0.5.0: line layout comes from the shared pure helpers
     * ([wrapTextLines], [blockSizePx], [TextMetrics]) so the segment planner
     * reproduces this bitmap's geometry exactly — one implementation.
     */
    fun renderTextBlock(
        width: Int,
        text: String,
        colorArgb: Int,
        fontPx: Float,
        familyId: String,
        bold: Boolean,
        italic: Boolean,
        bgArgb: Int,
        bgPadPx: Float,
        bgRadiusPx: Float,
    ): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorArgb
            textSize = fontPx
            typeface = typefaceFor(familyId, bold, italic)
            textAlign = Paint.Align.CENTER
        }
        val lines = wrapTextLines(text, width * WRAP_INNER_FRACTION) { paint.measureText(it) }
        val metrics = TextMetrics(
            fontPx = fontPx,
            padX = if ((bgArgb ushr 24) != 0) bgPadPx * 0.9f else 2f,
            padY = if ((bgArgb ushr 24) != 0) bgPadPx else 2f,
            lineSpacing = fontPx * LINE_SPACING_FACTOR,
            wrapMaxWidth = width * WRAP_INNER_FRACTION,
            hasBg = (bgArgb ushr 24) != 0,
        )
        val (outW, outH) = blockSizePx(lines, metrics) { paint.measureText(it) }

        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (metrics.hasBg) {
            val rect = RectF(0f, 0f, outW.toFloat(), outH.toFloat())
            val path = Path()
            path.addRoundRect(rect, bgRadiusPx, bgRadiusPx, Path.Direction.CW)
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgArgb }
            canvas.drawPath(path, bgPaint)
        }
        val shadow = Paint(paint).apply {
            setShadowLayer(fontPx * 0.08f, 0f, fontPx * 0.04f, 0x88000000.toInt())
        }
        var y = metrics.padY + fontPx * BASELINE_FACTOR
        for (line in lines) {
            canvas.drawText(line, outW / 2f, y, shadow)
            y += fontPx + metrics.lineSpacing
        }
        return bitmap
    }

    /**
     * Greedy word wrap (pure — measurer injected). Used for BOTH preview and
     * export text rendering and by the segment planner, so the wrapped line
     * assignment is always the same for the same text + metrics.
     */
    fun wrapTextLines(
        text: String,
        maxWidth: Float,
        measure: (String) -> Float,
    ): List<String> {
        val result = mutableListOf<String>()
        for (paragraph in text.split("\n")) {
            var line = ""
            for (word in paragraph.split(" ")) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (measure(candidate) <= maxWidth || line.isEmpty()) {
                    line = candidate
                } else {
                    result += line
                    line = word
                }
            }
            result += line
        }
        return result.filter { it.isNotEmpty() }.ifEmpty { listOf("") }
    }

    /** Android-paint convenience overload (delegates to the pure core). */
    fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> =
        wrapTextLines(text, maxWidth) { paint.measureText(it) }

    /**
     * Exact block bitmap size for [lines] laid out with [metrics] — the same
     * arithmetic [renderTextBlock] uses to size its output, exposed so the
     * segment planner normalizes segment positions against the SAME block
     * frame. Pure; returns (width, height) in canonical px (int-rounded like
     * Bitmap.createBitmap).
     */
    fun blockSizePx(
        lines: List<String>,
        metrics: TextMetrics,
        measure: (String) -> Float,
    ): Pair<Int, Int> {
        var blockW = 0f
        for (line in lines) blockW = maxOf(blockW, measure(line))
        val lineCount = lines.size.coerceAtLeast(1)
        val textBlockH = lineCount * metrics.fontPx + (lineCount - 1) * metrics.lineSpacing
        val outW = (blockW + metrics.padX * 2).toInt().coerceAtLeast(4)
        val outH = (textBlockH + metrics.padY * 2).toInt().coerceAtLeast(4)
        return outW to outH
    }
}

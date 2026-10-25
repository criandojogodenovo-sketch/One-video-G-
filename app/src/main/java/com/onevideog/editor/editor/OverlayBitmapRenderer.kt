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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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
     *
     * v0.9.0 (L4): styled (baked) bitmaps share THIS cache — the bake-to-
     * texture spine requires preview and export to consume the same instance
     * — but their dedicated budget ([MAX_STYLED_BYTES]) stops three heavy
     * bakes from evicting every plain content bitmap: entryRemoved tracks
     * global evictions and keeps the styled registry coherent.
     */
    val cache = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted) {
                globalEvictions++
                synchronized(styledLock) {
                    if (styledLru.remove(key) != null) bakedInfo.remove(key)
                }
            }
        }
    }

    /**
     * v0.9.0 (L4): dedicated ceiling for STYLED (baked) bitmaps inside the
     * shared cache. Insertions beyond it evict the least-recently-used styled
     * entries first (OVERLAY_BAKE event carries the evictions count).
     */
    const val MAX_STYLED_BYTES = 12 * 1024 * 1024

    private val styledLock = Any()
    /** styled contentKey -> bitmap bytes, access-order (LRU). */
    private val styledLru = LinkedHashMap<String, Int>(32, 0.75f, true)
    /** styled contentKey -> content/pad geometry for the shared draw math. */
    private val bakedInfo = ConcurrentHashMap<String, BakedBitmapInfo>()

    @Volatile
    var globalEvictions: Int = 0
        private set

    /**
     * Geometry of a baked bitmap: the ORIGINAL content size and the
     * symmetric pad the bake added. Both pipelines derive every drawn size
     * from these numbers (the content box stays the placement box; the baked
     * bitmap expands symmetrically around the SAME centre).
     */
    class BakedBitmapInfo(
        val contentWidth: Int,
        val contentHeight: Int,
        val padPx: Int,
    ) {
        val bakedWidth: Int get() = contentWidth + 2 * padPx
        val bakedHeight: Int get() = contentHeight + 2 * padPx
    }

    /**
     * D1 — THE renderer split (v0.9.0): ONE pure function decides which path
     * renders an overlay in BOTH pipelines. Overlays WITH a style render
     * through the baked bitmap (preview `Image` + export `BitmapOverlay` —
     * the SAME cache instance, exact parity by construction); text WITHOUT a
     * style keeps the Compose Text path (documented cosmetic delta), images
     * without a style keep the plain decode.
     */
    fun rendersViaBitmap(overlay: OverlayItem): Boolean = overlay.hasStyle

    /** Geometry for a styled overlay (null when plain or not baked yet). */
    fun bakedInfoFor(overlay: OverlayItem): BakedBitmapInfo? = bakedInfo[contentKey(overlay)]

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
            // v0.9.0 (L1): the style is CONTENT — a style edit must bake a
            // new bitmap (cache invalidation), not reuse the plain one.
            append('|').append(overlay.style)
        }
        com.onevideog.editor.data.OverlayKind.IMAGE ->
            "i|${overlay.uri}|${overlay.id}|${overlay.style}"
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
     * v0.5.9: THE ONE text measurer — the Paint every text-width measurement
     * in the app goes through (user prescription "medir a largura do texto
     * com as mesmas métricas de fonte nos dois"): the overlay's typeface at
     * the canonical [textMetrics] font size, the exact paint the block
     * renderer draws with. Consumed by BOTH pipelines:
     *  - the segment planner / animation spec ([ExpandedOverlays]);
     *  - the preview's canonical text layout
     *    ([com.onevideog.editor.ui.editor.OverlayTextRender] via the Compose
     *    layer's resolveRender).
     * One construction site means preview and export cannot drift in font
     * metrics (a second Paint with different flags/size is now impossible).
     */
    fun textMeasurer(overlay: OverlayItem): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textMetrics(overlay).fontPx
            typeface = typefaceFor(overlay.fontFamily, overlay.bold, overlay.italic)
        }
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

    // ------------------------------------------------------- v0.9.0 (Fase L)
    // bake-to-texture builders: ONE baked bitmap per (content, style), in the
    // SAME cache, consumed by BOTH pipelines (D1). The bake runs through the
    // pure OverlayStyleBake (D3) — no android.graphics participates in the
    // style math itself, only in the pixel-buffer exchange.

    /**
     * The styled bitmap for ANY overlay kind (text or image): renders the
     * plain content WITHOUT native shadows, bakes shadow/glow under it and
     * caches by the styled contentKey. Cache hits are O(1); a hit/miss is
     * logged on every call (throttled per key on the hot path) with the
     * L4 OVERLAY_BAKE evidence. Callers MUST treat this as cold-path work —
     * the preview pre-warms on Dispatchers.Default, the export builds on its
     * worker thread.
     */
    fun styledBitmap(context: Context?, overlay: OverlayItem): Bitmap? {
        val key = contentKey(overlay)
        cache.get(key)?.let {
            logBake(overlay, it, 0L, hit = true, evictions = 0)
            return it
        }
        val startedAt = SystemClock.elapsedRealtime()
        val base = when (overlay.kind) {
            com.onevideog.editor.data.OverlayKind.TEXT -> renderTextBlock(
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
                // The user style REPLACES the native paint shadow (baking
                // both would double the shadows — the plain path keeps its
                // established look untouched).
                nativeShadow = false,
            )
            com.onevideog.editor.data.OverlayKind.IMAGE -> {
                val ctx = context ?: return null
                decodeImage(ctx, Uri.parse(overlay.uri)) ?: return null
            }
        }
        val style = overlay.style ?: return base
        val src = IntArray(base.width * base.height)
        base.getPixels(src, 0, base.width, 0, 0, base.width, base.height)
        val baked = OverlayStyleBake.bake(src, base.width, base.height, style, base.height.coerceAtLeast(1))
        val bitmap = Bitmap.createBitmap(baked.width, baked.height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(baked.pixels, 0, baked.width, 0, 0, baked.width, baked.height)
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        val evictions = enforceStyledBudget(bitmap.byteCount)
        cache.put(key, bitmap)
        bakedInfo[key] = BakedBitmapInfo(base.width, base.height, baked.padPx)
        synchronized(styledLock) { styledLru[key] = bitmap.byteCount }
        logBake(overlay, bitmap, elapsedMs, hit = false, evictions = evictions)
        return bitmap
    }

    /**
     * L4: keeps the styled budget inside the shared cache — when a new bake
     * would push the styled footprint past [MAX_STYLED_BYTES], the least
     * recently used STYLED entries are evicted first (the plain content
     * bitmaps are never touched by this pass). Returns the eviction count.
     */
    private fun enforceStyledBudget(incomingBytes: Int): Int {
        var evictions = 0
        synchronized(styledLock) {
            var total = styledLru.values.sum() + incomingBytes
            if (total <= MAX_STYLED_BYTES) return 0
            val iterator = styledLru.entries.iterator()
            while (total > MAX_STYLED_BYTES && iterator.hasNext()) {
                val entry = iterator.next()
                total -= entry.value
                cache.remove(entry.key)
                bakedInfo.remove(entry.key)
                iterator.remove()
                evictions++
            }
        }
        return evictions
    }

    /** L4 evidence — one OVERLAY_BAKE event per render attempt (hit or miss). */
    private fun logBake(overlay: OverlayItem, bitmap: Bitmap, elapsedMs: Long, hit: Boolean, evictions: Int) {
        DiagnosticsLogger.d(
            "preview", "OVERLAY_BAKE", DiagContext.currentOpId(),
            if (hit) "styled bitmap cache hit" else "styled bitmap baked",
            meta = listOf(
                "ovId" to DiagSanitizer.fingerprint(overlay.id),
                "kind" to overlay.kind.name,
                "px" to "${bitmap.width}x${bitmap.height}",
                "bytes" to bitmap.byteCount.toString(),
                "elapsedMs" to elapsedMs.toString(),
                "cacheHit" to hit.toString(),
                "evictions" to evictions.toString(),
                "globalEvictions" to globalEvictions.toString(),
                "thread" to Thread.currentThread().name,
            ),
        )
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
        val key = contentKey(overlay)
        cache.remove(key)
        synchronized(styledLock) { styledLru.remove(key) }
        bakedInfo.remove(key)
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
     *
     * v0.9.0 (L1): [nativeShadow] = false renders the SAME block without the
     * built-in Paint shadow — the base bitmap the style bake consumes (the
     * user style replaces the native one; baking both would double shadows).
     * The default true keeps the plain path byte-identical.
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
        nativeShadow: Boolean = true,
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
        val shadow = if (nativeShadow) {
            Paint(paint).apply {
                setShadowLayer(fontPx * 0.08f, 0f, fontPx * 0.04f, 0x88000000.toInt())
            }
        } else {
            paint
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

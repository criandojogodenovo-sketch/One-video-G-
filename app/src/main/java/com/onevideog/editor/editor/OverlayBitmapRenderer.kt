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
import android.util.LruCache
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.TextFontFamily

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
            append(overlay.text.hashCode()).append('|')
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
        val decoded = decodeImage(context, Uri.parse(overlay.uri)) ?: return null
        cache.put(key, decoded)
        return decoded
    }

    /** Aspect (w/h) of the rendered content bitmap. */
    fun bitmapAspect(bitmap: Bitmap): Float =
        if (bitmap.height <= 0) 1f else bitmap.width.toFloat() / bitmap.height

    /** Evicts a single overlay's bitmap (after content edits that change size). */
    fun evict(overlay: OverlayItem) {
        cache.remove(contentKey(overlay))
    }

    /** Decodes an image URI sampled to at most [IMAGE_MAX_DIM] on its longest side. */
    fun decodeImage(context: Context, uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        while (largest / (sample * 2) >= IMAGE_MAX_DIM) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }.getOrNull()

    /**
     * Draws the text block (with optional background pill) on a transparent
     * bitmap, word-wrapped at [width]. Shared by preview and export paths.
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
        val lines = wrapText(text, paint, width * 0.98f)
        val lineSpacing = fontPx * 0.22f
        val textBlockH = lines.size * fontPx + (lines.size - 1) * lineSpacing

        val hasBg = (bgArgb ushr 24) != 0
        val padX = if (hasBg) bgPadPx * 0.9f else 2f
        val padY = if (hasBg) bgPadPx else 2f
        var blockW = 0f
        for (line in lines) blockW = maxOf(blockW, paint.measureText(line))
        val outW = (blockW + padX * 2).toInt().coerceAtLeast(4)
        val outH = (textBlockH + padY * 2).toInt().coerceAtLeast(4)

        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (hasBg) {
            val rect = RectF(0f, 0f, outW.toFloat(), outH.toFloat())
            val path = Path()
            path.addRoundRect(rect, bgRadiusPx, bgRadiusPx, Path.Direction.CW)
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgArgb }
            canvas.drawPath(path, bgPaint)
        }
        val shadow = Paint(paint).apply {
            setShadowLayer(fontPx * 0.08f, 0f, fontPx * 0.04f, 0x88000000.toInt())
        }
        var y = padY + fontPx * 0.82f
        for (line in lines) {
            canvas.drawText(line, outW / 2f, y, shadow)
            y += fontPx + lineSpacing
        }
        return bitmap
    }

    /** Greedy word wrap used for both preview and export text rendering. */
    fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        for (paragraph in text.split("\n")) {
            var line = ""
            for (word in paragraph.split(" ")) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(candidate) <= maxWidth || line.isEmpty()) {
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
}

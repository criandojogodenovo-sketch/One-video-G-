package com.onevideog.editor.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.annotation.ColorInt
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.RgbMatrix
import com.onevideog.editor.data.OverlayPosition
import kotlin.math.min

/**
 * Overlay rendering helpers and the colour adjustment effect.
 *
 * Overlays are composed into a full-frame transparent bitmap at render time,
 * which keeps OverlaySettings trivial (stretch to frame) and avoids GL
 * positioning pitfalls. Visibility windows are handled by
 * [TimeWindowedBitmapOverlay], which swaps to a transparent bitmap outside
 * its window — returning null from getBitmap is not safe in Media3 1.4.1
 * (getTextureId dereferences the bitmap).
 */
object OverlayRenderer {

    private const val TRANSPARENT_SIZE = 2

    /** Shared 1x transparent bitmap used to hide overlays outside their window. */
    val transparentBitmap: Bitmap = Bitmap.createBitmap(
        TRANSPARENT_SIZE, TRANSPARENT_SIZE, Bitmap.Config.ARGB_8888,
    )

    /** Text sizes and margins, as a fraction of frame height. */
    private const val TEXT_SIZE_RATIO = 0.07f
    private const val TEXT_MARGIN_RATIO = 0.08f

    /**
     * Renders [text] onto a transparent full-frame bitmap. Long text is
     * wrapped per word to stay inside the frame.
     */
    fun renderText(
        width: Int,
        height: Int,
        text: String,
        @ColorInt colorArgb: Int,
        position: OverlayPosition,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorArgb
            textSize = height * TEXT_SIZE_RATIO
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            setShadowLayer(height * 0.006f, 0f, height * 0.003f, 0x99000000.toInt())
        }

        val maxWidth = width * 0.9f
        val lines = wrapText(text, paint, maxWidth)
        val lineSpacing = paint.textSize * 0.25f
        val blockHeight = lines.size * paint.textSize + (lines.size - 1) * lineSpacing

        val baseY = when (position) {
            OverlayPosition.TOP -> height * TEXT_MARGIN_RATIO + paint.textSize
            OverlayPosition.CENTER -> (height - blockHeight) / 2f + paint.textSize
            OverlayPosition.BOTTOM -> height - height * TEXT_MARGIN_RATIO - blockHeight + paint.textSize
        }

        var y = baseY
        for (line in lines) {
            canvas.drawText(line, width / 2f, y, paint)
            y += paint.textSize + lineSpacing
        }
        return bitmap
    }

    /**
     * Composes [image] onto a transparent full-frame bitmap, scaled so its
     * width is [scale] x frame width, at [position].
     */
    fun renderImage(
        width: Int,
        height: Int,
        image: Bitmap,
        position: OverlayPosition,
        scale: Float,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val targetW = (width * scale.coerceIn(0.1f, 1f)).toInt()
        val targetH = (image.height.toLong() * targetW / image.width.coerceAtLeast(1)).toInt()
        val scaled = Bitmap.createScaledBitmap(image, targetW, targetH.coerceAtLeast(1), true)
        val x = (width - targetW) / 2f
        val y = when (position) {
            OverlayPosition.TOP -> height * TEXT_MARGIN_RATIO
            OverlayPosition.CENTER -> (height - targetH) / 2f
            OverlayPosition.BOTTOM -> height - height * TEXT_MARGIN_RATIO - targetH
        }
        canvas.drawBitmap(scaled, x, y, null)
        if (scaled !== image) scaled.recycle()
        return bitmap
    }

    /** Decodes an image URI sampled to at most [maxDim] on its longest side. */
    fun decodeImage(context: Context, uri: Uri, maxDim: Int = 1024): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: return null
        var sample = 1
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        while (largest / (sample * 2) >= maxDim) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
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

/**
 * A [BitmapOverlay] visible only inside [startUs]..[endUs] on the item's
 * local timeline.
 */
@UnstableApi
class TimeWindowedBitmapOverlay(
    private val visibleBitmap: Bitmap,
    private val startUs: Long,
    private val endUs: Long,
) : BitmapOverlay() {

    override fun getBitmap(presentationTimeUs: Long): Bitmap =
        if (presentationTimeUs in startUs until endUs) {
            visibleBitmap
        } else {
            OverlayRenderer.transparentBitmap
        }
}

/**
 * Brightness / contrast / saturation as a 4x4 RGB colour matrix
 * (android.opengl.Matrix layout, column-major). Applied to linear RGB by the
 * Media3 pipeline; values are "basic adjustments", not colour science.
 */
@UnstableApi
class ColorAdjustmentEffect(
    private val brightness: Float, // -1..1
    private val contrast: Float, // typically 0.5..2
    private val saturation: Float, // 0..2
) : RgbMatrix {

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        var m = identity()
        m = multiply(saturationMatrix(saturation), m)
        m = multiply(contrastMatrix(contrast), m)
        m = multiply(brightnessMatrix(brightness), m)
        return m
    }

    private fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    /** out = a * b (column-major 4x4). */
    private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var acc = 0f
                for (k in 0 until 4) {
                    acc += a[k * 4 + row] * b[col * 4 + k]
                }
                out[col * 4 + row] = acc
            }
        }
        return out
    }

    /** Rec.709 luminance-preserving saturation matrix. */
    private fun saturationMatrix(s: Float): FloatArray {
        val lr = 0.2126f
        val lg = 0.7152f
        val lb = 0.0722f
        return floatArrayOf(
            lr * (1 - s) + s, lg * (1 - s), lb * (1 - s), 0f,
            lr * (1 - s), lg * (1 - s) + s, lb * (1 - s), 0f,
            lr * (1 - s), lg * (1 - s), lb * (1 - s) + s, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    /** c' = contrast * c + (0.5 - 0.5 * contrast). */
    private fun contrastMatrix(k: Float): FloatArray {
        val offset = 0.5f - 0.5f * k
        return floatArrayOf(
            k, 0f, 0f, offset,
            0f, k, 0f, offset,
            0f, 0f, k, offset,
            0f, 0f, 0f, 1f,
        )
    }

    /** Adds [b] to each channel. */
    private fun brightnessMatrix(b: Float): FloatArray {
        val m = identity()
        m[3] = b
        m[7] = b
        m[11] = b
        return m
    }

    companion object {
        /** Maps a -100..100 UI slider to the -0.3..0.3 brightness range. */
        fun brightnessFromSlider(value: Int): Float = value.coerceIn(-100, 100) / 100f * 0.3f

        /** Maps a -100..100 UI slider to a 0.5..2 contrast factor. */
        fun contrastFromSlider(value: Int): Float {
            val v = value.coerceIn(-100, 100) / 100f
            return if (v >= 0) 1f + v else 1f + v * 0.5f
        }

        /** Maps a -100..100 UI slider to a 0..2 saturation factor. */
        fun saturationFromSlider(value: Int): Float {
            val v = value.coerceIn(-100, 100) / 100f
            return if (v >= 0) 1f + v else 1f + v // 0..1..2; negative side desaturates to grey at -100
        }

        fun clampScale(scale: Float): Float = min(scale, 1f)
    }
}

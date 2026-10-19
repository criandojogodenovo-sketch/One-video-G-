package com.onevideog.editor.editor

import kotlin.math.roundToInt

/**
 * Pure colour-adjustment math shared by:
 *  - the export pipeline ([ColorAdjustmentEffect] wraps [rgbMatrix44]),
 *  - the preview (via [androidColorMatrix45] applied to the TextureView's
 *    hardware layer Paint).
 *
 * Limitation (documented in docs/PREVIEW_EXPORT_PARITY.md): Media3 applies
 * RgbMatrix in linear RGB, while the preview's android ColorMatrix works in
 * sRGB. Both produce the same directional adjustment, but pixel values are
 * not bit-identical between preview and export.
 */
object ColorMatrixMath {

    /** Column-major 4x4 GL matrix (Media3 RgbMatrix layout). */
    fun rgbMatrix44(brightness: Float, contrast: Float, saturation: Float): FloatArray {
        var m = identity44()
        m = multiply44(saturationMatrix44(saturation), m)
        m = multiply44(contrastMatrix44(contrast), m)
        m = multiply44(brightnessMatrix44(brightness), m)
        return m
    }

    /**
     * Row-major 4x5 android ColorMatrix (android.graphics.ColorMatrix layout)
     * implementing the same adjustments in sRGB space for the preview.
     *
     * Pipeline: add brightness, apply contrast around 0.5, then saturation
     * around Rec.709 luma. Composed analytically:
     *   out_i = s*k*in_i + k*luma(in)*(1-s) + (k*b + 0.5 - 0.5*k)
     */
    fun androidColorMatrix45(brightness: Float, contrast: Float, saturation: Float): FloatArray {
        val lr = 0.2126f
        val lg = 0.7152f
        val lb = 0.0722f
        val s = saturation
        val k = contrast
        val offset = k * brightness + (0.5f - 0.5f * k)
        return floatArrayOf(
            k * (lr * (1 - s) + s), k * (lg * (1 - s)), k * (lb * (1 - s)), 0f, offset,
            k * (lr * (1 - s)), k * (lg * (1 - s) + s), k * (lb * (1 - s)), 0f, offset,
            k * (lr * (1 - s)), k * (lg * (1 - s)), k * (lb * (1 - s) + s), 0f, offset,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    // ---- 4x4 column-major helpers (Media3 RgbMatrix) ----

    fun identity44(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    /** out = a * b (column-major 4x4). */
    fun multiply44(a: FloatArray, b: FloatArray): FloatArray {
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

    /** Rec.709 luminance-preserving saturation. */
    fun saturationMatrix44(s: Float): FloatArray {
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
    fun contrastMatrix44(k: Float): FloatArray {
        val offset = 0.5f - 0.5f * k
        return floatArrayOf(
            k, 0f, 0f, offset,
            0f, k, 0f, offset,
            0f, 0f, k, offset,
            0f, 0f, 0f, 1f,
        )
    }

    /** Adds [b] to each channel. */
    fun brightnessMatrix44(b: Float): FloatArray {
        val m = identity44()
        m[3] = b
        m[7] = b
        m[11] = b
        return m
    }

    // ---- UI slider mappings (unchanged from v1) ----

    fun brightnessFromSlider(value: Int): Float = value.coerceIn(-100, 100) / 100f * 0.3f

    fun contrastFromSlider(value: Int): Float {
        val v = value.coerceIn(-100, 100) / 100f
        return if (v >= 0) 1f + v else 1f + v * 0.5f
    }

    fun saturationFromSlider(value: Int): Float {
        val v = value.coerceIn(-100, 100) / 100f
        return if (v >= 0) 1f + v else 1f + v
    }

    fun roundEven(value: Float): Int {
        val v = value.roundToInt()
        return if (v % 2 == 0) v else v - 1
    }
}

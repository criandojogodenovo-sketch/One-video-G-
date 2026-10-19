package com.onevideog.editor.editor

import android.graphics.Bitmap
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbMatrix

/**
 * Shared resources and the colour adjustment effect.
 *
 * - [OverlayRenderer.transparentBitmap]: shared 1x transparent bitmap used to
 *   hide overlays outside their window — returning null from getBitmap is not
 *   safe in Media3 1.4.1 (getTextureId dereferences the bitmap).
 * - [ColorAdjustmentEffect]: brightness/contrast/saturation built by
 *   [ColorMatrixMath] (the same math the preview uses), keeping the two
 *   pipelines consistent.
 */
object OverlayRenderer {

    private const val TRANSPARENT_SIZE = 2

    /** Shared 1x transparent bitmap used to hide overlays outside their window. */
    val transparentBitmap: Bitmap = Bitmap.createBitmap(
        TRANSPARENT_SIZE, TRANSPARENT_SIZE, Bitmap.Config.ARGB_8888,
    )
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

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray =
        ColorMatrixMath.rgbMatrix44(brightness, contrast, saturation)
}

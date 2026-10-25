package com.onevideog.editor.editor

import android.graphics.Matrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import com.onevideog.editor.data.CameraState

/**
 * v0.9.0 (Fase L2, spike L2b INTEGRADO — portão D4 passado, ver
 * VideoPlaneCameraParityTest): the video-plane camera as a Media3 1.4.1
 * [MatrixTransformation].
 *
 * Why this primitive (verified against the REAL 1.4.1 sources during the
 * spike): MatrixTransformation feeds a per-frame android.graphics.Matrix
 * through MatrixUtils.getGlMatrixArray into the stock transformation vertex
 * shader (`gl_Position = uTransformationMatrix * aFramePosition`), KEEPS the
 * frame size (GlMatrixTransformation.configure default) and CLIPS pixels
 * pushed outside NDC (DefaultShaderProgram visible polygon) — a camera crop.
 * The spike first named ScaleAndRotateTransformation, but its real `configure`
 * RESIZES the output frame to preserve all pixels (bbox compensation), which
 * makes a uniform zoom a visual no-op downstream of the SCALE_TO_FIT
 * Presentation and offers no pan — so the harness gates the
 * MatrixTransformation vehicle instead (documented in the test + PARITY.md).
 *
 * Time base: identical to the overlays — Media3 resets item timestamps to 0
 * at the clip window start and SpeedChangeEffect has already scaled them so
 * effect time == project-local time; [surfaceStartProjectMs] converts to
 * absolute project time, the exact convention KeyframedOverlay uses.
 *
 * No new shader, no new dependency: the shader is Media3's stock
 * transformation shader; the matrix is pure [CameraMath] output.
 */
@UnstableApi
class CameraTransformEffect(
    private val camera: CameraState,
    /** Project-time position of this item's local t=0. */
    private val surfaceStartProjectMs: Long,
) : MatrixTransformation {

    // One reusable Matrix: DefaultShaderProgram reads getValues() per frame
    // (its matrix cache compares the VALUES), and getMatrix runs on the GL
    // thread only — mutation between frames is safe and allocation-free.
    private val matrix = Matrix()

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val pose = CameraMath.evaluate(
            camera,
            surfaceStartProjectMs + presentationTimeUs / 1000,
        )
        matrix.setValues(CameraMath.videoPlaneMatrixValues(pose))
        return matrix
    }
}

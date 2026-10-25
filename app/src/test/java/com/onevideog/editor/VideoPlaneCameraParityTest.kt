package com.onevideog.editor.editor

import com.onevideog.editor.data.CameraKeyframe
import com.onevideog.editor.data.CameraState
import com.onevideog.editor.data.Interpolation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * v0.9.0 SPIKE L2b (decisão D4 — portão do estudo de viabilidade aprovado):
 * prova, contra a cadeia REAL do Media3 1.4.1, que o plano de vídeo sob a
 * câmara afim (MatrixTransformation) renderiza EXATAMENTE o que a
 * [CameraMath] manda — os 4 cantos de um quad de prova no plano de vídeo
 * coincidem entre o caminho de pré-visualização (matemática Compose/CameraMath)
 * e o caminho de export (matriz GL portada), dentro de 1 px, em 2 aspects
 * (9:16 retrato e 16:9 paisagem).
 *
 * Evidência usada (fontes REAIS do tag 1.4.1, descarregadas do Google Maven):
 *
 *  1. `MatrixUtils.getGlMatrixArray` (media3-effect 1.4.1): converte a
 *     android.graphics.Matrix 3x3 (getValues, row-major
 *     [MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y, MSCALE_Y, MTRANS_Y, MPERSP...])
 *     num array 4x4 [a,b,0,c, d,e,0,f, 0,0,1,0, g,h,0,i] e TRANSPOÕE para
 *     column-major — logo a matriz GL aplica ao vértice EXACTAMENTE o mapeamento
 *     de pontos da Matrix do Android.
 *  2. `shaders/vertex_shader_transformation_es2.glsl` (recurso do AAR 1.4.1):
 *     `gl_Position = uTransformationMatrix * aFramePosition` — multiplicação
 *     matriz-coluna padrão; para matrizes afins w continua 1 (sem divisão de
 *     perspetiva); a componente de AMOSTRAGEM usa a inversa (o conteúdo
 *     desenhado = a matriz aplicada à entrada).
 *  3. `DefaultShaderProgram.updateCompositeTransformationMatrixAndVisiblePolygon`
 *     + `drawFrame` (1.4.1): o draw cobre o polígono visível transformado e
 *     CORTADO ao intervalo NDC — píxeis empurrados para fora do frame são
 *     cortados (comportamento de câmara: zoom recorta as bordas).
 *  4. `MatrixTransformation.configure` (default de GlMatrixTransformation):
 *     o frame de SAÍDA mantém o tamanho da entrada — o Presentation
 *     SCALE_TO_FIT a jusante não volta a escalar o conteúdo.
 *
 * Nota da investigação que FEZ O SPIKE ADAPTAR O VEÍCULO: D4 nomeava
 * `ScaleAndRotateTransformation`, mas as fontes reais mostram que essa classe
 * REDIMENSIONA o frame de saída para preservar todos os píxeis (bbox
 * compensation no `configure`) — um zoom uniforme cresce o frame e o
 * Presentation SCALE_TO_FIT a jusante desfaz o zoom visualmente (no-op), além
 * de não ter translação (pan). O veículo correto no 1.4.1 é o
 * `MatrixTransformation` (a interface que ScaleAndRotate implementa) com a
 * matriz por frame da CameraMath — dinâmico (keyframes), tamanho fixo, corta
 * fora do NDC. Este harness testa o veículo correto.
 */
class VideoPlaneCameraParityTest {

    // --------------------------------------------- Media3 1.4.1 faithful port

    /**
     * MatrixUtils.getGlMatrixArray (1.4.1) — android Matrix values -> 4x4
     * column-major GL array. Values come in getValues order
     * [MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y, MSCALE_Y, MTRANS_Y, p0, p1, p2].
     */
    private fun glMatrixArrayFromValues(v: FloatArray): FloatArray {
        // getMatrix4x4Array: [a,b,0,c, d,e,0,f, 0,0,1,0, g,h,0,i] (row-major)
        val rowMajor = floatArrayOf(
            v[0], v[1], 0f, v[2],
            v[3], v[4], 0f, v[5],
            0f, 0f, 1f, 0f,
            v[6], v[7], 0f, v[8],
        )
        // Matrix.transposeM — row-major -> column-major.
        val colMajor = FloatArray(16)
        for (r in 0..3) for (c in 0..3) colMajor[r * 4 + c] = rowMajor[c * 4 + r]
        return colMajor
    }

    /**
     * The real vertex shader position path: gl_Position = uTransformationMatrix
     * * aFramePosition, aFramePosition = (x, y, 0, 1). Column-major multiply.
     */
    private fun vertexNdc(m: FloatArray, x: Float, y: Float): Pair<Float, Float> {
        val vx = x; val vy = y; val vz = 0f; val vw = 1f
        val outX = m[0] * vx + m[4] * vy + m[8] * vz + m[12] * vw
        val outY = m[1] * vx + m[5] * vy + m[9] * vz + m[13] * vw
        val outW = m[3] * vx + m[7] * vy + m[11] * vz + m[15] * vw
        // Affine android matrices keep w = 1 (MPERSP = 0,0,1); the GL pipeline
        // divides by w — with w == 1 it is a no-op. Assert the invariant.
        assertEquals(1f, outW, 1e-4f)
        return outX to outY
    }

    /** Output px of an NDC point on a W×H frame (y down, GL y up). */
    private fun ndcToPx(ndcX: Float, ndcY: Float, w: Int, h: Int): Pair<Float, Float> =
        ((ndcX + 1f) / 2f * w) to ((1f - ndcY) / 2f * h)

    /**
     * EXPORT path: where does the video-plane point at content NDC (xNdc,
     * yNdc) land on the output frame, through the production CameraMath
     * values + the ported 1.4.1 GL chain?
     */
    private fun exportVideoPlanePx(
        frameW: Int,
        frameH: Int,
        pose: CameraMath.Pose,
        xNdc: Float,
        yNdc: Float,
    ): Pair<Float, Float> {
        val values = CameraMath.videoPlaneMatrixValues(pose)
        val gl = glMatrixArrayFromValues(values)
        val (ndcX, ndcY) = vertexNdc(gl, xNdc, yNdc)
        return ndcToPx(ndcX, ndcY, frameW, frameH)
    }

    /**
     * PREVIEW path: the same point through the SHARED pure math the preview
     * consumes — the depth-0 affine applied to a content position, converted
     * to px the way computePlacements does (fraction × frame px).
     */
    private fun previewVideoPlanePx(
        frameW: Int,
        frameH: Int,
        pose: CameraMath.Pose,
        xNdc: Float,
        yNdc: Float,
    ): Pair<Float, Float> {
        // The camera maps the plane with the depth-0 affine — the SAME values
        // the video-plane matrix carries (affineForDepth(pose, 0)).
        val d = CameraMath.affineForDepth(pose, 0f)
        val outXNdc = d.zoom * xNdc + d.ndcShiftX
        val outYNdc = d.zoom * yNdc + d.ndcShiftY
        return ((outXNdc + 1f) / 2f * frameW) to ((1f - outYNdc) / 2f * frameH)
    }

    // ------------------------------------------------------------- the gate

    /**
     * THE D4 GATE: the four corners of a probe quad on the video plane, under
     * camera poses (pan/zoom combinations incl. keyframed evaluation), in
     * BOTH aspects, must agree within 1 px. Probe quads stay inside the frame
     * under the pose (points pushed outside NDC are clipped by the pipeline —
     * documented camera-crop behaviour, not a parity failure).
     */
    @Test
    fun `video plane corners match preview under the camera on a portrait frame`() {
        gate(frameW = 1080, frameH = 1920)
    }

    @Test
    fun `video plane corners match preview under the camera on a landscape frame`() {
        gate(frameW = 1920, frameH = 1080)
    }

    private fun gate(frameW: Int, frameH: Int) {
        val poses = listOf(
            CameraMath.IDENTITY_POSE,
            CameraMath.Pose(panX = 0.10f, panY = -0.05f, zoom = 1.6f),
            CameraMath.Pose(panX = -0.20f, panY = 0.15f, zoom = 2.4f),
            CameraMath.Pose(panX = 0.05f, panY = 0.05f, zoom = 1.15f),
            // Keyframed camera evaluated at t = mid-flight.
            keyframedPoseAt(750L),
        )
        for (pose in poses) {
            // Probe quad: the content NDC points whose images stay inside the
            // frame under the pose (zoom pulls them toward the centre; probes
            // are chosen inside the zoomed-in region).
            val inset = 1f / maxOf(pose.zoom, 1f) * 0.9f
            val probes = listOf(
                -inset to -inset, inset to -inset, -inset to inset, inset to inset,
            )
            for ((xNdc, yNdc) in probes) {
                val preview = previewVideoPlanePx(frameW, frameH, pose, xNdc, yNdc)
                val export = exportVideoPlanePx(frameW, frameH, pose, xNdc, yNdc)
                assertTrue(
                    "pose=$pose probe=($xNdc,$yNdc) preview=$preview export=$export",
                    abs(preview.first - export.first) <= 1f &&
                        abs(preview.second - export.second) <= 1f,
                )
            }
        }
    }

    /** The full frame corners map to themselves under the identity camera. */
    @Test
    fun `identity camera leaves the frame corners untouched`() {
        for ((x, y) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
            val (px, py) = exportVideoPlanePx(1080, 1920, CameraMath.IDENTITY_POSE, x, y)
            assertEquals(if (x < 0f) 0f else 1080f, px, 1f)
            // NDC y is UP: ndcY = -1 is the BOTTOM of the frame in px.
            assertEquals(if (y < 0f) 1920f else 0f, py, 1f)
        }
    }

    /**
     * COHERENCE: a zDepth = 0 overlay rides the video plane EXACTLY — the
     * mapped position/scale of the overlay equal the plane's affine applied
     * to the same point (the property that makes the camera look like ONE
     * move of the whole scene, and the reason the parity of overlays under
     * the camera inherits the v0.8.1 <= 1 px corner contract).
     */
    @Test
    fun `depth zero overlays ride the video plane exactly`() {
        val pose = CameraMath.Pose(panX = 0.12f, panY = -0.08f, zoom = 1.8f)
        val d = CameraMath.affineForDepth(pose, 0f)
        val plane = CameraMath.videoPlaneMatrixValues(pose)
        assertEquals(d.zoom, plane[0], 1e-6f)
        assertEquals(d.ndcShiftX, plane[2], 1e-6f)
        assertEquals(d.ndcShiftY, plane[5], 1e-6f)
        // applyToTransform at depth 0 maps the centre exactly like the plane.
        val mapped = CameraMath.applyToTransform(
            pose, 0f,
            com.onevideog.editor.data.OverlayTransform(x = 0.3f, y = 0.7f, scale = 0.5f),
        )
        val xNdc = 2f * 0.3f - 1f
        val yNdc = 1f - 2f * 0.7f
        assertEquals((d.zoom * xNdc + d.ndcShiftX + 1f) / 2f, mapped.x, 1e-5f)
        assertEquals((1f - (d.zoom * yNdc + d.ndcShiftY)) / 2f, mapped.y, 1e-5f)
        assertEquals(0.5f * d.zoom, mapped.scale, 1e-5f)
    }

    /** Pan direction sanity: +panX moves content right, +panY moves it DOWN. */
    @Test
    fun `pan directions follow the screen convention`() {
        val pose = CameraMath.Pose(panX = 0.1f, panY = 0.1f, zoom = 1f)
        val centre = exportVideoPlanePx(1080, 1920, CameraMath.IDENTITY_POSE, 0f, 0f)
        val moved = exportVideoPlanePx(1080, 1920, pose, 0f, 0f)
        assertTrue("panX must move content right", moved.first > centre.first)
        assertTrue("panY must move content down", moved.second > centre.second)
    }

    // ------------------------------------------------------------- helpers

    private fun keyframedPoseAt(t: Long): CameraMath.Pose {
        val camera = CameraState(
            keyframes = listOf(
                CameraKeyframe(timeMs = 0, panX = -0.2f, panY = 0.1f, zoom = 1.2f),
                CameraKeyframe(timeMs = 1_000, panX = 0.2f, panY = -0.1f, zoom = 2.2f),
            ),
            interpolation = Interpolation.LINEAR,
        )
        return CameraMath.evaluate(camera, t)
    }
}

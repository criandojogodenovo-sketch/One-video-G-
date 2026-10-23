package com.onevideog.editor.editor

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.Effect
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.onevideog.editor.data.ColorCurves
import java.nio.ByteBuffer

/**
 * v0.7.0 (Fase J) — the Media3 glue for the per-clip colour curves: converts
 * the model's [ColorCurves] into ONE [Effect] consumed by BOTH pipelines
 * (preview `ExoPlayer.setVideoEffects` and export
 * `EditedMediaItem.setEffects`, both through [ClipEffectsFactory.effectsFor]).
 *
 * The shader string, the Fritsch–Carlson spline and the CPU reference live in
 * [CurveMath] (pure, JVM-tested); this file only wires the Media3 GL plumbing
 * — the four LUTs are uploaded as 256x1 8-bit textures (GL_LUMINANCE,
 * GL_NEAREST, CLAMP_TO_EDGE) and sampled per channel by the shared shader
 * (CurveMath.curvesFragmentShaderCode).
 *
 * Identity/absent curves build NO effect instance (no GPU cost): the glue
 * returns an empty list when curves are null, invalid, or all four are the
 * identity within CurveMath.IDENTITY_TOLERANCE.
 */
@UnstableApi
object ColorCurvesEffects {

    /**
     * Effects for [curves]: empty when absent/identity/invalid (no GPU cost),
     otherwise exactly one [ColorCurvesEffect] carrying all four LUTs.
     */
    fun effectsFor(curves: ColorCurves?): List<Effect> {
        curves ?: return emptyList()
        if (!CurveMath.isValid(curves.master) || !CurveMath.isValid(curves.red) ||
            !CurveMath.isValid(curves.green) || !CurveMath.isValid(curves.blue)
        ) {
            // Defence in depth: the ViewModel never stores invalid curves.
            return emptyList()
        }
        if (CurveMath.isIdentity(curves.master) && CurveMath.isIdentity(curves.red) &&
            CurveMath.isIdentity(curves.green) && CurveMath.isIdentity(curves.blue)
        ) {
            return emptyList()
        }
        return listOf(ColorCurvesEffect(curves))
    }
}

/**
 * One curves pass (Master → R → G → B in a single shader). JVM-testable spec:
 * [glslShaderString] (the exact fragment shader compiled) and [applyToFrame]
 * (the CPU reference — the same four LUTs, same order, same 8-bit mapping).
 */
@UnstableApi
class ColorCurvesEffect(private val curves: ColorCurves) : GlEffect {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        CurvesShaderProgram(curves, useHdr)

    fun glslShaderString(): String = CurveMath.curvesFragmentShaderCode()

    /** CPU reference of the whole pass (JVM-testable behavioural spec). */
    fun applyToFrame(pixels: IntArray, width: Int, height: Int): IntArray {
        require(pixels.size == width * height) { "pixel buffer must match w*h" }
        return CurveMath.applyCurves(
            pixels,
            masterLut = CurveMath.buildLut(curves.master),
            redLut = CurveMath.buildLut(curves.red),
            greenLut = CurveMath.buildLut(curves.green),
            blueLut = CurveMath.buildLut(curves.blue),
        )
    }
}

@UnstableApi
internal class CurvesShaderProgram(
    private val curves: ColorCurves,
    useHdr: Boolean,
) : GlShaderProgramBase(CurveMath.curvesFragmentShaderCode(), useHdr) {

    // 256x1 LUT textures, created lazily on the GL thread (first frame) and
    // kept for the program's lifetime — they do not depend on frame size.
    private var lutTexIds: IntArray? = null

    override fun onConfigure(inputWidth: Int, inputHeight: Int) {
        // Uniforms are set per draw (sampler units); nothing size-dependent.
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val texIds = lutTexIds ?: createLutTextures().also { lutTexIds = it }
        glProgram.setSamplerTexIdUniform("u_LutMaster", texIds[0], /* texUnitIndex = */ 1)
        glProgram.setSamplerTexIdUniform("u_LutR", texIds[1], /* texUnitIndex = */ 2)
        glProgram.setSamplerTexIdUniform("u_LutG", texIds[2], /* texUnitIndex = */ 3)
        glProgram.setSamplerTexIdUniform("u_LutB", texIds[3], /* texUnitIndex = */ 4)
        drawQuad(inputTexId, presentationTimeUs)
    }

    override fun release() {
        lutTexIds?.let { ids ->
            GLES20.glDeleteTextures(ids.size, ids, 0)
            lutTexIds = null
        }
        super.release()
    }

    /**
     * Uploads the four 256x1 LUTs as GL_LUMINANCE textures with the SAME
     * 8-bit quantization the CPU reference uses (CurveMath.lutBytes) — the
     * documented preview/export/CPU parity basis.
     */
    private fun createLutTextures(): IntArray {
        val ids = IntArray(4)
        GLES20.glGenTextures(4, ids, 0)
        val luts = listOf(
            CurveMath.buildLut(curves.master),
            CurveMath.buildLut(curves.red),
            CurveMath.buildLut(curves.green),
            CurveMath.buildLut(curves.blue),
        )
        for (i in 0 until 4) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[i])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val bytes = CurveMath.lutBytes(luts[i])
            val buffer = ByteBuffer.allocateDirect(bytes.size)
            for (v in bytes) buffer.put(v.toByte())
            buffer.position(0)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
                CurveMath.LUT_SIZE, 1, 0,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, buffer,
            )
        }
        GlUtil.checkGlError()
        return ids
    }
}

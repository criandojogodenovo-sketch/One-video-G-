package com.onevideog.editor.editor

import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram

/**
 * v0.6.0 — shared base for every full-frame GLSL effect of the app (the
 * Fase H transition shader and the Fase I blur/vignette/sharpen shaders).
 *
 * The drawing pattern is the Media3 1.4.1 reference implementation pattern
 * (verified against the 1.4.1 sources, HslShaderProgram): one
 * [BaseGlShaderProgram] with a texture pool of 1, a [GlProgram] built from
 * shader STRINGS (no assets), the normalized quad bound on
 * `aFramePosition`, identity transformation matrices, and a triangle-strip
 * quad draw per frame. Uniform values set on the [glProgram] before
 * [drawQuad] are uploaded by `bindAttributesAndUniforms` on the same frame.
 *
 * The vertex shader is the standard Media3 transformation vertex shader
 * (frame position -> NDC, sampling coord -> 0..1): the fragment shaders
 * receive `vTexSamplingCoord` (0..1, y = 0 at the BOTTOM of the frame —
 * the GL texture orientation) and sample `uTexSampler`.
 */
@UnstableApi
internal abstract class GlShaderProgramBase(
    /** Fragment shader source (Media3 GL conventions; see the class docs). */
    fragmentShaderCode: String,
    useHdr: Boolean,
) : BaseGlShaderProgram(
    /* useHighPrecisionColorComponents = */ useHdr,
    /* texturePoolCapacity = */ 1,
) {

    protected val glProgram: GlProgram = try {
        GlProgram(VERTEX_SHADER_CODE, fragmentShaderCode)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }

    init {
        // Draw the frame on the entire normalized device coordinate space,
        // from -1 to 1 for x and y (the Media3 reference pattern).
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
        val identity = GlUtil.create4x4IdentityMatrix()
        glProgram.setFloatsUniform("uTransformationMatrix", identity)
        glProgram.setFloatsUniform("uTexTransformationMatrix", identity)
    }

    /**
     * One-time-per-size hook (called from [configure]) — texel-size uniforms
     * and similar resolution-dependent values go here.
     */
    protected abstract fun onConfigure(inputWidth: Int, inputHeight: Int)

    final override fun configure(inputWidth: Int, inputHeight: Int): Size {
        onConfigure(inputWidth, inputHeight)
        return Size(inputWidth, inputHeight)
    }

    /** Draws the full-screen quad with the uniforms set so far. */
    protected fun drawQuad(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex = */ 0)
            glProgram.bindAttributesAndUniforms()
            // The four-vertex triangle strip forms a quad.
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first = */ 0, /* count = */ 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            glProgram.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    internal companion object {
        /**
         * The standard Media3 1.4.1 transformation vertex shader
         * (shaders/vertex_shader_transformation_es2.glsl, inlined so the
         * effect shaders ship as code — no asset files).
         */
        internal val VERTEX_SHADER_CODE = """
#version 100
attribute vec4 aFramePosition;
uniform mat4 uTransformationMatrix;
uniform mat4 uTexTransformationMatrix;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = uTransformationMatrix * aFramePosition;
  vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5,
                              aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);
  vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;
}
""".trim()
    }
}

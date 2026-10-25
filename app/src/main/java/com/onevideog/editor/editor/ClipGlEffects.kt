package com.onevideog.editor.editor

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.Effect
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.google.common.util.concurrent.MoreExecutors
import com.onevideog.editor.data.ClipEffect
import com.onevideog.editor.data.EffectKind
import java.util.concurrent.Executor

/**
 * v0.6.0 (Fase I) — the Media3 glue for the per-clip 2D effects: converts
 * the model's [ClipEffect] list into [Effect] instances consumed by BOTH
 * pipelines (preview `ExoPlayer.setVideoEffects` and export
 * `EditedMediaItem.setEffects` through [ClipEffectsFactory.effectsFor]).
 *
 * The shader strings and the CPU reference implementations live in
 * [ClipEffectMath] (pure, JVM-tested); this file only wires the Media3 GL
 * plumbing (verified against the 1.4.1 API surface: GlEffect.toGlShaderProgram
 * -> BaseGlShaderProgram with GlProgram).
 *
 * v0.9.1 (BUG A fix — realme RMX3624 / Mali-G57, Android 13): BLUR is
 * separable, and its TWO passes now live inside ONE [GaussianBlurEffect] /
 * ONE [BlurShaderProgram] (pass H -> own intermediate texture -> pass V ->
 * output), the exact structure of Media3's own reference implementation
 * (SeparableConvolutionShaderProgram, verified against the 1.4.1 sources).
 * Until v0.9.0 the two passes were TWO chained GlShaderPrograms, which was
 * fine while the chain was [BlurH, BlurV] (v0.6.0–v0.8.1). The v0.9.0 video
 * plane camera is grouped by DefaultVideoFrameProcessor.createGlShaderPrograms
 * into a matrices-bearing DefaultShaderProgram BEFORE the passes, producing
 * the chain [DS(camera) -> BlurH -> BlurV -> Final] — the only new chain
 * shape of v0.9.0 and the only one that died on the affected device with a
 * deterministic NullPointerException from the GL thread (wrapped by
 * VideoFrameProcessingTaskExecutor as VIDEO_FRAME_PROCESSING_FAILED; the
 * NPE site is not in Media3 1.4.1 nor in this app — verified by source
 * grep — so it is a platform/vendor fault triggered by that shape, while
 * [DS(camera) -> ONE custom program -> Final] (curves) demonstrably works
 * on the same device). Folding the two passes into one program restores the
 * proven-working shape with ZERO visual change: same shader source, same
 * kernel weights, same H-then-V order, same CPU reference (the pass pair
 * is what [ClipEffectMath.applyBlur] always was). Both pipelines consume
 * this same list, so preview/export parity holds by construction.
 */
@UnstableApi
object ClipGlEffects {

    /**
     * Effects for the clip's [ClipEffect] list, in model order, trimmed to
     * [ClipEffectsFactory.MAX_EFFECTS_PER_CLIP] (I7 — the ViewModel already
     * rejects a 4th; this is the defence-in-depth trim). intensity == 0
     * produces NO effect instance (identity — no GPU cost). BLUR produces
     * exactly ONE effect (the two separable passes live inside its shader
     * program — see the class docs).
     */
    fun effectsFor(effects: List<ClipEffect>): List<Effect> {
        if (effects.isEmpty()) return emptyList()
        val out = mutableListOf<Effect>()
        for (clipEffect in effects.take(ClipEffectsFactory.MAX_EFFECTS_PER_CLIP)) {
            when (clipEffect.kind) {
                EffectKind.BLUR -> {
                    val sigma = ClipEffectMath.blurSigma(clipEffect.intensity)
                    if (sigma > 0f) {
                        out += GaussianBlurEffect(sigma)
                    }
                }
                EffectKind.VIGNETTE -> {
                    if (clipEffect.intensity > 0f) out += VignetteEffect(clipEffect.intensity)
                }
                EffectKind.SHARPEN -> {
                    if (clipEffect.intensity > 0f) out += SharpenEffect(clipEffect.intensity)
                }
            }
        }
        return out
    }
}

/**
 * The full separable Gaussian blur (7-tap, H pass then V pass) as ONE
 * [GlEffect]. [sigma] is the physical Gaussian sigma (0 excluded upstream —
 * identity effects are not installed).
 *
 * Test-facing pure API: [glslShaderString] (the fragment shader BOTH passes
 * compile) and [applyToFrame] (the CPU reference of the WHOLE blur — the
 * same H-then-V pair, [ClipEffectMath.applyBlur]).
 */
@UnstableApi
class GaussianBlurEffect(
    private val sigma: Float,
) : GlEffect {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = sigma <= 0f

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        BlurShaderProgram(sigma, useHdr)

    /** The fragment shader source compiled for this effect (JVM-testable). */
    fun glslShaderString(): String = ClipEffectMath.blurFragmentShaderCode()

    /** CPU reference of the whole two-pass blur (JVM-testable behavioural spec). */
    fun applyToFrame(pixels: IntArray, width: Int, height: Int): IntArray =
        ClipEffectMath.applyBlur(pixels, width, height, sigma)
}

/**
 * v0.9.1 — the two-pass blur as ONE [GlShaderProgram], mirroring Media3
 * 1.4.1's own SeparableConvolutionShaderProgram structure (verified against
 * the 1.4.1 sources): it implements [GlShaderProgram] directly (NOT
 * BaseGlShaderProgram) because the intermediate H-pass result must live in
 * a texture the program owns, next to the single output texture it hands
 * downstream. Pass H reads the input and renders into the intermediate;
 * pass V reads the intermediate and renders into the output. One GLSL
 * program object (the shared blur fragment shader), one kernel, per-pass
 * `u_TexelStep` — the ONLY difference between the passes, exactly like the
 * pre-v0.9.1 chained pair.
 *
 * The listener/texture lifecycle is the Media3 reference skeleton:
 * single output texture in use at a time, releaseOutputFrame reopens the
 * input gate, flush resets, release deletes the program and both textures.
 */
@UnstableApi
internal class BlurShaderProgram(
    private val sigma: Float,
    private val useHdr: Boolean,
) : GlShaderProgram {

    private val glProgram: GlProgram = try {
        GlProgram(GlShaderProgramBase.VERTEX_SHADER_CODE, ClipEffectMath.blurFragmentShaderCode())
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }

    private val weights = ClipEffectMath.blurWeights(sigma)

    private var inputListener: GlShaderProgram.InputListener =
        object : GlShaderProgram.InputListener {}
    private var outputListener: GlShaderProgram.OutputListener =
        object : GlShaderProgram.OutputListener {}
    private var errorListener: GlShaderProgram.ErrorListener =
        GlShaderProgram.ErrorListener {}
    private var errorListenerExecutor: Executor = MoreExecutors.directExecutor()
    private var outputTextureInUse = false

    private var outputTexture: GlTextureInfo = GlTextureInfo.UNSET
    private var intermediateTexture: GlTextureInfo = GlTextureInfo.UNSET
    private var lastInputWidth = Int.MIN_VALUE
    private var lastInputHeight = Int.MIN_VALUE

    init {
        // The exact drawing setup of GlShaderProgramBase (the app's other
        // effects): full NDC quad on aFramePosition, identity transformation
        // matrices, kernel uploaded once and re-bound on every draw.
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
        val identity = GlUtil.create4x4IdentityMatrix()
        glProgram.setFloatsUniform("uTransformationMatrix", identity)
        glProgram.setFloatsUniform("uTexTransformationMatrix", identity)
        glProgram.setFloatsUniform("u_Weights", weights)
    }

    override fun setInputListener(inputListener: GlShaderProgram.InputListener) {
        this.inputListener = inputListener
        if (!outputTextureInUse) {
            inputListener.onReadyToAcceptInputFrame()
        }
    }

    override fun setOutputListener(outputListener: GlShaderProgram.OutputListener) {
        this.outputListener = outputListener
    }

    override fun setErrorListener(
        errorListenerExecutor: Executor,
        errorListener: GlShaderProgram.ErrorListener,
    ) {
        this.errorListenerExecutor = errorListenerExecutor
        this.errorListener = errorListener
    }

    override fun queueInputFrame(
        glObjectsProvider: GlObjectsProvider,
        inputTexture: GlTextureInfo,
        presentationTimeUs: Long,
    ) {
        try {
            if (inputTexture.width != lastInputWidth || inputTexture.height != lastInputHeight) {
                intermediateTexture = configurePixelTexture(
                    glObjectsProvider,
                    intermediateTexture,
                    inputTexture.width,
                    inputTexture.height,
                )
                outputTexture = configurePixelTexture(
                    glObjectsProvider,
                    outputTexture,
                    inputTexture.width,
                    inputTexture.height,
                )
                lastInputWidth = inputTexture.width
                lastInputHeight = inputTexture.height
            }
            outputTextureInUse = true
            // Pass 1 — HORIZONTAL: input -> intermediate.
            GlUtil.focusFramebufferUsingCurrentContext(
                intermediateTexture.fboId,
                intermediateTexture.width,
                intermediateTexture.height,
            )
            GlUtil.clearFocusedBuffers()
            drawPass(inputTexture.texId, horizontal = true)
            // Pass 2 — VERTICAL: intermediate -> output (the texture handed
            // downstream, exactly what the old chained second pass produced).
            GlUtil.focusFramebufferUsingCurrentContext(
                outputTexture.fboId,
                outputTexture.width,
                outputTexture.height,
            )
            GlUtil.clearFocusedBuffers()
            drawPass(intermediateTexture.texId, horizontal = false)
            inputListener.onInputFrameProcessed(inputTexture)
            outputListener.onOutputFrameAvailable(outputTexture, presentationTimeUs)
        } catch (e: GlUtil.GlException) {
            errorListenerExecutor.execute {
                errorListener.onError(
                    VideoFrameProcessingException.from(e, presentationTimeUs),
                )
            }
        }
    }

    override fun releaseOutputFrame(outputTexture: GlTextureInfo) {
        outputTextureInUse = false
        inputListener.onReadyToAcceptInputFrame()
    }

    override fun signalEndOfCurrentInputStream() {
        outputListener.onCurrentOutputStreamEnded()
    }

    override fun flush() {
        outputTextureInUse = false
        inputListener.onFlush()
        inputListener.onReadyToAcceptInputFrame()
    }

    override fun release() {
        try {
            outputTexture.release()
            intermediateTexture.release()
            glProgram.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    /** One blur draw: binds the sampler + the per-pass texel step, draws. */
    private fun drawPass(inputTexId: Int, horizontal: Boolean) {
        glProgram.use()
        glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex = */ 0)
        val step = if (horizontal) {
            floatArrayOf(1f / lastInputWidth, 0f)
        } else {
            floatArrayOf(0f, 1f / lastInputHeight)
        }
        glProgram.setFloatsUniform("u_TexelStep", step)
        glProgram.bindAttributesAndUniforms()
        // The four-vertex triangle strip forms a quad.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first = */ 0, /* count = */ 4)
        GlUtil.checkGlError()
    }

    /** The TexturePool/SeparableConvolution texture setup pattern (1.4.1). */
    private fun configurePixelTexture(
        glObjectsProvider: GlObjectsProvider,
        existing: GlTextureInfo,
        width: Int,
        height: Int,
    ): GlTextureInfo {
        if (width == existing.width && height == existing.height) {
            return existing
        }
        existing.release()
        val texId = GlUtil.createTexture(width, height, useHdr)
        return glObjectsProvider.createBuffersForTexture(texId, width, height)
    }
}

/**
 * Vignette: darkens the frame edges with `k = 1 - intensity * d2` (d2
 * normalized so the corners sit at d2 == 1 — see [ClipEffectMath]).
 */
@UnstableApi
class VignetteEffect(private val intensity: Float) : GlEffect {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = intensity <= 0f

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        VignetteShaderProgram(intensity, useHdr)

    fun glslShaderString(): String = ClipEffectMath.vignetteFragmentShaderCode()

    fun applyToFrame(pixels: IntArray, width: Int, height: Int): IntArray =
        ClipEffectMath.applyVignette(pixels, width, height, intensity)
}

@UnstableApi
internal class VignetteShaderProgram(
    private val intensity: Float,
    useHdr: Boolean,
) : GlShaderProgramBase(ClipEffectMath.vignetteFragmentShaderCode(), useHdr) {

    override fun onConfigure(inputWidth: Int, inputHeight: Int) {
        glProgram.setFloatUniform("u_Intensity", intensity.coerceIn(0f, 1f))
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        drawQuad(inputTexId, presentationTimeUs)
    }
}

/**
 * Sharpen: 3x3 unsharp mask, `out = center * (1 + 4a) - a * neighbours`
 * with `a = intensity` (clamped 0..1 — see [ClipEffectMath.MAX_AMOUNT]).
 */
@UnstableApi
class SharpenEffect(private val intensity: Float) : GlEffect {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = intensity <= 0f

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        SharpenShaderProgram(intensity, useHdr)

    fun glslShaderString(): String = ClipEffectMath.sharpenFragmentShaderCode()

    fun applyToFrame(pixels: IntArray, width: Int, height: Int): IntArray =
        ClipEffectMath.applySharpen(pixels, width, height, intensity)
}

@UnstableApi
internal class SharpenShaderProgram(
    intensity: Float,
    useHdr: Boolean,
) : GlShaderProgramBase(ClipEffectMath.sharpenFragmentShaderCode(), useHdr) {

    private val amount = ClipEffectMath.sharpenAmount(intensity)

    override fun onConfigure(inputWidth: Int, inputHeight: Int) {
        glProgram.setFloatUniform("u_Amount", amount)
        glProgram.setFloatsUniform("u_TexelSize", floatArrayOf(1f / inputWidth, 1f / inputHeight))
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        drawQuad(inputTexId, presentationTimeUs)
    }
}

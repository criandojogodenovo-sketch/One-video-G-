package com.onevideog.editor.editor

import android.content.Context
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.onevideog.editor.data.ClipEffect
import com.onevideog.editor.data.EffectKind

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
 * BLUR is separable: one intensity produces TWO chained passes (horizontal
 * then vertical) — the same two passes the CPU reference runs.
 */
@UnstableApi
object ClipGlEffects {

    /**
     * Effects for the clip's [ClipEffect] list, in model order, trimmed to
     * [ClipEffectsFactory.MAX_EFFECTS_PER_CLIP] (I7 — the ViewModel already
     * rejects a 4th; this is the defence-in-depth trim). intensity == 0
     * produces NO effect instance (identity — no GPU cost).
     */
    fun effectsFor(effects: List<ClipEffect>): List<Effect> {
        if (effects.isEmpty()) return emptyList()
        val out = mutableListOf<Effect>()
        for (clipEffect in effects.take(ClipEffectsFactory.MAX_EFFECTS_PER_CLIP)) {
            when (clipEffect.kind) {
                EffectKind.BLUR -> {
                    val sigma = ClipEffectMath.blurSigma(clipEffect.intensity)
                    if (sigma > 0f) {
                        out += GaussianBlurEffect(sigma, horizontal = true)
                        out += GaussianBlurEffect(sigma, horizontal = false)
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
 * One separable Gaussian blur pass (7-tap). [horizontal] picks the axis;
 * the pair (true, then false) is the full blur. [sigma] is the physical
 * Gaussian sigma (0 excluded upstream — identity effects are not installed).
 *
 * Test-facing pure API: [glslShaderString] (the exact fragment shader the
 * GL program compiles) and [applyToFrame] (the CPU reference of THIS pass).
 */
@UnstableApi
class GaussianBlurEffect(
    private val sigma: Float,
    private val horizontal: Boolean,
) : GlEffect {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = sigma <= 0f

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        BlurShaderProgram(sigma, horizontal, useHdr)

    /** The fragment shader source compiled for this effect (JVM-testable). */
    fun glslShaderString(): String = ClipEffectMath.blurFragmentShaderCode()

    /** CPU reference of this pass (JVM-testable behavioural spec). */
    fun applyToFrame(pixels: IntArray, width: Int, height: Int): IntArray =
        ClipEffectMath.applyBlurPass(pixels, width, height, sigma, horizontal)
}

@UnstableApi
internal class BlurShaderProgram(
    private val sigma: Float,
    private val horizontal: Boolean,
    useHdr: Boolean,
) : GlShaderProgramBase(ClipEffectMath.blurFragmentShaderCode(), useHdr) {

    private val weights = ClipEffectMath.blurWeights(sigma)

    override fun onConfigure(inputWidth: Int, inputHeight: Int) {
        glProgram.setFloatsUniform("u_Weights", weights)
        val step = if (horizontal) {
            floatArrayOf(1f / inputWidth, 0f)
        } else {
            floatArrayOf(0f, 1f / inputHeight)
        }
        glProgram.setFloatsUniform("u_TexelStep", step)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        drawQuad(inputTexId, presentationTimeUs)
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

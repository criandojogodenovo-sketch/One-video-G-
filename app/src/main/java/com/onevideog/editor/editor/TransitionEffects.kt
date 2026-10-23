package com.onevideog.editor.editor

import android.content.Context
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.onevideog.editor.data.Clip
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.TransitionKind

/**
 * v0.6.0 (Fase H) — the Media3 glue that renders transition windows as
 * per-clip effects. THE single transition renderer for BOTH pipelines: the
 * preview attaches these effects through `ExoPlayer.setVideoEffects` and the
 * export through `EditedMediaItem.setEffects`, both via
 * [ClipEffectsFactory.effectsFor] — the two pipelines run the SAME shader
 * string with the SAME uniforms, so the preview is the export plan by
 * construction.
 *
 * WHY per-clip effects and not the Media3 Transition API: Media3 1.4.1 has
 * NO Transition API — verified against the 1.4.1 sources (Composition.Builder
 * has no setTransitions; androidx.media3.transformer.Transition and
 * CrossFadeTransition do not exist; they arrive in later Media3 releases).
 * The user's plan anticipated `TransitionFactory.build(...) ->
 * ImmutableList<Transition>`; the same architecture is kept here with the
 * renderer-agnostic types we DO have:
 *   TransitionFactory  -> [TransitionPlanner] (pure plan) +
 *                         [TransitionEffects.effectsForClip] (GL glue)
 *   ImmutableList<Transition> -> List<Effect> appended per clip
 *
 * Rendering model (documented limitation, PREVIEW_EXPORT_PARITY.md):
 *  - the window of each transition is rendered on TWO clips: the OUT phase
 *    on the tail of the previous clip (window [plan.windowStartLocalMsOnA,
 *    clip end]) and the IN phase on the head of the next clip (window
 *    [0, durationMs]);
 *  - CROSSFADE = fade through black (linear); WIPE/SLIDE = directional push
 *    through black (see [TransitionShaders]) — a sequential 1.4.1
 *    composition has no second frame to blend, so a true two-stream blend
 *    is impossible; the total perceived window is out+in = 2×durationMs;
 *  - time base: item-local ms == project-local ms after SpeedChangeEffect
 *    (the verified ClipEffectsFactory contract), and the plan windows are
 *    already in that unit.
 */
@UnstableApi
object TransitionEffects {

    /** Phase constants mirrored into the shader's u_Phase uniform. */
    const val PHASE_OUT = 0
    const val PHASE_IN = 1

    /**
     * The transition effects attached to [clip]: one OUT effect when the
     * clip is the PREVIOUS clip of a plan (tail window) and one IN effect
     * when it is the NEXT clip (head window). A clip between two
     * transitions carries both. Empty when no valid transition touches the
     * clip — the common case (no GPU cost).
     *
     * v0.7.0 (K3): windows live on the clip's OUTPUT timeline (ramp-aware
     * via [SpeedRampPlanner.outputDurationMs]); for a ramped clip the export
     * splits it into segments and calls [effectsForClipSegment] with each
     * segment's output span — windows are SHIFTED (not truncated) into the
     * segment timebase, so the progress mapping is exact even when a
     * segment boundary cuts the window (windowStart may be negative: the
     * shader clamps progress, frames outside the window render identity).
     */
    fun effectsForClip(
        project: OvgProject,
        clip: Clip,
        clipStartMs: Long,
    ): List<Effect> = effectsForClipSegment(
        project,
        clip,
        segmentOutputStartMs = 0L,
        segmentOutputEndMs = SpeedRampPlanner.outputDurationMs(clip),
    )

    /**
     * The transition windows of [clip] rebased to ONE export segment (its
     * output span [segmentOutputStartMs, segmentOutputEndMs) in clip-local
     * output time). Windows entirely outside the segment produce no effect
     * instance (no GPU cost); inside, the window start is shifted by the
     * segment start (may be negative — exact progress mapping, see above).
     */
    fun effectsForClipSegment(
        project: OvgProject,
        clip: Clip,
        segmentOutputStartMs: Long,
        segmentOutputEndMs: Long,
    ): List<Effect> {
        if (project.transitions.isEmpty() || project.clips.isEmpty()) return emptyList()
        val plans = TransitionPlanner.plansFor(project.clips, project.transitions)
        if (plans.isEmpty()) return emptyList()
        val effects = mutableListOf<Effect>()
        for (plan in plans) {
            if (plan.clipA.id == clip.id) {
                val windowStart = plan.windowStartLocalMsOnA
                val windowEnd = windowStart + plan.durationMs
                if (windowEnd > segmentOutputStartMs && windowStart < segmentOutputEndMs) {
                    effects += TransitionEffect(
                        kind = plan.transition.kind,
                        phase = PHASE_OUT,
                        windowStartLocalMs = windowStart - segmentOutputStartMs,
                        durationMs = plan.durationMs,
                    )
                }
            }
            if (plan.clipB.id == clip.id) {
                val windowEnd = plan.durationMs
                if (windowEnd > segmentOutputStartMs && segmentOutputStartMs < segmentOutputEndMs) {
                    effects += TransitionEffect(
                        kind = plan.transition.kind,
                        phase = PHASE_IN,
                        windowStartLocalMs = 0L - segmentOutputStartMs,
                        durationMs = plan.durationMs,
                    )
                }
            }
        }
        return effects
    }
}

/**
 * One transition window on one clip. [windowStartLocalMs] is the item-local
 * ms where the window begins (tail offset for OUT, 0 for IN); [durationMs]
 * the window length. Frames outside the window render IDENTITY (progress
 * clamps: before the window p=0 = identity on OUT; after the window p=1 =
 * identity on IN — the effect list never disturbs non-transition frames).
 */
@UnstableApi
class TransitionEffect(
    private val kind: TransitionKind,
    private val phase: Int,
    private val windowStartLocalMs: Long,
    private val durationMs: Long,
) : GlEffect {

    /** The pure behavioural spec for the JVM tests (delegates to math). */
    fun glslShaderString(): String = TransitionShaders.fragmentShaderCode()

    fun applyToFrame(
        pixels: IntArray,
        width: Int,
        height: Int,
        progress: Float,
    ): IntArray = TransitionShaders.applyToFrame(pixels, width, height, kind, phase, progress)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        TransitionShaderProgram(kind, phase, windowStartLocalMs, durationMs, useHdr)
}

@UnstableApi
internal class TransitionShaderProgram(
    private val kind: TransitionKind,
    private val phase: Int,
    private val windowStartLocalMs: Long,
    private val durationMs: Long,
    useHdr: Boolean,
) : GlShaderProgramBase(TransitionShaders.fragmentShaderCode(), useHdr) {

    override fun onConfigure(inputWidth: Int, inputHeight: Int) {
        val dir = TransitionShaders.dirTex(kind)
        glProgram.setFloatsUniform(
            TransitionShaders.UNIFORM_DIR,
            floatArrayOf(dir.first, dir.second),
        )
        glProgram.setFloatUniform(
            TransitionShaders.UNIFORM_MODE,
            if (kind == TransitionKind.CROSSFADE) 0f else 1f,
        )
        glProgram.setFloatUniform(
            TransitionShaders.UNIFORM_PHASE,
            if (phase == TransitionEffects.PHASE_OUT) 0f else 1f,
        )
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        // Item-local ms == project-local ms after SpeedChangeEffect (the
        // verified ClipEffectsFactory time-base contract).
        val localMs = presentationTimeUs / 1000f
        val progress = if (durationMs <= 0L) {
            1f
        } else {
            ((localMs - windowStartLocalMs) / durationMs).coerceIn(0f, 1f)
        }
        glProgram.setFloatUniform(TransitionShaders.UNIFORM_PROGRESS, progress)
        drawQuad(inputTexId, presentationTimeUs)
    }
}

package com.onevideog.editor.editor

import com.onevideog.editor.data.TransitionKind

/**
 * v0.6.0 (Fase H) — pure transition shader math: the GLSL source rendered by
 * BOTH pipelines (preview ExoPlayer effects and export Transformer effects —
 * the same string, the same uniforms) and a CPU reference implementation the
 * JVM tests use as the behavioural spec.
 *
 * Rendering model (documented limitation — Media3 1.4.1 has no Transition
 * API and a sequential composition has no second frame to blend):
 *  - CROSSFADE renders as a fade THROUGH BLACK: the tail of the previous
 *    clip fades out linearly over the window, the head of the next clip
 *    fades in linearly over the window.
 *  - WIPE/SLIDE render as a directional PUSH THROUGH BLACK: the previous
 *    frame slides out in the kind's direction (black fills behind it), the
 *    next frame slides in along the SAME direction.
 * The total perceived window is out+in = 2×durationMs (no overlap possible
 * on a sequential single-frame pipeline).
 *
 * Space conventions:
 *  - [applyToFrame] is the SCREEN-space spec (y grows DOWNWARD); directions
 *    are unit vectors: LEFT (-1,0), RIGHT (1,0), UP (0,-1), DOWN (0,1).
 *  - the GLSL works in texture space (v grows UPWARD in the Media3 GL
 *    pipeline), so the Kotlin glue flips the y component when it fills
 *    u_Dir (see [dirTex]).
 */
object TransitionShaders {

    /** Screen-space unit direction (y DOWN) — the behavioural spec. */
    fun dirScreen(kind: TransitionKind): Pair<Float, Float> = when (kind) {
        TransitionKind.WIPE_LEFT -> -1f to 0f
        TransitionKind.WIPE_RIGHT -> 1f to 0f
        TransitionKind.SLIDE_UP -> 0f to -1f
        TransitionKind.SLIDE_DOWN -> 0f to 1f
        TransitionKind.CROSSFADE -> 0f to 0f
    }

    /**
     * Texture-space direction for u_Dir (v UP in the Media3 GL pipeline —
     * the y flip of [dirScreen]). A signed zero from the flip is
     * normalized to +0.0 so the value equals the documented spec pair.
     */
    fun dirTex(kind: TransitionKind): Pair<Float, Float> {
        val (dx, dy) = dirScreen(kind)
        val flipped = -dy
        return dx to if (flipped == 0f) 0f else flipped
    }

    /**
     * The ONE fragment shader for every kind (Media3 1.4.1 GL conventions:
     * samples uTexSampler at vTexSamplingCoord — the varying the shared
     * Media3 vertex shader [com.onevideog.editor.editor.GlShaderProgramBase]
     * fills). Uniforms (set per frame by the Media3 glue from the
     * TransitionPlan):
     *  - u_Progress: 0..1 inside the current window (tail OR head);
     *  - u_Phase: 0 = tail/out (previous clip), 1 = head/in (next clip);
     *  - u_Mode: 0 = crossfade (fade through black), 1 = directional push;
     *  - u_Dir: texture-space unit direction (u_Mode 1 only).
     */
    fun fragmentShaderCode(): String = """
#version 100
precision mediump float;
uniform sampler2D uTexSampler;
varying vec2 vTexSamplingCoord;
uniform float u_Progress;
uniform float u_Phase;
uniform float u_Mode;
uniform vec2 u_Dir;

void main() {
  float p = clamp(u_Progress, 0.0, 1.0);
  vec4 c = texture2D(uTexSampler, vTexSamplingCoord);
  if (u_Mode > 0.5) {
    // Directional push through black: the out phase moves the frame along
    // -u_Dir (content exits in the kind's direction), the in phase moves it
    // along +u_Dir * (1 - p) (content enters moving the same direction).
    float travel = u_Phase > 0.5 ? (1.0 - p) : p;
    float phaseSign = u_Phase > 0.5 ? 1.0 : -1.0;
    vec2 uv = vTexSamplingCoord + u_Dir * travel * phaseSign;
    bool inside = uv.x >= 0.0 && uv.x <= 1.0 && uv.y >= 0.0 && uv.y <= 1.0;
    vec3 rgb = inside ? texture2D(uTexSampler, uv).rgb : vec3(0.0);
    gl_FragColor = vec4(rgb, c.a);
  } else {
    // Fade through black: tail fades out (1 - p), head fades in (p).
    float k = u_Phase > 0.5 ? p : (1.0 - p);
    gl_FragColor = vec4(c.rgb * k, c.a);
  }
}
""".trim()

    /** Uniform names filled by the Media3 glue (kept in ONE place). */
    const val UNIFORM_PROGRESS = "u_Progress"
    const val UNIFORM_PHASE = "u_Phase"
    const val UNIFORM_MODE = "u_Mode"
    const val UNIFORM_DIR = "u_Dir"

    /**
     * CPU reference implementation (the behavioural spec the JVM tests
     * assert against). ARGB pixels in/out; [progress] clamped to 0..1;
     * [phase] 0 = tail/out, 1 = head/in. Identity at progress 0, full
     * transition at progress 1.
     */
    fun applyToFrame(
        pixels: IntArray,
        width: Int,
        height: Int,
        kind: TransitionKind,
        phase: Int,
        progress: Float,
    ): IntArray {
        require(pixels.size == width * height) { "pixel buffer must match w*h" }
        val p = progress.coerceIn(0f, 1f)
        val out = IntArray(pixels.size)
        if (kind == TransitionKind.CROSSFADE) {
            val k = if (phase != 0) p else 1f - p
            for (i in pixels.indices) {
                val c = pixels[i]
                val a = (c ushr 24) and 0xFF
                val r = ((c ushr 16) and 0xFF) * k
                val g = ((c ushr 8) and 0xFF) * k
                val b = (c and 0xFF) * k
                out[i] = (a shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
            }
            return out
        }
        val (dx, dy) = dirScreen(kind)
        // Content displacement O (px): the tail pushes the content ALONG +D
        // (it exits in the kind's direction); the head enters from the -D
        // side, so its displacement is -D scaled by (1 - p). rendered(x, y)
        // = source(x - Ox, y - Oy).
        val travel = if (phase != 0) 1f - p else p
        val sign = if (phase != 0) -1f else 1f
        val ox = dx * travel * sign * width
        val oy = dy * travel * sign * height
        for (y in 0 until height) {
            for (x in 0 until width) {
                val sx = (x - ox).toInt()
                val sy = (y - oy).toInt()
                val index = y * width + x
                out[index] = if (sx in 0 until width && sy in 0 until height) {
                    pixels[sy * width + sx]
                } else {
                    0 // black (alpha 0 keeps the RGB 0 too)
                }
            }
        }
        return out
    }
}

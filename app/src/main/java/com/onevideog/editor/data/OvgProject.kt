package com.onevideog.editor.data

import com.onevideog.editor.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToLong

/** Aspect ratio options for a project. */
enum class ProjectAspect(val labelRes: Int) {
    SOURCE(R.string.aspect_source),
    R16_9(R.string.aspect_16_9),
    R9_16(R.string.aspect_9_16),
    R1_1(R.string.aspect_1_1),
    R4_3(R.string.aspect_4_3),
    R3_4(R.string.aspect_3_4);

    /** width / height, or null when the source aspect is kept. */
    fun ratio(): Float? = when (this) {
        SOURCE -> null
        R16_9 -> 16f / 9f
        R9_16 -> 9f / 16f
        R1_1 -> 1f
        R4_3 -> 4f / 3f
        R3_4 -> 3f / 4f
    }
}

/**
 * v0.6.0 (Fase I): per-clip 2D effects, rendered by the shared GLSL effect
 * pipeline (preview AND export — same [com.onevideog.editor.editor.ClipEffectsFactory]
 * list, same shaders).
 *
 * [intensity] is normalized 0..1; each effect maps it to its own physical
 * parameter (blur sigma, vignette depth, sharpen amount) in the pure math
 * objects under com.onevideog.editor.editor.
 */
enum class EffectKind { BLUR, VIGNETTE, SHARPEN }

@Serializable
data class ClipEffect(
    val kind: EffectKind,
    // 0..1 (0 = effect off, 1 = maximum); coerced on use.
    val intensity: Float = 0.5f,
)

/** Basic colour adjustments, applied to the video (preview and export). */
@Serializable
data class ColorAdjustments(
    // -1..1 range (negative darkens, positive brightens)
    val brightness: Float = 0f,
    // 1 = unchanged; 0.5..2 supported
    val contrast: Float = 1f,
    // 0..2 (0 = grey, 1 = unchanged, 2 = strong)
    val saturation: Float = 1f,
) {
    val isDefault: Boolean get() = brightness == 0f && contrast == 1f && saturation == 1f
}

// ---------------------------------------------------------------------------
// Schema v4 (v0.7.0, Fase J): per-clip advanced colour grading — tonal curves
// (Photoshop/Lightroom-style control points per channel). Applied through the
// shared GLSL pipeline (ColorCurvesEffect, one GlEffect for Master+R+G+B) by
// BOTH pipelines via ClipEffectsFactory.effectsFor — same shader string, same
// LUTs, preview/export parity by construction. Validation and the monotonic
// cubic spline live in com.onevideog.editor.editor.CurveMath (pure, JVM).
// ---------------------------------------------------------------------------

/** One curve control point; x and y are normalized 0..1 (x = input, y = output). */
@Serializable
data class CurvePoint(
    val x: Float,
    val y: Float,
)

/**
 * A tonal curve: control points mapped to a 256-entry LUT by
 * [com.onevideog.editor.editor.CurveMath.buildLut] (Fritsch–Carlson monotonic
 * cubic spline — no oscillation/overshoot between points).
 */
@Serializable
data class Curve(
    val points: List<CurvePoint>,
)

/** The four tonal curves of one clip, applied in order Master → R → G → B. */
@Serializable
data class ColorCurves(
    val master: Curve,
    val red: Curve,
    val green: Curve,
    val blue: Curve,
)

// ---------------------------------------------------------------------------
// Schema v4 (v0.7.0, Fase K): per-clip speed ramps (variable speed).
// Planned by com.onevideog.editor.editor.SpeedRampPlanner (pure): the ramp
// lives on the constant-speed basis [0, effectiveDurationMs]; between points
// the factor interpolates linearly and the planner quantizes it into at most
// 16 constant-speed segments (export = one EditedMediaItem per segment).
// ---------------------------------------------------------------------------

/**
 * One speed-ramp control point. [localTimeMs] is the clip-local time on the
 * CONSTANT-SPEED basis (0..effectiveDurationMs); [factor] is 0.1..4.0
 * (0.5 = half speed, 2.0 = double speed).
 */
@Serializable
data class SpeedPoint(
    val localTimeMs: Long,
    val factor: Float,
)

/** One video clip on the timeline. Source times are in milliseconds of the source file. */
@Serializable
data class Clip(
    val id: String,
    val uri: String,
    val displayName: String,
    val sourceDurationMs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val trimStartMs: Long = 0,
    val trimEndMs: Long = 0,
    val volume: Float = 1f,
    val speed: Float = 1f,
    val color: ColorAdjustments = ColorAdjustments(),
    // v0.6.0 (Fase I): per-clip 2D effects. The default keeps every
    // pre-v0.6.0 document byte-compatible (decodes to an empty list).
    val effects: List<ClipEffect> = emptyList(),
    // v0.7.0 (Fase J): advanced colour curves. The default (null) keeps every
    // pre-v0.7.0 document byte-compatible: no curves, no LUT effect, the
    // v3 behaviour exactly.
    val curves: ColorCurves? = null,
    // v0.7.0 (Fase K): speed ramps. The default (empty) keeps every
    // pre-v0.7.0 document byte-compatible: constant [speed], unchanged
    // effectiveDurationMs.
    val speedRamps: List<SpeedPoint> = emptyList(),
) {
    val effectiveDurationMs: Long
        get() = ((trimEndMs - trimStartMs).coerceAtLeast(0) / speed).roundToLong()

    fun withTrim(start: Long, end: Long): Clip =
        copy(
            trimStartMs = start.coerceIn(0, sourceDurationMs),
            trimEndMs = end.coerceIn(0, sourceDurationMs),
        )

    /** Source aspect (width / height), guarded against unknown dimensions. */
    val sourceAspect: Float?
        get() = if (width > 0 && height > 0) width.toFloat() / height else null
}

// ---------------------------------------------------------------------------
// Schema v3 (v0.6.0, Fase H): transitions between adjacent video clips.
//
// Media3 1.4.1 has NO Transition API (verified against the 1.4.1 sources:
// Composition.Builder has no setTransitions; Transition/CrossFadeTransition
// do not exist yet — they arrive in a later Media3). Transitions are
// therefore rendered as per-clip GLSL effects built by the SAME shared
// pipeline the 2D effects use (ClipEffectsFactory.effectsFor), which the
// preview applies through ExoPlayer.setVideoEffects and the export through
// EditedMediaItem.setEffects — preview and export render the identical
// shader by construction.
// ---------------------------------------------------------------------------

/** Kinds of transition rendered on the window between two adjacent clips. */
enum class TransitionKind { CROSSFADE, WIPE_LEFT, WIPE_RIGHT, SLIDE_UP, SLIDE_DOWN }

/**
 * A transition between two ADJACENT video clips ([betweenClipIds].first is
 * the earlier clip, .second the later one — adjacency is re-validated by
 * [com.onevideog.editor.editor.TransitionPlanner] whenever the sequence
 * changes; invalid transitions are dropped, never allowed to break export).
 */
@Serializable
data class ClipTransition(
    val id: String,
    val betweenClipIds: Pair<String, String>,
    val kind: TransitionKind = TransitionKind.CROSSFADE,
    // Total window length on the project timeline, clamped by
    // TransitionPlanner to 200..1000 ms and to <= 50% of BOTH clips.
    val durationMs: Long = 500L,
)

// ---------------------------------------------------------------------------
// Schema v2: unified overlays (text/image) with free transforms + keyframes,
// and independent audio clips. Fields below are shared by preview and export
// through the pure math in com.onevideog.editor.editor (OverlayLayout,
// KeyframeEvaluator, AudioTimeline).
// ---------------------------------------------------------------------------

/** What an overlay renders. */
enum class OverlayKind { TEXT, IMAGE }

/** Keyframe interpolation curve between neighbouring keyframes. */
enum class Interpolation { LINEAR, EASE_IN_OUT }

/** System font families offered for text overlays (mapped via android.graphics.Typeface). */
enum class TextFontFamily(val id: String) {
    SANS("sans"), SERIF("serif"), MONO("monospace"), CONDENSED("sans-serif-condensed");

    companion object {
        fun fromId(id: String?): TextFontFamily =
            entries.firstOrNull { it.id == id } ?: SANS
    }
}

/**
 * Free transform of an overlay, in normalised content coordinates:
 * - x, y: overlay centre, 0..1 across/height down of the video content area
 * - scale: overlay width as a fraction of the content width
 * - rotationDeg: clockwise-positive degrees (preview convention; export negates)
 * - opacity: 0..1
 */
@Serializable
data class OverlayTransform(
    val x: Float = 0.5f,
    val y: Float = 0.8f,
    val scale: Float = 0.6f,
    val rotationDeg: Float = 0f,
    val opacity: Float = 1f,
) {
    /**
     * v0.4.0 (E1 validation): non-finite values (NaN / ±Inf) fall back to the
     * field default instead of poisoning the model — `coerceIn` passes NaN
     * through unchanged (all comparisons against NaN are false), so a single
     * bad value would disable every downstream clamp. Finite values keep
     * their existing clamp behaviour; rotation is normalized for finiteness
     * only (NOT wrapped to ±180 — wrapping would corrupt intentional
     * multi-turn keyframe interpolation).
     */
    fun withClampedValues(): OverlayTransform = copy(
        x = x.finiteOrElse(0.5f).coerceIn(0f, 1f),
        y = y.finiteOrElse(0.8f).coerceIn(0f, 1f),
        scale = scale.finiteOrElse(0.6f).coerceIn(0.05f, 1.6f),
        opacity = opacity.finiteOrElse(1f).coerceIn(0f, 1f),
        rotationDeg = rotationDeg.finiteOrElse(0f),
    )

    /** True when any component is non-finite (NaN or ±Inf). */
    val hasNonFinite: Boolean
        get() = !(x.isFinite() && y.isFinite() && scale.isFinite() &&
            opacity.isFinite() && rotationDeg.isFinite())

    private fun Float.finiteOrElse(fallback: Float): Float =
        if (isFinite()) this else fallback
}

/**
 * A keyframe: property values captured at an absolute project time.
 * Null properties inherit the evaluated base/previous value.
 */
@Serializable
data class Keyframe(
    val timeMs: Long,
    val x: Float? = null,
    val y: Float? = null,
    val scale: Float? = null,
    val rotationDeg: Float? = null,
    val opacity: Float? = null,
)

/**
 * Per-word / per-letter text entrance animation (v0.5.0, Fase G).
 *
 * The animation is DECLARATIVE: the preset is expanded into per-segment
 * keyframes by the shared pure planners
 * ([com.onevideog.editor.editor.TextSegmentPlanner] +
 * [com.onevideog.editor.editor.TextAnimationPlanner]) inside
 * [com.onevideog.editor.editor.ExpandedOverlays] — one expansion used by
 * BOTH the preview canvas and the export composition (parity contract).
 *
 * Defaults keep every pre-v0.5.0 schema-v2 document readable and behaving
 * exactly as before: [preset] NONE means "no expansion, single bitmap".
 */
@Serializable
data class TextAnimation(
    val preset: TextAnimPreset = TextAnimPreset.NONE,
    val unit: TextAnimUnit = TextAnimUnit.WORD,
    // Entrance duration per segment, clamped by the planner to 100..5000 ms.
    val durationMs: Long = 600,
) {
    val isEnabled: Boolean get() = preset != TextAnimPreset.NONE
}

/** Entrance animation presets (v0.5.0, Fase G). */
enum class TextAnimPreset { NONE, FADE, SLIDE_UP, POP, TYPEWRITER }

/** Segmentation unit for text animation: whole words or single letters. */
enum class TextAnimUnit { WORD, LETTER }

// ---------------------------------------------------------------------------
// Schema v6 (v0.9.0, Fase L — Opção A "2.5D bake-to-texture" do estudo de
// viabilidade aprovado): overlay styles (baked shadow/glow), per-overlay
// depth plane and a project-level virtual camera. ALL of it is ADDITIVE:
// defaults decode to "no style, depth 0, identity camera" so every pre-v0.9.0
// document loads byte-compatibly and renders EXACTLY as before. The bake is
// pure JVM math (com.onevideog.editor.editor.OverlayStyleBake, D3) and the
// camera math is pure (com.onevideog.editor.editor.CameraMath) — preview and
// export consume the SAME functions (parity by construction).
// ---------------------------------------------------------------------------

/**
 * Baked shadow under the overlay content (v0.9.0, L1). All geometry fields
 * are FRACTIONS of the source bitmap's height, so the look is independent of
 * the bitmap's resolution (the canonical text block is 1080 px high; images
 * up to 1024). Baked by [com.onevideog.editor.editor.OverlayStyleBake] — a
 * deterministic CPU pass, no GPU, no shader (Opção A rule).
 */
@Serializable
data class StyleShadow(
    // + = right / + = down, fractions of the source bitmap height.
    val offsetX: Float = 0.006f,
    val offsetY: Float = 0.010f,
    // Gaussian sigma as a fraction of the source bitmap height (0 = hard).
    val blur: Float = 0.016f,
    val colorArgb: Int = 0xFF000000.toInt(),
    // 0..1 multiplier on the blurred alpha.
    val opacity: Float = 0.55f,
) {
    fun sanitized(): StyleShadow = copy(
        offsetX = offsetX.finiteOrElse(0.006f).coerceIn(-0.25f, 0.25f),
        offsetY = offsetY.finiteOrElse(0.010f).coerceIn(-0.25f, 0.25f),
        blur = blur.finiteOrElse(0.016f).coerceIn(0f, 0.15f),
        opacity = opacity.finiteOrElse(0.55f).coerceIn(0f, 1f),
    )

    private fun Float.finiteOrElse(fallback: Float): Float =
        if (isFinite()) this else fallback
}

/**
 * Baked outer glow around the overlay content (v0.9.0, L1). Same fraction
 * convention as [StyleShadow].
 */
@Serializable
data class StyleGlow(
    // Gaussian sigma of the glow falloff, fraction of the source bitmap height.
    val radius: Float = 0.022f,
    val colorArgb: Int = 0xFF00E5FF.toInt(),
    // 0..1 multiplier on the blurred alpha.
    val strength: Float = 0.85f,
) {
    fun sanitized(): StyleGlow = copy(
        radius = radius.finiteOrElse(0.022f).coerceIn(0f, 0.15f),
        strength = strength.finiteOrElse(0.85f).coerceIn(0f, 1f),
    )

    private fun Float.finiteOrElse(fallback: Float): Float =
        if (isFinite()) this else fallback
}

/**
 * A text/image overlay style: baked shadow + glow (v0.9.0, L1). Null on the
 * owning overlay means "no style" — the render path is EXACTLY the pre-v0.9.0
 * one. When present, the overlay renders from ONE baked bitmap consumed by
 * BOTH pipelines (preview Image + export BitmapOverlay — the same cache
 * instance, parity by construction; decision D1).
 */
@Serializable
data class OverlayStyle(
    val shadow: StyleShadow? = null,
    val glow: StyleGlow? = null,
) {
    val isDefault: Boolean get() = shadow == null && glow == null

    fun sanitized(): OverlayStyle = copy(
        shadow = shadow?.sanitized(),
        glow = glow?.sanitized(),
    )
}

/**
 * v0.9.0 (L3): the "Estilo" panel presets — deterministic parameter sets of
 * [OverlayStyle]. Data-class equality makes the chip selection state a plain
 * comparison against the stored style.
 */
object OverlayStylePresets {
    /** "Drop suave": small soft black shadow below the content. */
    val SOFT = OverlayStyle(
        shadow = StyleShadow(
            offsetX = 0.006f, offsetY = 0.010f, blur = 0.016f,
            colorArgb = 0xFF000000.toInt(), opacity = 0.55f,
        ),
    )

    /** "Dura": hard offset shadow, no blur (sticker look). */
    val HARD = OverlayStyle(
        shadow = StyleShadow(
            offsetX = 0.014f, offsetY = 0.014f, blur = 0f,
            colorArgb = 0xFF000000.toInt(), opacity = 0.9f,
        ),
    )

    /** "Glow neon": cyan outer glow, no shadow. */
    val NEON = OverlayStyle(
        glow = StyleGlow(radius = 0.022f, colorArgb = 0xFF00E5FF.toInt(), strength = 0.9f),
    )
}

/**
 * One camera control point (v0.9.0, L2). [timeMs] is absolute project time;
 * values interpolate with the project camera's [Interpolation].
 */
@Serializable
data class CameraKeyframe(
    val timeMs: Long,
    val panX: Float,
    val panY: Float,
    val zoom: Float,
)

/**
 * The project's virtual 2.5D camera (v0.9.0, L2): a pan/zoom applied to the
 * video plane (via a Media3 1.4.1 MatrixTransformation — zero new shaders)
 * and, with depth-proportional strength, to the overlays (parallax — pure
 * [com.onevideog.editor.editor.CameraMath] shared by BOTH pipelines).
 * panX/panY are fractions of the content size (+ = content moves right/down);
 * zoom 1 = unchanged. The default instance is the identity camera: zero
 * effect instances, zero cost, byte-compatible with every older document.
 */
@Serializable
data class CameraState(
    val panX: Float = 0f,
    val panY: Float = 0f,
    val zoom: Float = 1f,
    val keyframes: List<CameraKeyframe> = emptyList(),
    val interpolation: Interpolation = Interpolation.LINEAR,
) {
    val isDefault: Boolean
        get() = panX == 0f && panY == 0f && zoom == 1f && keyframes.isEmpty()
}

/**
 * A timed overlay (text or image) on the project timeline.
 *
 * All geometry lives in [transform] (content-space). Text styling is only
 * meaningful when kind == TEXT; [uri] only when kind == IMAGE. Keyframes are
 * evaluated by [com.onevideog.editor.editor.KeyframeEvaluator] for both the
 * preview canvas and the export composition.
 */
@Serializable
data class OverlayItem(
    val id: String,
    val kind: OverlayKind,
    val startMs: Long = 0,
    val endMs: Long = 0,
    val transform: OverlayTransform = OverlayTransform(),
    val zOrder: Int = 0,
    // v0.3.0 layer state: hidden layers render nothing (preview AND export);
    // locked layers stay visible but reject every editing gesture. Defaults
    // keep v1/v2 documents loadable unchanged.
    val visible: Boolean = true,
    val locked: Boolean = false,
    // v0.5.0 per-word/per-letter entrance animation (TEXT overlays). The
    // default (preset NONE) keeps older documents byte-compatible: the field
    // decodes to NONE and the overlay renders as the single bitmap it always
    // was.
    val textAnimation: TextAnimation = TextAnimation(),
    val keyframes: List<Keyframe> = emptyList(),
    val interpolation: Interpolation = Interpolation.LINEAR,
    // ---- text ----
    val text: String = "",
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val fontFamily: String = TextFontFamily.SANS.id,
    val bold: Boolean = true,
    val italic: Boolean = false,
    // text height as a fraction of content height
    val sizeFrac: Float = 0.07f,
    // background "pill" behind the text; alpha 0 disables it
    val bgArgb: Int = 0x00000000,
    val bgPaddingFrac: Float = 0.012f,
    val bgRadiusFrac: Float = 0.008f,
    // ---- image ----
    val uri: String = "",
    // v0.9.0 (schema v6, Fase L2): depth plane — -1 (deepest background) ..
    // +1 (closest foreground); 0 = the video plane (default: every pre-v0.9.0
    // overlay). Draw-order rule (D2): zDepth ASC defines the draw order when
    // it differs (deeper drawn first); [zOrder] is the tie-break and stays
    // what bringToFront/sendToBack manipulate. Coerced at use time by
    // [com.onevideog.editor.editor.CameraMath.depthMultiplier].
    val zDepth: Float = 0f,
    // v0.9.0 (schema v6, Fase L1): baked shadow/glow style; null = no style
    // (the exact pre-v0.9.0 render path). When set, BOTH pipelines render
    // this overlay from the SAME baked bitmap (decision D1).
    val style: OverlayStyle? = null,
) {
    val isText: Boolean get() = kind == OverlayKind.TEXT
}

// ---------------------------------------------------------------------------
// Schema v6 (v0.9.0, Fase L): the project-level camera lives here ([camera]);
// overlay-level depth and styles live on [OverlayItem]. All defaults are the
// identity — v5 documents decode unchanged.
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Schema v5 (v0.8.0, Fases P+Q): audio gain automation + treatment.
// Per-clip fades, volume keyframes and a noise gate. ALL gain math lives in
// ONE pure object (com.onevideog.editor.editor.AudioGainMath — Q1) with TWO
// consumers: the preview (GainAutomationAudioProcessor injected into each
// audio ExoPlayer's DefaultAudioSink) and the export (the same processor
// class in every audio EditedMediaItem's Effects) — preview↔export gain
// parity BY CONSTRUCTION. Lanes are display-only (AudioLanePacker, computed
// at render time, never stored).
// ---------------------------------------------------------------------------

/**
 * One audio volume-automation control point (v0.8.0, Q3). [timeMs] is
 * clip-local (0 = the first sample AFTER the trim start); [volume] is a
 * multiplier 0..2 (1.0 = the clip's own [AudioClipItem.volume] unchanged).
 */
@Serializable
data class AudioKeyframe(
    val timeMs: Long,
    val volume: Float,
)

/**
 * An independent audio clip placed at an absolute project time.
 * Audio clips mix with the video's own audio and with each other.
 */
@Serializable
data class AudioClipItem(
    val id: String,
    val uri: String,
    val displayName: String,
    val sourceDurationMs: Long,
    // absolute position on the project timeline
    val startMs: Long = 0,
    val trimStartMs: Long = 0,
    val trimEndMs: Long = 0,
    val volume: Float = 1f,
    // legacy background-music behaviour: repeat until the project ends
    val loop: Boolean = false,
    // v0.8.0 (schema v5, P3): linear gain ramps at the clip edges, in
    // clip-local ms (0 = after trim). The defaults keep every pre-v0.8.0
    // document byte-compatible: no fades, constant [volume] — exactly the
    // v4 behaviour. On looping clips each loop segment restarts the envelope
    // (documented in PREVIEW_EXPORT_PARITY.md).
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    // v0.8.0 (schema v5, Q3): volume automation points (clip-local time,
    // multiplier 0..2). The default (empty) keeps the constant [volume].
    val gainKeyframes: List<AudioKeyframe> = emptyList(),
    // v0.8.0 (schema v5, Q4): simple time-domain noise gate (NOT ML/spectral
    // — documented honest scoping like K's "no pitch adjust").
    val noiseGate: Boolean = false,
) {
    val trimmedDurationMs: Long
        get() = (trimEndMs - trimStartMs).coerceAtLeast(0)

    val endMs: Long get() = startMs + trimmedDurationMs

    /** True when any gain automation is configured (fades/keyframes/gate). */
    val hasGainAutomation: Boolean
        get() = fadeInMs > 0 || fadeOutMs > 0 || gainKeyframes.isNotEmpty() || noiseGate
}

// ---------------------------------------------------------------------------
// Legacy schema v1 documents (textOverlays / imageOverlays / music) remain
// deserialisable below; [migrated] converts them to the v2 fields. New saves
// no longer carry legacy data.
// ---------------------------------------------------------------------------

/** Where an overlay sits vertically on the frame (schema v1). */
enum class OverlayPosition { TOP, CENTER, BOTTOM }

/** A text overlay, timed on the project timeline (ms). (schema v1) */
@Serializable
data class TextOverlayItem(
    val id: String,
    val text: String,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val position: String = OverlayPosition.BOTTOM.name,
    val startMs: Long = 0,
    val endMs: Long = 0,
)

/** An image overlay, timed on the project timeline (ms). (schema v1) */
@Serializable
data class ImageOverlayItem(
    val id: String,
    val uri: String,
    val position: String = OverlayPosition.BOTTOM.name,
    // fraction of the video width occupied by the image (0.1..1)
    val scale: Float = 0.4f,
    val startMs: Long = 0,
    val endMs: Long = 0,
)

/** An optional background music track. (schema v1) */
@Serializable
data class MusicTrack(
    val uri: String,
    val displayName: String,
    val durationMs: Long,
    val volume: Float = 0.8f,
)

/**
 * Versioned project document, serialized to JSON at
 * files/projects/<id>/project.json. Immutable: edits produce new instances
 * (which makes undo/redo a plain snapshot stack).
 */
@Serializable
data class OvgProject(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val aspect: String = ProjectAspect.SOURCE.name,
    val clips: List<Clip> = emptyList(),
    // schema v2
    val overlays: List<OverlayItem> = emptyList(),
    val audioClips: List<AudioClipItem> = emptyList(),
    // schema v3 (v0.6.0): transitions between adjacent video clips. The
    // default keeps every v2 document readable unchanged (empty list —
    // "no transitions", exactly the v2 behaviour).
    val transitions: List<ClipTransition> = emptyList(),
    // schema v6 (v0.9.0, Fase L2): the project virtual camera (pan/zoom +
    // optional keyframes). The default (identity) keeps every pre-v0.9.0
    // document byte-compatible: no camera effect instances, no parallax —
    // exactly the v5 behaviour.
    val camera: CameraState = CameraState(),
    // schema v1 (read for compatibility, emptied on write after migration)
    val textOverlays: List<TextOverlayItem> = emptyList(),
    val imageOverlays: List<ImageOverlayItem> = emptyList(),
    val music: MusicTrack? = null,
) {
    val aspectEnum: ProjectAspect
        get() = runCatching { ProjectAspect.valueOf(aspect) }.getOrDefault(ProjectAspect.SOURCE)

    fun withAspect(value: ProjectAspect): OvgProject = copy(aspect = value.name)

    /**
     * Returns the project with legacy v1 fields converted to the v2 model
     * (idempotent). Called after every load so the rest of the app only ever
     * sees the unified model.
     */
    fun migrated(): OvgProject {
        val needsOverlayMigration = textOverlays.isNotEmpty() || imageOverlays.isNotEmpty()
        val needsAudioMigration = music != null
        if (!needsOverlayMigration && !needsAudioMigration) return this

        var z = overlays.maxOfOrNull { it.zOrder } ?: 0
        val migratedOverlays = mutableListOf<OverlayItem>()
        migratedOverlays.addAll(overlays)
        for (legacy in textOverlays) {
            migratedOverlays += OverlayItem(
                id = legacy.id,
                kind = OverlayKind.TEXT,
                startMs = legacy.startMs,
                endMs = legacy.endMs,
                transform = OverlayTransform(
                    x = 0.5f,
                    y = legacyY(legacy.position),
                    scale = 0.9f,
                ),
                zOrder = ++z,
                text = legacy.text,
                colorArgb = legacy.colorArgb,
            )
        }
        for (legacy in imageOverlays) {
            migratedOverlays += OverlayItem(
                id = legacy.id,
                kind = OverlayKind.IMAGE,
                startMs = legacy.startMs,
                endMs = legacy.endMs,
                transform = OverlayTransform(
                    x = 0.5f,
                    y = legacyY(legacy.position),
                    scale = legacy.scale.coerceIn(0.1f, 1f),
                ),
                zOrder = ++z,
                uri = legacy.uri,
            )
        }
        val migratedAudio = audioClips.toMutableList()
        music?.let { m ->
            migratedAudio += AudioClipItem(
                // v0.5.1: a fresh UUID instead of the old derived
                // "music_" + uri.hashCode() fixed id — every clip in the
                // project must have a unique random identity. Safe for
                // migration idempotency: the legacy fields are cleared in
                // the same pass, so this runs at most once per document.
                id = java.util.UUID.randomUUID().toString(),
                uri = m.uri,
                displayName = m.displayName,
                sourceDurationMs = m.durationMs,
                startMs = 0,
                trimStartMs = 0,
                trimEndMs = m.durationMs,
                volume = m.volume,
                loop = true,
            )
        }
        return copy(
            overlays = migratedOverlays,
            audioClips = migratedAudio,
            textOverlays = emptyList(),
            imageOverlays = emptyList(),
            music = null,
        )
    }

    companion object {
        // v0.9.0 (schema v6, Fase L): overlay styles + zDepth + project
        // camera. v5/v4/v3/v2 documents decode with style = null, zDepth = 0
        // and the identity camera — the exact pre-v0.9.0 behaviour
        // (byte-compatible migration, no field rewriting needed).
        const val SCHEMA_VERSION = 6

        private fun legacyY(position: String): Float = when (
            runCatching { OverlayPosition.valueOf(position) }.getOrDefault(OverlayPosition.BOTTOM)
        ) {
            OverlayPosition.TOP -> 0.16f
            OverlayPosition.CENTER -> 0.5f
            OverlayPosition.BOTTOM -> 0.84f
        }
    }
}

/** JSON configuration used everywhere projects are read or written. */
val projectJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = false
}

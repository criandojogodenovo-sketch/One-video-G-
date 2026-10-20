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
) {
    val isText: Boolean get() = kind == OverlayKind.TEXT
}

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
) {
    val trimmedDurationMs: Long
        get() = (trimEndMs - trimStartMs).coerceAtLeast(0)

    val endMs: Long get() = startMs + trimmedDurationMs
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
                id = "music_" + m.uri.hashCode().toString(16),
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
        const val SCHEMA_VERSION = 2

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

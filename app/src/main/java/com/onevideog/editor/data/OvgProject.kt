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

/** Where an overlay sits vertically on the frame. */
enum class OverlayPosition { TOP, CENTER, BOTTOM }

/** Basic colour adjustments, applied on export. */
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
}

/** A text overlay, timed on the project timeline (ms). */
@Serializable
data class TextOverlayItem(
    val id: String,
    val text: String,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val position: String = OverlayPosition.BOTTOM.name,
    val startMs: Long = 0,
    val endMs: Long = 0,
)

/** An image overlay, timed on the project timeline (ms). */
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

/** An optional background music track. */
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
    val textOverlays: List<TextOverlayItem> = emptyList(),
    val imageOverlays: List<ImageOverlayItem> = emptyList(),
    val music: MusicTrack? = null,
) {
    val aspectEnum: ProjectAspect
        get() = runCatching { ProjectAspect.valueOf(aspect) }.getOrDefault(ProjectAspect.SOURCE)

    val totalDurationMs: Long get() = clips.sumOf { it.effectiveDurationMs }

    fun withAspect(value: ProjectAspect): OvgProject = copy(aspect = value.name)

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** JSON configuration used everywhere projects are read or written. */
val projectJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = false
}

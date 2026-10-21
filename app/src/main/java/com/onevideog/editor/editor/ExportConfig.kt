package com.onevideog.editor.editor

import com.onevideog.editor.data.ExportResolutionEnum
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.ProjectAspect
import kotlin.math.roundToInt

/**
 * Pure export configuration: output size, bitrate presets and validation.
 * Unit-tested on the JVM (see ExportConfigTest).
 */
object ExportConfig {

    enum class Quality { LOW, MEDIUM, HIGH }

    data class OutputSize(val width: Int, val height: Int)

    /**
     * Output frame size for [resolution] and [aspect]. Both dimensions are
     * even (encoder requirement). The short side is the preset height, e.g.
     * 1080p 16:9 -> 1920x1080, 1080p 9:16 -> 1080x1920.
     */
    fun outputSize(resolution: ExportResolutionEnum, aspect: ProjectAspect): OutputSize {
        val short = resolution.targetHeight
        val ratio = aspect.ratio() ?: 1f
        return if (ratio >= 1f) {
            OutputSize(roundEven(short * ratio), short)
        } else {
            OutputSize(short, roundEven(short / ratio))
        }
    }

    /**
     * Video bitrate (bits/s) for the given preset. Values chosen for social
     * media quality on ordinary devices; not a benchmark claim.
     */
    fun videoBitrate(resolution: ExportResolutionEnum, quality: Quality): Int {
        val base = when (resolution) {
            ExportResolutionEnum.P720 -> 5_000_000
            ExportResolutionEnum.P1080 -> 10_000_000
        }
        val factor = when (quality) {
            Quality.LOW -> 0.6f
            Quality.MEDIUM -> 1f
            Quality.HIGH -> 1.8f
        }
        return (base * factor).roundToInt()
    }

    /**
     * Rough estimated output size in bytes, used for the free-storage check
     * before starting an export (video + ~10% audio/muxer overhead).
     */
    fun estimatedBytes(
        resolution: ExportResolutionEnum,
        quality: Quality,
        durationMs: Long,
    ): Long {
        val seconds = (durationMs.coerceAtLeast(0)) / 1000.0
        val bitsPerSecond = videoBitrate(resolution, quality) * 1.15
        return (bitsPerSecond * seconds / 8.0).toLong()
    }

    /** Sanitizes a project name into a safe file base name. */
    fun sanitizeFileName(name: String): String {
        val cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ")
            .replace(Regex(" {2,}"), " ")
            .trim()
            .take(60)
        return cleaned.ifBlank { "one-video-g" }
    }

    /**
     * Validates that a project can be exported at all. Returns null when valid.
     *
     * v0.5.1: a project WITHOUT base video is exportable — the composition
     * renders a generated black background (see
     * [CompositionFactory.buildBlackBaseItem]). The gate is therefore the
     * project DURATION (>= 5 s for video-less projects via
     * [ProjectMath.MIN_EMPTY_DURATION_MS]), not the presence of video clips;
     * NO_CLIPS remains only for a degenerate zero-duration document.
     */
    fun validateForExport(project: OvgProject): ExportProblem? {
        if (ProjectMath.projectDurationMs(project) <= 0L) return ExportProblem.NO_CLIPS
        for (clip in project.clips) {
            if (clip.trimEndMs - clip.trimStartMs < TimelineLogic.MIN_CLIP_SOURCE_MS) {
                return ExportProblem.CLIP_TOO_SHORT
            }
        }
        return null
    }

    enum class ExportProblem { NO_CLIPS, CLIP_TOO_SHORT }

    private fun roundEven(value: Float): Int {
        val v = value.roundToInt()
        return if (v % 2 == 0) v else v - 1
    }
}

package com.onevideog.editor.editor

import com.onevideog.editor.data.AudioClipItem
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind

/**
 * v0.5.1 — THE unified clip view ("estrutura única de clipes com tipo").
 *
 * The persisted document keeps the schema-v2 lists (clips / overlays /
 * audioClips) for backwards compatibility, but EVERY editing-layer decision
 * that must treat "a clip" uniformly — exclusive selection, new-clip
 * creation, row assignment, "does this clip overlap the playhead" — goes
 * through [TimelineClip] + [TimelineClips], so the rule is written ONCE and
 * parameterised by [ClipKind] instead of copy-pasted per type.
 *
 * Windows use the SAME pure math as preview and export
 * ([TimelineLogic.clipStartMs] for the concatenated video sequence,
 * absolute start/end for overlays and audio) — no third timing source.
 */
enum class ClipKind { VIDEO, TEXT, IMAGE, AUDIO }

/** One timeline element of any kind, in absolute project-time ms. */
data class TimelineClip(
    val id: String,
    val kind: ClipKind,
    val startMs: Long,
    val endMs: Long,
    val label: String,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
}

object TimelineClips {

    /**
     * Every clip in the project as [TimelineClip]s: video clips expand to
     * their concatenated timeline windows, overlays and audio clips keep
     * their absolute windows. Order: video first (timeline order), then
     * audio, then overlays — matches the track order drawn by the timeline.
     */
    fun all(project: OvgProject): List<TimelineClip> {
        val result = mutableListOf<TimelineClip>()
        var acc = 0L
        for (clip in project.clips) {
            val dur = clip.effectiveDurationMs
            result += TimelineClip(
                id = clip.id,
                kind = ClipKind.VIDEO,
                startMs = acc,
                endMs = acc + dur,
                label = clip.displayName,
            )
            acc += dur
        }
        for (audio in project.audioClips) {
            result += audio.toTimelineClip()
        }
        for (overlay in project.overlays) {
            result += overlay.toTimelineClip()
        }
        return result
    }

    fun AudioClipItem.toTimelineClip(): TimelineClip = TimelineClip(
        id = id,
        kind = ClipKind.AUDIO,
        startMs = startMs,
        endMs = endMs,
        label = displayName,
    )

    fun OverlayItem.toTimelineClip(): TimelineClip = TimelineClip(
        id = id,
        kind = when (kind) {
            OverlayKind.TEXT -> ClipKind.TEXT
            OverlayKind.IMAGE -> ClipKind.IMAGE
        },
        startMs = startMs,
        endMs = endMs,
        label = if (kind == OverlayKind.TEXT) text else uri,
    )

    /** Finds a clip of ANY kind by id (ids are unique across kinds — UUIDs). */
    fun find(project: OvgProject, id: String): TimelineClip? =
        all(project).firstOrNull { it.id == id }

    /**
     * v0.5.2 — true when the project holds NOTHING at all: 0 video clips,
     * 0 overlays and 0 audio clips. Such projects are deleted automatically
     * when the editor is left (abandoned empty projects never accumulate).
     * Pure — unit-tested.
     */
    fun isCompletelyEmpty(project: OvgProject): Boolean =
        project.clips.isEmpty() && project.overlays.isEmpty() && project.audioClips.isEmpty()

    /** True when the clip is audible/visible at [timeMs]. */
    fun TimelineClip.isActiveAt(timeMs: Long): Boolean =
        timeMs >= startMs && timeMs < endMs

    /**
     * Row rule (documented contract): a new clip NEVER merges into an
     * existing bar. Overlays are placed by [LanePacker] — a new overlay
     * spans the whole duration, overlaps every existing bar and therefore
     * always opens a NEW lane; two non-overlapping bars may share a lane
     * (they never visually collide). Audio clips each own their dedicated
     * row ([com.onevideog.editor.ui.editor.TimelineStrip] draws one row per
     * clip). Video clips append to the single sequential video track.
     */
    fun wouldShareLaneWith(
        existing: List<TimelineClip>,
        newStartMs: Long,
        newEndMs: Long,
    ): Boolean = existing.any { it.endMs > newStartMs && it.startMs < newEndMs }
}

package com.onevideog.editor.editor

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.onevideog.editor.data.AudioClipItem
import com.onevideog.editor.data.AudioKeyframe
import com.onevideog.editor.data.Clip
import com.onevideog.editor.data.ClipEffect
import com.onevideog.editor.data.ClipTransition
import com.onevideog.editor.data.ColorCurves
import com.onevideog.editor.data.Curve
import com.onevideog.editor.data.CurvePoint
import com.onevideog.editor.data.EffectKind
import com.onevideog.editor.data.Interpolation
import com.onevideog.editor.data.Keyframe
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind
import com.onevideog.editor.data.OverlayTransform
import com.onevideog.editor.data.ProjectAspect
import com.onevideog.editor.data.ProjectRepository
import com.onevideog.editor.data.SpeedPoint
import com.onevideog.editor.data.TransitionKind
import com.onevideog.editor.diag.DiagSanitizer
import com.onevideog.editor.diag.DiagnosticsLogger
import com.onevideog.editor.diag.ImageImportStages
import com.onevideog.editor.diag.OpRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Editor state holder.
 *
 * - Immutable project snapshots; every edit commits a new snapshot to the
 *   undo/redo manager and schedules a debounced autosave.
 * - Playhead state lives here so configuration changes never lose it.
 * - The preview and the export both derive everything from [OvgProject];
 *   there is no separate "preview state".
 * - Missing-media detection runs when a project is opened and whenever a
 *   clip is relinked.
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProjectRepository(application)
    private val importer = MediaImporter(application)

    data class UiState(
        val project: OvgProject? = null,
        val loadError: Boolean = false,
        val selectedClipId: String? = null,
        val selectedOverlayId: String? = null,
        val selectedAudioClipId: String? = null,
        val playheadMs: Long = 0L,
        val isPlaying: Boolean = false,
        /**
         * True between [beginScrub] and [endScrub]. While set, the periodic
         * player-position publisher must NOT write the playhead — the user's
         * finger owns it. This is the single guard against the classic
         * "scrub reverts to the old position" feedback loop.
         */
        val isScrubbing: Boolean = false,
        val zoom: Float = 1f,
        val missingClipIds: Set<String> = emptySet(),
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val saving: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> get() = _ui

    private var history = UndoRedoManager<OvgProject?>(null)
    private var autosaveJob: Job? = null
    private val thumbnailCache = ThumbnailCache()

    /** Authoritative duration for the current project (0 when none). */
    val durationMs: Long
        get() = _ui.value.project?.let { ProjectMath.projectDurationMs(it) } ?: 0L

    // region project lifecycle ------------------------------------------------

    fun open(projectId: String) {
        if (_ui.value.project?.id == projectId) return
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = repository.load(projectId)
            if (loaded == null) {
                DiagnosticsLogger.e("project", "OPEN", null, "project load FAILED (missing or corrupt)")
                withContext(Dispatchers.Main) { _ui.value = UiState(loadError = true) }
                return@launch
            }
            val project = loaded.migrated()
            // v0.6.0 (H6): a stale document (clip removed/moved elsewhere) can
            // carry transitions the planner no longer accepts — drop them on
            // open so the preview and the export can never disagree.
            val sanitized = TransitionPlanner.sanitize(project)
            logDroppedTransitions(project, sanitized)
            history.reset(sanitized)
            val missing = project.clips
                .filter { !importer.isReadable(Uri.parse(it.uri)) }
                .map { it.id }
                .toSet()
            DiagnosticsLogger.i(
                "project", "OPEN", null,
                "project opened",
                meta = listOf(
                    "clips" to project.clips.size.toString(),
                    "overlays" to project.overlays.size.toString(),
                    "audio" to project.audioClips.size.toString(),
                    "missing" to missing.size.toString(),
                    "durationMs" to ProjectMath.projectDurationMs(project).toString(),
                ),
            )
            withContext(Dispatchers.Main) {
                _ui.value = UiState(
                    project = sanitized,
                    missingClipIds = missing,
                    canUndo = history.canUndo,
                    canRedo = history.canRedo,
                )
            }
        }
    }

    fun newProject(name: String, onCreated: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val project = repository.create(name)
            history.reset(project)
            DiagnosticsLogger.i(
                "project", "CREATE", null,
                "new project created",
                meta = listOf(
                    "projectId" to DiagSanitizer.fingerprint(project.id),
                    "nameLen" to name.length.toString(),
                ),
            )
            withContext(Dispatchers.Main) {
                _ui.value = UiState(project = project)
                onCreated(project.id)
            }
        }
    }

    /**
     * v0.5.2 — the "Novo projeto" flow: name -> video picker -> create WITH
     * the chosen video. The project is created EXACTLY once, only after the
     * user picked a video (the picker result is the single trigger — never a
     * LaunchedEffect, never a screen-entry side effect); a cancelled or
     * failed pick creates NOTHING. The video becomes the project base through
     * the ONE shared import path ([importVideo] — appended clip, duration =
     * video length, thumbnails, diagnostics), never a per-flow copy.
     */
    fun newProjectWithVideo(
        name: String,
        videoUri: Uri,
        displayName: String,
        onCreated: (String) -> Unit,
    ) {
        newProject(name) { id ->
            onCreated(id)
            // Runs after the state/navigation are in place; failures surface
            // through importError exactly like the in-editor import.
            importVideo(videoUri, displayName)
        }
    }

    /**
     * v0.5.2 — leaving the editor back to the list. Flushes pending edits and
     * DELETES the project when it is completely empty (0 clips, 0 overlays,
     * 0 audios — [TimelineClips.isCompletelyEmpty]) so abandoned empty
     * projects never accumulate. Projects with ANY content are kept. The
     * navigation callback runs in every outcome (delete, kept, save failure,
     * no project at all) — the user always returns to the list.
     */
    fun exitAndCleanup(onExit: () -> Unit) {
        val project = _ui.value.project
        if (project == null) {
            onExit()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (TimelineClips.isCompletelyEmpty(project)) {
                // Cancel a pending autosave FIRST — a fire-after-delete save
                // would recreate the directory (repository.save mkdirs) and
                // resurrect an empty project. An autosave already in flight is
                // caught by the re-delete below.
                autosaveJob?.cancel()
                repository.delete(project.id)
                if (repository.load(project.id) != null) {
                    repository.delete(project.id)
                }
                DiagnosticsLogger.i(
                    "project", "AUTO_DELETE_EMPTY", null,
                    "empty project deleted on editor exit",
                    meta = listOf("projectId" to DiagSanitizer.fingerprint(project.id)),
                )
                withContext(Dispatchers.Main) {
                    // Drop the in-memory state and history so the deleted
                    // snapshot can never be reused after its file is gone.
                    history = UndoRedoManager(null)
                    _ui.value = UiState()
                    onExit()
                }
            } else {
                autosaveJob?.cancel()
                runCatching {
                    repository.save(project.copy(updatedAtEpochMs = System.currentTimeMillis()))
                }.onFailure {
                    DiagnosticsLogger.e("project", "SAVE", null, "exit save FAILED", it)
                }
                withContext(Dispatchers.Main) { onExit() }
            }
        }
    }

    // region video clips ------------------------------------------------------

    fun importVideo(uri: Uri, displayName: String) {
        val projectId = _ui.value.project?.id ?: return
        val opId = OpRegistry.newOpId("vid")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val persisted = importer.persistReadPermission(uri)
                DiagnosticsLogger.i(
                    MediaImporter.SUBSYSTEM, "VIDEO_IMPORT", opId,
                    "video import started",
                    meta = listOf(
                        "uri" to DiagSanitizer.sanitizeUri(uri.toString()),
                        "persisted" to persisted.toString(),
                    ),
                )
                val thumbsDir = repository.timelineThumbsDir(projectId)
                val clip = importer.createClip(uri, displayName, projectId, thumbsDir)
                commitProject { it.copy(clips = it.clips + clip) }
                // v0.5.1: like text/image/audio, the imported video clip is
                // APPENDED (never replaces an existing one) and becomes the
                // selected clip immediately.
                withContext(Dispatchers.Main) {
                    selectTimelineClip(ClipKind.VIDEO, clip.id)
                }
                refreshHomeThumb()
                DiagnosticsLogger.i(
                    MediaImporter.SUBSYSTEM, "VIDEO_IMPORT", opId,
                    "video import completed (appended, selected)",
                    meta = listOf(
                        "clipId" to DiagSanitizer.fingerprint(clip.id),
                        "durationMs" to clip.sourceDurationMs.toString(),
                        "w" to clip.width.toString(), "h" to clip.height.toString(),
                    ),
                )
            } catch (e: MediaImporter.ImportException) {
                DiagnosticsLogger.e(
                    MediaImporter.SUBSYSTEM, "VIDEO_IMPORT", opId,
                    "video import failed kind=${e.kind}", e,
                )
                importError.value = e.kind
            } catch (e: Exception) {
                DiagnosticsLogger.e(
                    MediaImporter.SUBSYSTEM, "VIDEO_IMPORT", opId,
                    "video import failed unexpectedly", e,
                )
                importError.value = MediaImporter.ImportException.Kind.UNREADABLE
            }
        }
    }

    val importError = MutableStateFlow<MediaImporter.ImportException.Kind?>(null)

    fun consumeImportError() {
        importError.value = null
    }

    fun relinkClip(clipId: String, uri: Uri, displayName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                importer.persistReadPermission(uri)
                val meta = importer.probeVideo(uri)
                val pid = currentProjectId() ?: return@launch
                val thumbsDir = repository.timelineThumbsDir(pid)
                commitProject { project ->
                    val updated = TimelineLogic.withClip(project.clips, clipId) { clip ->
                        clip.copy(
                            uri = uri.toString(),
                            displayName = displayName,
                            sourceDurationMs = meta.durationMs,
                            width = meta.width,
                            height = meta.height,
                            trimStartMs = 0,
                            trimEndMs = meta.durationMs,
                        )
                    }
                    project.copy(clips = updated)
                }
                viewModelScope.launch(Dispatchers.IO) {
                    val fresh = currentProjectId()?.let { repository.load(it) }
                    fresh?.clips?.firstOrNull { it.id == clipId }?.let {
                        importer.generateThumbnails(it, thumbsDir)
                    }
                }
                clearMissing(clipId)
            } catch (e: Exception) {
                importError.value = MediaImporter.ImportException.Kind.UNREADABLE
            }
        }
    }

    fun trimClip(clipId: String, startMs: Long, endMs: Long) {
        DiagnosticsLogger.i(
            "timeline", "TRIM", null,
            "video clip trim applied",
            meta = listOf(
                "clip" to DiagSanitizer.fingerprint(clipId),
                "startMs" to startMs.toString(), "endMs" to endMs.toString(),
            ),
        )
        commitProject { project ->
            project.copy(
                clips = TimelineLogic.withClip(project.clips, clipId) {
                    it.withTrim(startMs, endMs)
                },
            )
        }
    }

    fun splitAtPlayhead() {
        val state = _ui.value
        val project = state.project ?: return
        val result = TimelineLogic.split(project.clips, state.playheadMs)
        if (result == null) {
            // Strictly-inside rule: give actionable feedback instead of a
            // silent no-op (spec: show a clear message for invalid ops).
            DiagnosticsLogger.w(
                "timeline", "SPLIT_REJECTED", null,
                "split rejected at playhead",
                meta = listOf(
                    "playheadMs" to state.playheadMs.toString(),
                    "clips" to project.clips.size.toString(),
                ),
            )
            editError.value = if (project.clips.isEmpty()) {
                EditError.NO_CLIP
            } else {
                EditError.SPLIT_TOO_SHORT
            }
            return
        }
        DiagnosticsLogger.i(
            "timeline", "SPLIT", null,
            "clip split at playhead",
            meta = listOf("playheadMs" to state.playheadMs.toString()),
        )
        commitProject { it.copy(clips = result) }
    }

    /** Actionable, localized feedback for rejected edit operations. */
    enum class EditError { NO_CLIP, SPLIT_TOO_SHORT }

    val editError = MutableStateFlow<EditError?>(null)

    fun consumeEditError() {
        editError.value = null
    }

    fun moveClip(clipId: String, delta: Int) {
        commitProjectOrNull { project ->
            TimelineLogic.move(project.clips, clipId, delta)?.let { project.copy(clips = it) }
        }
    }

    fun deleteClip(clipId: String) {
        commitProjectOrNull { project ->
            TimelineLogic.remove(project.clips, clipId)?.let { project.copy(clips = it) }
        }
        if (_ui.value.selectedClipId == clipId) _ui.value = _ui.value.copy(selectedClipId = null)
    }

    fun removeMissingClip(clipId: String) {
        deleteClip(clipId)
    }

    fun setVolume(clipId: String, volume: Float) {
        commitProject { project ->
            project.copy(
                clips = TimelineLogic.withClip(project.clips, clipId) {
                    it.copy(volume = volume.coerceIn(0f, 2f))
                },
            )
        }
    }

    fun setSpeed(clipId: String, speed: Float) {
        commitProject { project ->
            project.copy(
                clips = TimelineLogic.withClip(project.clips, clipId) {
                    it.copy(speed = speed.coerceIn(0.25f, 4f))
                },
            )
        }
    }

    fun setColor(clipId: String, brightness: Float, contrast: Float, saturation: Float) {
        commitProject { project ->
            project.copy(
                clips = TimelineLogic.withClip(project.clips, clipId) {
                    it.copy(
                        color = com.onevideog.editor.data.ColorAdjustments(
                            brightness, contrast, saturation,
                        ),
                    )
                },
            )
        }
    }

    fun setAspect(aspect: ProjectAspect) {
        commitProject { it.withAspect(aspect) }
    }

    // region transitions between clips (v0.6.0, Fase H) ------------------------

    /**
     * Sets (or replaces) the transition between two ADJACENT video clips.
     * The UI clamps [durationMs] to the legal window
     * ([TransitionPlanner.maxLegalDurationMs]); this guard rejects anything
     * the planner would discard anyway — an invalid transition never enters
     * the document (logged TRANSITION_INVALID, no commit).
     */
    fun setTransition(clipIdA: String, clipIdB: String, kind: TransitionKind, durationMs: Long) {
        val project = _ui.value.project ?: return
        val existing = project.transitions.firstOrNull {
            it.betweenClipIds.first == clipIdA && it.betweenClipIds.second == clipIdB
        }
        val transition = ClipTransition(
            id = existing?.id ?: TimelineLogic.newId(),
            betweenClipIds = clipIdA to clipIdB,
            kind = kind,
            durationMs = durationMs.coerceIn(
                TransitionPlanner.MIN_DURATION_MS,
                TransitionPlanner.MAX_DURATION_MS,
            ),
        )
        val reason = TransitionPlanner.validate(project.clips, transition)
        if (reason != null) {
            DiagnosticsLogger.w(
                "timeline", "TRANSITION_INVALID", null,
                "transition rejected by the planner",
                meta = listOf(
                    "clipA" to DiagSanitizer.fingerprint(clipIdA),
                    "clipB" to DiagSanitizer.fingerprint(clipIdB),
                    "kind" to kind.name,
                    "durationMs" to transition.durationMs.toString(),
                    "reason" to reason.name,
                ),
            )
            return
        }
        commitProject { p ->
            val others = p.transitions.filterNot {
                it.betweenClipIds.first == clipIdA && it.betweenClipIds.second == clipIdB
            }
            p.copy(transitions = others + transition)
        }
        DiagnosticsLogger.i(
            "timeline", "TRANSITION_SET", null,
            "transition set between adjacent clips",
            meta = listOf(
                "clipA" to DiagSanitizer.fingerprint(clipIdA),
                "clipB" to DiagSanitizer.fingerprint(clipIdB),
                "kind" to kind.name,
                "durationMs" to transition.durationMs.toString(),
            ),
        )
    }

    /** Removes the transition between the two clips (no-op when absent). */
    fun clearTransition(clipIdA: String, clipIdB: String) {
        val project = _ui.value.project ?: return
        val exists = project.transitions.any {
            it.betweenClipIds.first == clipIdA && it.betweenClipIds.second == clipIdB
        }
        if (!exists) return
        commitProject { p ->
            p.copy(
                transitions = p.transitions.filterNot {
                    it.betweenClipIds.first == clipIdA && it.betweenClipIds.second == clipIdB
                },
            )
        }
        DiagnosticsLogger.i(
            "timeline", "TRANSITION_REMOVED", null,
            "transition removed",
            meta = listOf(
                "clipA" to DiagSanitizer.fingerprint(clipIdA),
                "clipB" to DiagSanitizer.fingerprint(clipIdB),
            ),
        )
    }

    // region per-clip 2D effects (v0.6.0, Fase I) -----------------------------

    /**
     * Upserts one 2D effect on the clip ([intensity] 0..1). Enforces the
     * [ClipEffectsFactory.MAX_EFFECTS_PER_CLIP] limit: adding a 4th kind is
     * IGNORED (logged EFFECT_LIMIT; the UI also warns). Effects are decoder
     * inputs — the commit re-prepares the preview through the signature.
     */
    fun setClipEffect(clipId: String, kind: EffectKind, intensity: Float) {
        var rejected = false
        commitProject { p ->
            val clips = TimelineLogic.withClip(p.clips, clipId) { clip ->
                val exists = clip.effects.any { it.kind == kind }
                if (!exists && clip.effects.size >= ClipEffectsFactory.MAX_EFFECTS_PER_CLIP) {
                    rejected = true
                    clip
                } else {
                    val effect = ClipEffect(kind, intensity.coerceIn(0f, 1f))
                    val updated = if (exists) {
                        clip.effects.map { if (it.kind == kind) effect else it }
                    } else {
                        clip.effects + effect
                    }
                    clip.copy(effects = updated)
                }
            }
            p.copy(clips = clips)
        }
        if (rejected) {
            DiagnosticsLogger.w(
                "timeline", "EFFECT_LIMIT", null,
                "per-clip effect limit reached — change ignored",
                meta = listOf(
                    "clip" to DiagSanitizer.fingerprint(clipId),
                    "kind" to kind.name,
                    "max" to ClipEffectsFactory.MAX_EFFECTS_PER_CLIP.toString(),
                ),
            )
            return
        }
        DiagnosticsLogger.i(
            "timeline", "EFFECT_SET", null,
            "clip effect upserted",
            meta = listOf(
                "clip" to DiagSanitizer.fingerprint(clipId),
                "kind" to kind.name,
                "intensity" to String.format(java.util.Locale.US, "%.2f", intensity),
            ),
        )
    }

    /** Removes one 2D effect from the clip (no-op when absent). */
    fun removeClipEffect(clipId: String, kind: EffectKind) {
        val project = _ui.value.project ?: return
        val clip = project.clips.firstOrNull { it.id == clipId } ?: return
        if (clip.effects.none { it.kind == kind }) return
        commitProject { p ->
            p.copy(
                clips = TimelineLogic.withClip(p.clips, clipId) {
                    it.copy(effects = it.effects.filterNot { e -> e.kind == kind })
                },
            )
        }
        DiagnosticsLogger.i(
            "timeline", "EFFECT_REMOVED", null,
            "clip effect removed",
            meta = listOf(
                "clip" to DiagSanitizer.fingerprint(clipId),
                "kind" to kind.name,
            ),
        )
    }

    // region per-clip colour curves (v0.7.0, Fase J) ---------------------------

    /**
     * Sets (or clears, with null) the clip's tonal curves (J5). Every one of
     * the four channels is validated by [CurveMath] BEFORE the commit: an
     * invalid curve never enters the document (logged CURVE_INVALID, no
     * commit — the same reject-don't-clamp rule as TRANSITION_INVALID). An
     * all-identity set is normalized to null (no GPU cost). Curves are
     * decoder inputs — the commit re-prepares the preview through the
     * signature (same class as effects).
     */
    fun setClipCurves(clipId: String, curves: ColorCurves?) {
        if (curves != null) {
            val invalid = listOf("master" to curves.master, "red" to curves.red,
                "green" to curves.green, "blue" to curves.blue)
                .firstOrNull { (channel, curve) ->
                    CurveMath.validate(curve)?.also { reason ->
                        DiagnosticsLogger.w(
                            "timeline", "CURVE_INVALID", null,
                            "curve rejected by validation",
                            meta = listOf(
                                "clip" to DiagSanitizer.fingerprint(clipId),
                                "channel" to channel,
                                "reason" to reason.name,
                                "points" to curve.points.size.toString(),
                            ),
                        )
                    } != null
                }
            if (invalid != null) return
        }
        // All-identity curves are semantically "no curves": store null so the
        // pipeline skips the LUT effect entirely (no GPU cost) and the JSON
        // stays minimal.
        val toStore = curves?.takeIf {
            !(CurveMath.isIdentity(it.master) && CurveMath.isIdentity(it.red) &&
                CurveMath.isIdentity(it.green) && CurveMath.isIdentity(it.blue))
        }
        val project = _ui.value.project ?: return
        val existing = project.clips.firstOrNull { it.id == clipId }?.curves
        if (existing == toStore) return
        commitProject { p ->
            p.copy(
                clips = TimelineLogic.withClip(p.clips, clipId) { it.copy(curves = toStore) },
            )
        }
        DiagnosticsLogger.i(
            "timeline", "CURVES_SET", null,
            if (toStore == null) "clip curves cleared" else "clip curves set",
            meta = listOf(
                "clip" to DiagSanitizer.fingerprint(clipId),
                "master" to (toStore?.master?.points?.size ?: 0).toString(),
                "red" to (toStore?.red?.points?.size ?: 0).toString(),
                "green" to (toStore?.green?.points?.size ?: 0).toString(),
                "blue" to (toStore?.blue?.points?.size ?: 0).toString(),
            ),
        )
    }

    /**
     * Convenience for the sheet: updates ONE channel of the clip's curves,
     * keeping the others (missing channels default to the linear identity).
     */
    fun setClipCurveChannel(clipId: String, channel: CurveMath.Channel, curve: Curve) {
        val project = _ui.value.project ?: return
        val existing = project.clips.firstOrNull { it.id == clipId }?.curves
        val linear = Curve(listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)))
        val next = when (channel) {
            CurveMath.Channel.MASTER -> ColorCurves(
                master = curve,
                red = existing?.red ?: linear,
                green = existing?.green ?: linear,
                blue = existing?.blue ?: linear,
            )
            CurveMath.Channel.RED -> ColorCurves(
                master = existing?.master ?: linear,
                red = curve,
                green = existing?.green ?: linear,
                blue = existing?.blue ?: linear,
            )
            CurveMath.Channel.GREEN -> ColorCurves(
                master = existing?.master ?: linear,
                red = existing?.red ?: linear,
                green = curve,
                blue = existing?.blue ?: linear,
            )
            CurveMath.Channel.BLUE -> ColorCurves(
                master = existing?.master ?: linear,
                red = existing?.red ?: linear,
                green = existing?.green ?: linear,
                blue = curve,
            )
        }
        setClipCurves(clipId, next)
    }

    // region per-clip speed ramps (v0.7.0, Fase K) -----------------------------

    /**
     * Sets (or clears, with an empty list) the clip's speed ramps (K6).
     * Invalid points are DROPPED (logged SPEED_RAMP_INVALID, spec K1); fewer
     * than 2 valid points clears the ramps (constant clip.speed); a ramp
     * producing more than [SpeedRampPlanner.MAX_SEGMENTS] segments is
     * accepted but logged SPEED_RAMP_LIMIT (the planner truncates, K7).
     * Ramps are decoder inputs — the commit re-prepares the preview through
     * the signature.
     */
    fun setSpeedRamps(clipId: String, ramps: List<SpeedPoint>) {
        val project = _ui.value.project ?: return
        val clip = project.clips.firstOrNull { it.id == clipId } ?: return
        for ((point, reason) in SpeedRampPlanner.invalidPoints(clip, ramps)) {
            DiagnosticsLogger.w(
                "timeline", "SPEED_RAMP_INVALID", null,
                "speed ramp point dropped",
                meta = listOf(
                    "clip" to DiagSanitizer.fingerprint(clipId),
                    "localTimeMs" to point.localTimeMs.toString(),
                    "reason" to reason.name,
                ),
            )
        }
        val sanitized = SpeedRampPlanner.sanitizePoints(clip, ramps) ?: emptyList()
        if (sanitized == clip.speedRamps) return
        val truncated = SpeedRampPlanner.wasTruncated(clip.copy(speedRamps = sanitized))
        if (truncated) {
            DiagnosticsLogger.w(
                "timeline", "SPEED_RAMP_LIMIT", null,
                "speed ramp exceeds the segment limit — will truncate to MAX_SEGMENTS",
                meta = listOf(
                    "clip" to DiagSanitizer.fingerprint(clipId),
                    "max" to SpeedRampPlanner.MAX_SEGMENTS.toString(),
                ),
            )
        }
        commitProject { p ->
            p.copy(
                clips = TimelineLogic.withClip(p.clips, clipId) {
                    it.copy(speedRamps = sanitized)
                },
            )
        }
        DiagnosticsLogger.i(
            "timeline", "SPEED_RAMP_SET", null,
            if (sanitized.isEmpty()) "speed ramps cleared (constant speed)" else "speed ramps set",
            meta = listOf(
                "clip" to DiagSanitizer.fingerprint(clipId),
                "points" to sanitized.size.toString(),
                "segments" to SpeedRampPlanner.planSegments(
                    clip.copy(speedRamps = sanitized),
                ).size.toString(),
                "outputDurationMs" to SpeedRampPlanner.outputDurationMs(
                    clip.copy(speedRamps = sanitized),
                ).toString(),
            ),
        )
    }

    // region overlays (text/image, transforms, keyframes) ---------------------

    fun addTextOverlay(
        text: String,
        colorArgb: Int,
        fontFamily: String,
        bold: Boolean,
        italic: Boolean,
        sizeFrac: Float,
        bgArgb: Int,
    ) {
        val duration = durationMs
        if (duration <= 0) return
        val project = _ui.value.project ?: return
        val contentAspect = project.aspectEnum.ratio() ?: 16f / 9f
        // Render once to learn the bitmap aspect, then set the initial
        // width fraction so the text appears at sizeFrac of the height.
        // The probe is a measurement-only object (id "probe" is never stored).
        val probe = OverlayItem(
            id = "probe",
            kind = OverlayKind.TEXT,
            text = text,
            colorArgb = colorArgb,
            fontFamily = fontFamily,
            bold = bold,
            italic = italic,
            sizeFrac = sizeFrac,
            bgArgb = bgArgb,
        )
        val bitmap = OverlayBitmapRenderer.textBitmap(probe)
        val aspect = OverlayBitmapRenderer.bitmapAspect(bitmap)
        val initialScale = OverlayLayout.textWidthFrac(sizeFrac, aspect, contentAspect)
        // v0.5.1: the identity is minted BEFORE the commit and the item is
        // APPENDED (never replaces/reuses an existing overlay). A full-span
        // window makes [LanePacker] open a NEW lane for it, and it becomes
        // the selected clip right after the commit.
        val newId = TimelineLogic.newId()
        val newItem = probe.copy(
            id = newId,
            startMs = 0,
            endMs = duration,
            transform = OverlayTransform(x = 0.5f, y = 0.8f, scale = initialScale),
        )
        commitProject { p ->
            val nextZ = (p.overlays.maxOfOrNull { it.zOrder } ?: 0) + 1
            p.copy(overlays = p.overlays + newItem.copy(zOrder = nextZ))
        }
        selectTimelineClip(ClipKind.TEXT, newId)
        DiagnosticsLogger.i(
            "timeline", "OVERLAY_ADD", null,
            "text overlay created (uuid, own lane, selected)",
            meta = listOf(
                "ovId" to DiagSanitizer.fingerprint(newId),
                "endMs" to duration.toString(),
            ),
        )
    }

    /**
     * Image overlay import — fully instrumented (v0.2.3, spec section 3).
     *
     * Every stage gets a structured event under one correlation id (`img-*`):
     * picker result -> persisted permission -> URI/mime/bounds probe ->
     * validation decode (IO thread; the REAL decode happens later on the
     * preview thread, its evidence lands under the same op via DiagContext)
     * -> overlay creation. A validation failure is surfaced to the user as a
     * localized error AND recorded with the real exception — null bitmaps
     * are never silently ignored. The editor stays interactive: failures
     * never throw out of this function.
     */
    fun addImageOverlay(uri: Uri) {
        val opId = OpRegistry.newOpId("img")
        val startedAt = SystemClock.elapsedRealtime()
        DiagnosticsLogger.i(
            MediaImporter.SUBSYSTEM, ImageImportStages.PICKER_RESULT, opId,
            "image picker returned a document",
            meta = listOf("uri" to DiagSanitizer.sanitizeUri(uri.toString())),
        )
        val duration = durationMs
        if (duration <= 0) {
            DiagnosticsLogger.w(
                MediaImporter.SUBSYSTEM, ImageImportStages.OVERLAY_CREATE, opId,
                "image import rejected: project has no video (duration=0)",
            )
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val persisted = importer.persistReadPermission(uri)
                DiagnosticsLogger.i(
                    MediaImporter.SUBSYSTEM, ImageImportStages.PERMISSION, opId,
                    "persistable read permission requested",
                    meta = listOf("persisted" to persisted.toString()),
                )
                // B/E: scheme, authority, mime, size, bounds (no pixel decode).
                val meta = importer.probeImage(uri)
                DiagnosticsLogger.i(
                    MediaImporter.SUBSYSTEM, ImageImportStages.BOUNDS, opId,
                    "image bounds probed",
                    meta = listOf(
                        "mime" to (meta.mimeType ?: "?"),
                        "srcW" to meta.boundsWidth.toString(),
                        "srcH" to meta.boundsHeight.toString(),
                        "sizeBytes" to meta.sizeBytes.toString(),
                    ),
                )
                // G: validation decode on IO — catches unreadable/corrupt
                // images NOW (with a localized error) instead of failing
                // silently later on the preview thread. Bitmap released
                // immediately; never retained for logging.
                val probe = OverlayBitmapRenderer.decodeImage(getApplication(), uri, opId)
                if (probe == null) {
                    // decodeImage already logged the exact failure stage.
                    importError.value =
                        MediaImporter.ImportException.Kind.IMAGE_UNREADABLE
                    DiagnosticsLogger.e(
                        MediaImporter.SUBSYSTEM, ImageImportStages.DECODE_FAIL, opId,
                        "image import aborted after failed validation decode",
                    )
                    return@launch
                }
                // v0.5.4 (docs/AUDIT_v0.5.3_problems.md §3): persist the
                // resource — copy the validated bitmap into app storage and
                // reference the LOCAL file so the overlay survives SAF grant
                // loss and source deletion. Preview and export read the same
                // stored URI (contentResolver handles file://); the export
                // path is untouched. Falls back to the SAF uri if the copy
                // fails (previous behaviour, degraded but working).
                val storedUri = runCatching {
                    val dir = OverlayMediaStore.mediaDir(getApplication())
                    val fileName = OverlayMediaStore.newFileName()
                    File(dir, fileName).outputStream().use { out ->
                        probe.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
                    }
                    OverlayMediaStore.toLocalUriString(dir, fileName)
                }.onFailure {
                    DiagnosticsLogger.w(
                        MediaImporter.SUBSYSTEM, "IMAGE_LOCAL_COPY", opId,
                        "app-local image copy FAILED — overlay keeps the SAF uri", it,
                    )
                }.getOrNull()
                probe.recycle()
                DiagnosticsLogger.i(
                    MediaImporter.SUBSYSTEM, "IMAGE_LOCAL_COPY", opId,
                    if (storedUri != null) {
                        "image persisted to app storage (grant-loss-proof)"
                    } else {
                        "image kept on the SAF uri (copy failed)"
                    },
                )
                val overlayUri = storedUri ?: uri.toString()
                // v0.5.1: identity minted before the commit; the overlay is
                // APPENDED and becomes the selected clip immediately.
                val newId = TimelineLogic.newId()
                var created = false
                commitProject { p ->
                    val nextZ = (p.overlays.maxOfOrNull { it.zOrder } ?: 0) + 1
                    val overlay = OverlayItem(
                        id = newId,
                        kind = OverlayKind.IMAGE,
                        startMs = 0,
                        endMs = duration,
                        transform = OverlayTransform(x = 0.5f, y = 0.5f, scale = 0.4f),
                        zOrder = nextZ,
                        uri = overlayUri,
                    )
                    created = true
                    p.copy(overlays = p.overlays + overlay)
                }
                if (created) {
                    withContext(Dispatchers.Main) {
                        selectTimelineClip(ClipKind.IMAGE, newId)
                    }
                }
                DiagnosticsLogger.i(
                    MediaImporter.SUBSYSTEM, ImageImportStages.OVERLAY_CREATE, opId,
                    "image overlay created (uuid, own lane, selected)",
                    meta = listOf(
                        "ovId" to DiagSanitizer.fingerprint(newId),
                        "durationMs" to duration.toString(),
                        "elapsedMs" to (SystemClock.elapsedRealtime() - startedAt).toString(),
                    ),
                )
            } catch (e: MediaImporter.ImportException) {
                DiagnosticsLogger.e(
                    MediaImporter.SUBSYSTEM, ImageImportStages.DECODE_FAIL, opId,
                    "image import failed kind=${e.kind}", e,
                )
                importError.value = e.kind
            } catch (t: Throwable) {
                DiagnosticsLogger.e(
                    MediaImporter.SUBSYSTEM, ImageImportStages.DECODE_FAIL, opId,
                    "image import failed unexpectedly", t,
                )
                importError.value = MediaImporter.ImportException.Kind.IMAGE_UNREADABLE
            }
        }
    }

    fun updateOverlay(overlayId: String, transform: (OverlayItem) -> OverlayItem) {
        commitProjectOrNull { project ->
            val overlays = project.overlays
            val target = overlays.firstOrNull { it.id == overlayId }
                ?: return@commitProjectOrNull null
            // v0.3.0 layer lock: every transform/keyframe write on a locked
            // layer is rejected without an undo step. UI affordances disable
            // themselves too; this is the single authoritative guard.
            if (!OverlayEditing.isEditable(target.locked)) return@commitProjectOrNull null
            val next = transform(target)
            // v0.4.0 (E1 validation): the shared commit boundary for gizmos
            // AND the properties panel. A transform arriving with non-finite
            // components (NaN/±Inf) is sanitized to the defaults+clamps
            // instead of poisoning the model, the renderers and the export.
            val safe = if (next.transform.hasNonFinite) {
                next.copy(transform = next.transform.withClampedValues())
            } else {
                next
            }
            project.copy(overlays = overlays.map { if (it.id == overlayId) safe else it })
        }
    }

    /**
     * v0.5.0 (Fase F1): THE auto-key transform write path — gizmo gestures
     * (OverlayCanvas onGestureEnd), the quick-panel sliders and the sheet
     * TransformSection all commit through here, so there is exactly ONE
     * auto-key rule and ONE undo step per gesture/slider operation.
     *
     * When the overlay has keyframes, the edit lands on the keyframe at the
     * playhead (± KeyframeEditing.TOLERANCE_MS) or creates one at the
     * snapped playhead; without keyframes it writes the base transform
     * exactly like v0.4.0. See [KeyframeEditing.applyTransformEdit].
     */
    fun applyTransformEdit(overlayId: String, edit: (OverlayTransform) -> OverlayTransform) {
        val playhead = _ui.value.playheadMs
        commitProjectOrNull { project ->
            val target = project.overlays.firstOrNull { it.id == overlayId }
                ?: return@commitProjectOrNull null
            if (!OverlayEditing.isEditable(target.locked)) return@commitProjectOrNull null
            // The edit starts from what the user SEES at the playhead: the
            // evaluated transform when keyframes exist (so updating X on a
            // keyframe keeps the keyframe's other properties), the base
            // transform otherwise (v0.4.0 behaviour). Gesture commits pass
            // the full transform and ignore this starting point.
            val startFrom = if (target.keyframes.isEmpty()) {
                target.transform
            } else {
                KeyframeEvaluator.evaluate(target, playhead)
            }
            val edited = edit(startFrom)
            val safeEdit = if (edited.hasNonFinite) {
                edited.withClampedValues()
            } else {
                edited
            }
            val next = KeyframeEditing.applyTransformEdit(target, playhead, safeEdit)
            if (next == target) return@commitProjectOrNull null
            project.copy(
                overlays = project.overlays.map { if (it.id == overlayId) next else it },
            )
        }
    }

    /**
     * v0.5.0 (G6): sets the text entrance animation. Overlay-level state —
     * segment expansion happens at render time only, so this (like every
     * overlay edit) never touches the preview decoder (effectiveSignature
     * excludes overlays — v0.3.1 contract).
     */
    fun setTextAnimation(overlayId: String, animation: com.onevideog.editor.data.TextAnimation) {
        updateOverlay(overlayId) { it.copy(textAnimation = animation) }
    }

    /** v0.3.0: shows/hides a layer. Allowed even while locked (no edit). */
    fun setOverlayVisible(overlayId: String, visible: Boolean) {
        commitProjectOrNull { project ->
            if (project.overlays.none { it.id == overlayId }) return@commitProjectOrNull null
            project.copy(
                overlays = project.overlays.map {
                    if (it.id == overlayId && it.visible != visible) {
                        it.copy(visible = visible)
                    } else {
                        it
                    }
                },
            )
        }
    }

    /** v0.3.0: locks/unlocks a layer against editing gestures. */
    fun setOverlayLocked(overlayId: String, locked: Boolean) {
        commitProjectOrNull { project ->
            if (project.overlays.none { it.id == overlayId }) return@commitProjectOrNull null
            project.copy(
                overlays = project.overlays.map {
                    if (it.id == overlayId && it.locked != locked) {
                        it.copy(locked = locked)
                    } else {
                        it
                    }
                },
            )
        }
    }

    /**
     * Moves an overlay bar in time (timeline drag): shifts start/end keeping
     * the duration AND shifts keyframe times by the same delta (keyframes
     * belong to the overlay, not to absolute project time). Clamped to
     * start >= 0. Invalid requests are rejected without an undo step. Pure
     * rules in [OverlayEditing].
     */
    fun moveOverlay(overlayId: String, newStartMs: Long) {
        commitProjectOrNull { project ->
            val overlay = project.overlays.firstOrNull { it.id == overlayId }
                ?: return@commitProjectOrNull null
            // v0.3.0 layer lock: moving an overlay bar in time is an edit.
            if (!OverlayEditing.isEditable(overlay.locked)) return@commitProjectOrNull null
            val start = OverlayEditing.clampMove(newStartMs)
            val delta = start - overlay.startMs
            if (delta == 0L) return@commitProjectOrNull null
            project.copy(
                overlays = project.overlays.map {
                    if (it.id == overlayId) {
                        it.copy(
                            startMs = it.startMs + delta,
                            endMs = it.endMs + delta,
                            keyframes = it.keyframes.map { kf -> kf.copy(timeMs = kf.timeMs + delta) },
                        )
                    } else {
                        it
                    }
                },
            )
        }
    }

    /**
     * Trims an overlay bar from the timeline LEFT edge handle. Enforces the
     * minimum duration ([OverlayEditing]); rejects no-op requests without an
     * undo step.
     */
    fun trimOverlayStart(overlayId: String, newStartMs: Long) {
        commitProjectOrNull { project ->
            val overlay = project.overlays.firstOrNull { it.id == overlayId }
                ?: return@commitProjectOrNull null
            if (!OverlayEditing.isEditable(overlay.locked)) return@commitProjectOrNull null
            val start = OverlayEditing.clampTrimStart(
                newStartMs,
                overlay.endMs,
                OverlayEditing.MIN_OVERLAY_MS,
            )
            if (start == overlay.startMs) return@commitProjectOrNull null
            project.copy(
                overlays = project.overlays.map {
                    if (it.id == overlayId) it.copy(startMs = start) else it
                },
            )
        }
    }

    /** Trims an overlay bar from the timeline RIGHT edge handle. */
    fun trimOverlayEnd(overlayId: String, newEndMs: Long) {
        commitProjectOrNull { project ->
            val overlay = project.overlays.firstOrNull { it.id == overlayId }
                ?: return@commitProjectOrNull null
            if (!OverlayEditing.isEditable(overlay.locked)) return@commitProjectOrNull null
            val end = OverlayEditing.clampTrimEnd(
                overlay.startMs,
                newEndMs,
                OverlayEditing.MIN_OVERLAY_MS,
            )
            if (end == overlay.endMs) return@commitProjectOrNull null
            project.copy(
                overlays = project.overlays.map {
                    if (it.id == overlayId) it.copy(endMs = end) else it
                },
            )
        }
    }

    /** Moves an overlay to the bottom of the z-order stack (below all others). */
    fun sendOverlayToBack(id: String) {
        commitProjectOrNull { project ->
            val bottom = (project.overlays.minOfOrNull { it.zOrder } ?: 0) - 1
            val target = project.overlays.firstOrNull { it.id == id }
            if (target == null || !OverlayEditing.isEditable(target.locked)) null
            else project.copy(
                overlays = project.overlays.map {
                    if (it.id == id) it.copy(zOrder = bottom) else it
                },
            )
        }
    }

    fun removeOverlay(id: String) {
        // v0.3.0 layer lock: a locked layer cannot be deleted from the
        // timeline/canvas; the layers sheet keeps the delete button enabled
        // only for unlocked layers, so this guard is the authoritative one.
        if (run {
                val overlay = _ui.value.project?.overlays?.firstOrNull { it.id == id }
                overlay != null && overlay.locked
            }
        ) {
            DiagnosticsLogger.w(
                "timeline", "OVERLAY_REMOVE_REJECTED", null,
                "overlay delete rejected: layer locked",
                meta = listOf("ovId" to DiagSanitizer.fingerprint(id)),
            )
            return
        }
        DiagnosticsLogger.i(
            "timeline", "OVERLAY_REMOVE", null,
            "overlay removed",
            meta = listOf("ovId" to DiagSanitizer.fingerprint(id)),
        )
        commitProject { it.copy(overlays = it.overlays.filterNot { o -> o.id == id }) }
        if (_ui.value.selectedOverlayId == id) {
            _ui.value = _ui.value.copy(selectedOverlayId = null)
        }
    }

    fun bringOverlayToFront(id: String) {
        commitProjectOrNull { project ->
            val top = (project.overlays.maxOfOrNull { it.zOrder } ?: 0) + 1
            val target = project.overlays.firstOrNull { it.id == id }
            if (target == null || !OverlayEditing.isEditable(target.locked)) null
            else project.copy(
                overlays = project.overlays.map {
                    if (it.id == id) it.copy(zOrder = top) else it
                },
            )
        }
    }

    fun addKeyframeAtPlayhead(overlayId: String) {
        val playhead = _ui.value.playheadMs
        commitProjectOrNull { project ->
            val overlay = project.overlays.firstOrNull { it.id == overlayId } ?: return@commitProjectOrNull null
            val evaluated = KeyframeEvaluator.evaluate(overlay, playhead)
            val keyframe = Keyframe(
                timeMs = KeyframeEvaluator.snapTime(playhead),
                x = evaluated.x,
                y = evaluated.y,
                scale = evaluated.scale,
                rotationDeg = evaluated.rotationDeg,
                opacity = evaluated.opacity,
            )
            val updated = overlay.copy(
                keyframes = (overlay.keyframes.filterNot { it.timeMs == keyframe.timeMs } + keyframe)
                    .sortedBy { it.timeMs },
            )
            project.copy(overlays = project.overlays.map { if (it.id == overlayId) updated else it })
        }
    }

    fun removeKeyframe(overlayId: String, timeMs: Long) {
        updateOverlay(overlayId) { overlay ->
            overlay.copy(keyframes = overlay.keyframes.filterNot { it.timeMs == timeMs })
        }
    }

    fun moveKeyframe(overlayId: String, oldTimeMs: Long, newTimeMs: Long) {
        updateOverlay(overlayId) { overlay ->
            // v0.5.0 (F2): collision rule — nearest FREE 50 ms slot inside
            // the search radius; the move is rejected (no-op, no undo step)
            // instead of overwriting the keyframe at the target (the old
            // rule silently DELETED it — data loss).
            val resolved = KeyframeEditing.resolveMoveTarget(
                overlay.keyframes, oldTimeMs, newTimeMs,
            ) ?: return@updateOverlay overlay
            val others = overlay.keyframes.filterNot {
                it.timeMs == oldTimeMs || it.timeMs == resolved
            }
            val moved = overlay.keyframes
                .firstOrNull { it.timeMs == oldTimeMs }
                ?.copy(timeMs = resolved) ?: return@updateOverlay overlay
            overlay.copy(keyframes = (others + moved).sortedBy { it.timeMs })
        }
    }

    fun setInterpolation(overlayId: String, interpolation: Interpolation) {
        updateOverlay(overlayId) { it.copy(interpolation = interpolation) }
    }

    // region depth & style (v0.9.0, Fase L) -----------------------------------

    /**
     * v0.9.0 (L2/D2): sets one overlay's depth [-1, 1]. 0 = the video plane
     * (camera can never move it); deeper (> 0) draws first. zOrder is
     * untouched — it stays the tie-breaker and what bringOverlayToFront /
     * sendOverlayToBack manipulate.
     */
    fun setOverlayZDepth(overlayId: String, zDepth: Float) {
        updateOverlay(overlayId) {
            it.copy(zDepth = zDepth.coerceIn(CameraMath.Z_DEPTH_MIN, CameraMath.Z_DEPTH_MAX))
        }
    }

    /**
     * v0.9.0 (L1): replaces one overlay's baked style (null = no style — the
     * overlay returns to its established render path). The styled contentKey
     * changes, so the next preview frame bakes a fresh bitmap (cache
     * invalidation by construction) and the export consumes the same one.
     */
    fun setOverlayStyle(overlayId: String, style: com.onevideog.editor.data.OverlayStyle?) {
        updateOverlay(overlayId) { it.copy(style = style) }
    }

    /** v0.9.0 (L2): static camera base values (keyframed props override these). */
    fun setCameraPanZoom(panX: Float, panY: Float, zoom: Float) {
        commitProject { project ->
            project.copy(
                camera = project.camera.copy(
                    panX = panX.coerceIn(CameraMath.PAN_MIN, CameraMath.PAN_MAX),
                    panY = panY.coerceIn(CameraMath.PAN_MIN, CameraMath.PAN_MAX),
                    zoom = zoom.coerceIn(CameraMath.ZOOM_MIN, CameraMath.ZOOM_MAX),
                ),
            )
        }
    }

    /** v0.9.0 (L2): captures the evaluated camera at the playhead. */
    fun addCameraKeyframeAtPlayhead() {
        val playhead = _ui.value.playheadMs
        commitProject { project ->
            val evaluated = CameraMath.evaluate(project.camera, playhead)
            val keyframe = com.onevideog.editor.data.CameraKeyframe(
                timeMs = KeyframeEvaluator.snapTime(playhead),
                panX = evaluated.panX,
                panY = evaluated.panY,
                zoom = evaluated.zoom,
            )
            project.copy(
                camera = project.camera.copy(
                    keyframes = (project.camera.keyframes
                        .filterNot { it.timeMs == keyframe.timeMs } + keyframe)
                        .sortedBy { it.timeMs },
                ),
            )
        }
    }

    fun removeCameraKeyframe(timeMs: Long) {
        commitProject { project ->
            project.copy(
                camera = project.camera.copy(
                    keyframes = project.camera.keyframes.filterNot { it.timeMs == timeMs },
                ),
            )
        }
    }

    /** v0.9.0 (L2): moves a camera keyframe (nearest free 50 ms slot, F2 rule). */
    fun moveCameraKeyframe(oldTimeMs: Long, newTimeMs: Long) {
        commitProject { project ->
            val resolved = KeyframeEditing.resolveMoveTargetInTimes(
                project.camera.keyframes.map { it.timeMs }, oldTimeMs, newTimeMs,
            ) ?: return@commitProject project
            val others = project.camera.keyframes.filterNot {
                it.timeMs == oldTimeMs || it.timeMs == resolved
            }
            val moved = project.camera.keyframes
                .firstOrNull { it.timeMs == oldTimeMs }
                ?.copy(timeMs = resolved) ?: return@commitProject project
            project.copy(
                camera = project.camera.copy(
                    keyframes = (others + moved).sortedBy { it.timeMs },
                ),
            )
        }
    }

    fun setCameraInterpolation(interpolation: Interpolation) {
        commitProject { it.copy(camera = it.camera.copy(interpolation = interpolation)) }
    }

    // region audio clips ------------------------------------------------------

    /**
     * Imports an audio document onto an independent audio track.
     *
     * Crash-hardened: any failure (SAF read, metadata probe, persistence)
     * maps to a localized import error — an audio import can never take the
     * editor down. Sanitized diagnostics (no file names, no user content)
     * go to the diagnostics system (logcat mirror + persistent log).
     */
    fun addAudioClip(uri: Uri, displayName: String) {
        val projectId = _ui.value.project?.id ?: return
        val opId = OpRegistry.newOpId("aud")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!importer.isReadable(uri)) {
                    DiagnosticsLogger.w(
                        MediaImporter.SUBSYSTEM, "AUDIO_IMPORT", opId,
                        "audio import rejected: document unreadable",
                        meta = listOf("uri" to DiagSanitizer.sanitizeUri(uri.toString())),
                    )
                    importError.value = MediaImporter.ImportException.Kind.UNREADABLE
                    return@launch
                }
                val persisted = importer.persistReadPermission(uri)
                val meta = importer.probeAudio(uri)
                // v0.5.1: identity minted before the commit; the clip is
                // APPENDED to its own audio row and becomes the selected
                // clip immediately.
                val newId = TimelineLogic.newId()
                var created = false
                commitProject {
                    created = true
                    it.copy(
                        audioClips = it.audioClips + AudioClipItem(
                            id = newId,
                            uri = uri.toString(),
                            displayName = displayName,
                            sourceDurationMs = meta.durationMs,
                            startMs = 0,
                            trimStartMs = 0,
                            trimEndMs = meta.durationMs,
                        ),
                    )
                }
                if (created) {
                    withContext(Dispatchers.Main) {
                        selectTimelineClip(ClipKind.AUDIO, newId)
                    }
                }
                DiagnosticsLogger.i(
                    MediaImporter.SUBSYSTEM, "AUDIO_IMPORT", opId,
                    "audio import completed (uuid, own row, selected)",
                    meta = listOf(
                        "clipId" to DiagSanitizer.fingerprint(newId),
                        "durationMs" to meta.durationMs.toString(),
                        "persisted" to persisted.toString(),
                    ),
                )
            } catch (e: MediaImporter.ImportException) {
                DiagnosticsLogger.e(
                    MediaImporter.SUBSYSTEM, "AUDIO_IMPORT", opId,
                    "audio import failed kind=${e.kind}", e,
                )
                importError.value = e.kind
            } catch (t: Throwable) {
                DiagnosticsLogger.e(
                    MediaImporter.SUBSYSTEM, "AUDIO_IMPORT", opId,
                    "audio import failed unexpectedly", t,
                )
                importError.value = MediaImporter.ImportException.Kind.UNREADABLE
            }
        }
    }

    /** Preview-side audio decoder failures, surfaced once per clip. */
    val audioPreviewError = MutableStateFlow<String?>(null)

    fun consumeAudioPreviewError() {
        audioPreviewError.value = null
    }

    /** Video preview decoder failure (sanitized Media3 error code name). */
    val playbackError = MutableStateFlow<String?>(null)

    fun consumePlaybackError() {
        playbackError.value = null
    }

    fun moveAudioClip(clipId: String, newStartMs: Long) {
        updateAudioClip(clipId) { it.copy(startMs = newStartMs.coerceAtLeast(0)) }
    }

    fun trimAudioClip(clipId: String, trimStartMs: Long, trimEndMs: Long, newStartMs: Long?) {
        updateAudioClip(clipId) { clip ->
            val start = trimStartMs.coerceIn(0, clip.sourceDurationMs)
            val end = trimEndMs.coerceIn(start + AudioTimelineMinMs, clip.sourceDurationMs)
            clip.copy(
                trimStartMs = start,
                trimEndMs = end,
                startMs = (newStartMs ?: clip.startMs).coerceAtLeast(0),
            )
        }
    }

    fun setAudioVolume(clipId: String, volume: Float) {
        updateAudioClip(clipId) { it.copy(volume = volume.coerceIn(0f, 2f)) }
    }

    fun setAudioLoop(clipId: String, loop: Boolean) {
        updateAudioClip(clipId) { it.copy(loop = loop) }
    }

    // v0.8.0 (P3/Q3/Q4) — audio gain automation edits. All of them write the
    // model ONLY; preview and export derive the actual gain from the shared
    // AudioGainMath through the shared GainAutomationAudioProcessor.

    /**
     * Sets the clip fades (clip-local ms). Fades are clamped to
     * [0, trimmedDurationMs]; a fade longer than the clip is therefore not
     * storable from the UI (the math still tolerates it for external
     * documents — tested edge case).
     */
    fun setAudioFades(clipId: String, fadeInMs: Long, fadeOutMs: Long) {
        updateAudioClip(clipId) { clip ->
            val max = clip.trimmedDurationMs
            clip.copy(fadeInMs = fadeInMs.coerceIn(0, max), fadeOutMs = fadeOutMs.coerceIn(0, max))
        }
        val clip = _ui.value.project?.audioClips?.firstOrNull { it.id == clipId } ?: return
        DiagnosticsLogger.i(
            "timeline", "AUDIO_GAIN_SET", null,
            "audio fades set",
            meta = listOf(
                "clip" to DiagSanitizer.fingerprint(clipId),
                "fadeInMs" to clip.fadeInMs.toString(),
                "fadeOutMs" to clip.fadeOutMs.toString(),
            ),
        )
    }

    /**
     * Commits the volume-automation curve (Q3): invalid points are dropped
     * (AUDIO_KEYFRAME_INVALID), equal timestamps deduped and the 16-point
     * cap enforced (AUDIO_KEYFRAME_LIMIT) — the sanitize result is what the
     * model stores, so preview and export can never see an invalid point.
     */
    fun setAudioKeyframes(clipId: String, keyframes: List<AudioKeyframe>) {
        var dropped = 0
        var capped = false
        updateAudioClip(clipId) { clip ->
            val (valid, droppedCount) = AudioGainMath.sanitizeKeyframes(
                keyframes, clip.trimmedDurationMs,
            )
            dropped = droppedCount
            capped = keyframes.size > AudioGainMath.MAX_KEYFRAMES
            clip.copy(gainKeyframes = valid)
        }
        if (dropped > 0) {
            DiagnosticsLogger.w(
                "timeline", "AUDIO_KEYFRAME_INVALID", null,
                "audio keyframes dropped by validation",
                meta = listOf(
                    "clip" to DiagSanitizer.fingerprint(clipId),
                    "dropped" to dropped.toString(),
                    "submitted" to keyframes.size.toString(),
                ),
            )
        }
        if (capped) {
            DiagnosticsLogger.w(
                "timeline", "AUDIO_KEYFRAME_LIMIT", null,
                "audio keyframes exceeded the per-clip cap — truncated",
                meta = listOf(
                    "clip" to DiagSanitizer.fingerprint(clipId),
                    "max" to AudioGainMath.MAX_KEYFRAMES.toString(),
                ),
            )
        }
        val clip = _ui.value.project?.audioClips?.firstOrNull { it.id == clipId } ?: return
        DiagnosticsLogger.i(
            "timeline", "AUDIO_GAIN_SET", null,
            if (clip.gainKeyframes.isEmpty()) "audio keyframes cleared" else "audio keyframes set",
            meta = listOf(
                "clip" to DiagSanitizer.fingerprint(clipId),
                "points" to clip.gainKeyframes.size.toString(),
            ),
        )
    }

    /** Toggles the simple time-domain noise gate (Q4). */
    fun setAudioNoiseGate(clipId: String, enabled: Boolean) {
        updateAudioClip(clipId) { it.copy(noiseGate = enabled) }
        DiagnosticsLogger.i(
            "timeline", "AUDIO_GAIN_SET", null,
            if (enabled) "audio noise gate on" else "audio noise gate off",
            meta = listOf("clip" to DiagSanitizer.fingerprint(clipId)),
        )
    }

    /**
     * Applies the crossfade plan (P4) between [clipId] and its nearest
     * audio neighbour: creates the overlap, sets both fades. No-op when the
     * clip has no neighbour (single audio track).
     */
    fun applyAudioCrossfade(clipId: String, requestedMs: Long = AudioCrossfade.DEFAULT_CROSSFADE_MS) {
        val project = _ui.value.project ?: return
        val selected = project.audioClips.firstOrNull { it.id == clipId } ?: return
        val neighbour = AudioCrossfade.neighbour(project.audioClips, selected) ?: return
        val (earlier, later) = if (neighbour.endMs <= selected.startMs) {
            neighbour to selected
        } else {
            selected to neighbour
        }
        val plan = AudioCrossfade.plan(earlier, later, requestedMs)
        commitProjectOrNull { p ->
            p.copy(
                audioClips = p.audioClips.map { clip ->
                    when (clip.id) {
                        plan.earlier.id -> plan.earlier
                        plan.later.id -> plan.later
                        else -> clip
                    }
                },
            )
        }
        DiagnosticsLogger.i(
            "timeline", "AUDIO_CROSSFADE", null,
            "audio crossfade applied",
            meta = listOf(
                "earlier" to DiagSanitizer.fingerprint(plan.earlier.id),
                "later" to DiagSanitizer.fingerprint(plan.later.id),
                "overlapMs" to plan.overlapMs.toString(),
            ),
        )
    }

    fun removeAudioClip(clipId: String) {
        commitProject { project ->
            project.copy(audioClips = project.audioClips.filterNot { it.id == clipId })
        }
        if (_ui.value.selectedAudioClipId == clipId) {
            _ui.value = _ui.value.copy(selectedAudioClipId = null)
        }
    }

    private fun updateAudioClip(clipId: String, transform: (AudioClipItem) -> AudioClipItem) {
        commitProjectOrNull { project ->
            if (project.audioClips.none { it.id == clipId }) return@commitProjectOrNull null
            project.copy(
                audioClips = project.audioClips.map {
                    if (it.id == clipId) transform(it) else it
                },
            )
        }
    }

    private companion object {
        const val AudioTimelineMinMs = 150L
    }

    // region playback state ---------------------------------------------------

    /** One authoritative playhead. Writers: scrub gesture, transport seeks,
     *  and the periodic frame publisher (only while playing and NOT scrubbing). */
    fun setPlayhead(ms: Long) {
        _ui.value = _ui.value.copy(playheadMs = clampPlayhead(ms))
    }

    fun setPlaying(playing: Boolean) {
        _ui.value = _ui.value.copy(isPlaying = playing)
    }

    /** Clamps to the valid project range [0, durationMs]. */
    fun clampPlayhead(ms: Long): Long = ProjectMath.clampPlayheadMs(ms, durationMs)

    /**
     * Seeks the composition to [ms]: moves the authoritative playhead and
     * bumps the seek token so the preview player follows. Safe to call from
     * anywhere; clamped to the project duration.
     */
    fun seekTo(ms: Long) {
        setPlayhead(ms)
        _seekToken.value++
    }

    private val _seekToken = MutableStateFlow(0)
    val seekToken: StateFlow<Int> get() = _seekToken

    private var wasPlayingBeforeScrub = false

    /**
     * Start of a timeline scrub: the playhead now belongs to the user's
     * pointer. Playback pauses so the player cannot fight the drag; the
     * previous playing state is restored on [endScrub]. Instrumented (cold:
     * once per gesture) — gesture-correlation evidence for the timeline
     * touch-reliability problem. v0.2.4: the gesture now carries full
     * coordinate evidence (pointer px, mapped time, scroll, zoom, density)
     * so the next device report can conclusively separate mapping bugs from
     * user gestures.
     */
    fun beginScrub(
        pointerXpx: Float? = null,
        mappedMs: Long? = null,
        scrollPx: Int? = null,
        zoom: Float? = null,
        density: Float? = null,
        durationMs: Long? = null,
    ) {
        if (_ui.value.isScrubbing) return
        wasPlayingBeforeScrub = _ui.value.isPlaying
        _ui.value = _ui.value.copy(isScrubbing = true, isPlaying = false)
        DiagnosticsLogger.d(
            "timeline", "SCRUB_START", null,
            "scrub gesture started",
            meta = listOfNotNull(
                "playheadMs" to _ui.value.playheadMs.toString(),
                "wasPlaying" to wasPlayingBeforeScrub.toString(),
                pointerXpx?.let { "pointerXpx" to it.toString() },
                mappedMs?.let { "mappedMs" to it.toString() },
                scrollPx?.let { "scrollPx" to it.toString() },
                zoom?.let { "zoom" to it.toString() },
                density?.let { "density" to it.toString() },
                durationMs?.let { "durationMs" to it.toString() },
            ),
        )
    }

    /**
     * End of a scrub: seek the player to the final playhead (async seek —
     * the UI playhead stays where the user left it) and resume playback if
     * it was running when the drag started. v0.2.4: the seek happens BEFORE
     * playback resumes and the preview publisher is gated by [PlayheadGate]
     * so a stale pre-seek player position can never overwrite the final
     * playhead (the device "scrub ends at 120 ms and snaps back to 10163" bug).
     */
    fun endScrub(
        pointerXpx: Float? = null,
        mappedMs: Long? = null,
        scrollPx: Int? = null,
        zoom: Float? = null,
        density: Float? = null,
        durationMs: Long? = null,
    ) {
        if (!_ui.value.isScrubbing) return
        _ui.value = _ui.value.copy(isScrubbing = false)
        DiagnosticsLogger.d(
            "timeline", "SCRUB_END", null,
            "scrub gesture ended",
            meta = listOfNotNull(
                "playheadMs" to _ui.value.playheadMs.toString(),
                "resumePlaying" to wasPlayingBeforeScrub.toString(),
                pointerXpx?.let { "pointerXpx" to it.toString() },
                mappedMs?.let { "mappedMs" to it.toString() },
                scrollPx?.let { "scrollPx" to it.toString() },
                zoom?.let { "zoom" to it.toString() },
                density?.let { "density" to it.toString() },
                durationMs?.let { "durationMs" to it.toString() },
            ),
        )
        seekTo(_ui.value.playheadMs)
        if (wasPlayingBeforeScrub) setPlaying(true)
        wasPlayingBeforeScrub = false
    }

    /** Scrub update from the timeline pointer: clamp + write, no seek yet. */
    fun scrubTo(ms: Long) {
        if (!_ui.value.isScrubbing) return
        setPlayhead(ms)
    }

    // v0.5.1: the legacy per-kind setters are now THIN ALIASES of the
    // unified exclusive selection — any code path that still calls them
    // (instead of [selectTimelineClip]) automatically gets the
    // one-selected-clip-of-any-kind semantics, so a forgotten call site can
    // never resurrect the "two selected clips of different kinds" bug.

    fun selectClip(id: String?) {
        selectTimelineClip(ClipKind.VIDEO, id)
    }

    fun selectOverlay(id: String?) {
        if (id == null) {
            clearSelection()
        } else {
            val kind = _ui.value.project?.overlays
                ?.firstOrNull { it.id == id }
                ?.let { if (it.isText) ClipKind.TEXT else ClipKind.IMAGE }
                ?: ClipKind.TEXT
            selectTimelineClip(kind, id)
        }
    }

    fun selectAudioClip(id: String?) {
        selectTimelineClip(ClipKind.AUDIO, id)
    }

    /**
     * v0.5.1 — THE unified exclusive selection ("um clipe selecionado de
     * cada vez, qualquer tipo"). Every tap/select path routes here instead
     * of repeating the three-call clear dance, so selection can never end
     * up with two selected clips of different kinds.
     */
    fun selectTimelineClip(kind: ClipKind, id: String?) {
        _ui.value = _ui.value.copy(
            selectedClipId = if (kind == ClipKind.VIDEO) id else null,
            selectedOverlayId = if (kind == ClipKind.TEXT || kind == ClipKind.IMAGE) id else null,
            selectedAudioClipId = if (kind == ClipKind.AUDIO) id else null,
        )
    }

    /** Clears every selection (single entry point). */
    fun clearSelection() {
        _ui.value = _ui.value.copy(
            selectedClipId = null,
            selectedOverlayId = null,
            selectedAudioClipId = null,
        )
    }

    /** The currently selected clip id for [kind], or null. */
    fun selectedIdFor(kind: ClipKind): String? = when (kind) {
        ClipKind.VIDEO -> _ui.value.selectedClipId
        ClipKind.TEXT, ClipKind.IMAGE -> _ui.value.selectedOverlayId
        ClipKind.AUDIO -> _ui.value.selectedAudioClipId
    }

    fun setZoom(zoom: Float) {
        _ui.value = _ui.value.copy(zoom = zoom.coerceIn(0.5f, 4f))
    }

    // region undo / redo / persistence ----------------------------------------

    fun undo() {
        val restored = history.undo() ?: return
        _ui.value = _ui.value.copy(
            project = restored,
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
        scheduleAutosave()
    }

    fun redo() {
        val restored = history.redo() ?: return
        _ui.value = _ui.value.copy(
            project = restored,
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
        scheduleAutosave()
    }

    /**
     * Crash-hardened autosave (v0.2.3): a failing disk write used to
     * propagate out of the coroutine and kill the process via the default
     * uncaught-exception handler. Now the failure is logged (sanitized) and
     * the editor stays interactive.
     */
    fun saveNow(onSaved: (() -> Unit)? = null) {
        val project = _ui.value.project ?: return
        autosaveJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            val saveOk = runCatching {
                repository.save(project.copy(updatedAtEpochMs = System.currentTimeMillis()))
            }
            saveOk.onFailure {
                DiagnosticsLogger.e(
                    "project", "SAVE", null,
                    "explicit project save FAILED", it,
                )
            }
            withContext(Dispatchers.Main) {
                _ui.value = _ui.value.copy(saving = false)
                if (saveOk.isSuccess) onSaved?.invoke()
            }
        }
    }

    private fun currentProjectId(): String? = _ui.value.project?.id

    /**
     * v0.6.0: evidence for H6 — one TRANSITION_INVALID event per transition
     * the sanitizer dropped between [before] and [after].
     */
    private fun logDroppedTransitions(before: OvgProject, after: OvgProject) {
        if (before.transitions.size == after.transitions.size) return
        val keptIds = after.transitions.map { it.id }.toSet()
        for (dropped in before.transitions) {
            if (dropped.id in keptIds) continue
            val reason = TransitionPlanner.validate(before.clips, dropped)
            DiagnosticsLogger.w(
                "timeline", "TRANSITION_INVALID", null,
                "transition dropped by sanitize",
                meta = listOf(
                    "clipA" to DiagSanitizer.fingerprint(dropped.betweenClipIds.first),
                    "clipB" to DiagSanitizer.fingerprint(dropped.betweenClipIds.second),
                    "kind" to dropped.kind.name,
                    "durationMs" to dropped.durationMs.toString(),
                    "reason" to (reason?.name ?: "UNKNOWN"),
                ),
            )
        }
    }

    private fun commitProject(transform: (OvgProject) -> OvgProject) {
        commitProjectOrNull(transform)
    }

    private fun commitProjectOrNull(transform: (OvgProject) -> OvgProject?) {
        val current = _ui.value.project ?: return
        val transformed = transform(current) ?: return
        // v0.6.0 (H6): EVERY commit passes through the transition sanitizer —
        // a clip removal/move/split that breaks adjacency drops the orphaned
        // transition in the same snapshot (automatic cleanup, documented in
        // TransitionPlannerTest). Dropped transitions are logged.
        val next = TransitionPlanner.sanitize(transformed)
        logDroppedTransitions(transformed, next)
        // v0.2.5 no-op guard: a transform that produces an identical snapshot
        // (e.g. a tap-gesture "commit" with unchanged transform) must not
        // create an undo step, an autosave, or a new project reference — the
        // same reference keeps downstream recomposition/effects keys stable.
        if (next == current) return
        history.commit(next)
        _ui.value = _ui.value.copy(
            project = next,
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
        scheduleAutosave()
    }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(500)
            _ui.value.project?.let { project ->
                runCatching {
                    repository.save(project.copy(updatedAtEpochMs = System.currentTimeMillis()))
                }.onFailure {
                    DiagnosticsLogger.e(
                        "project", "AUTOSAVE", null,
                        "autosave FAILED (project state kept in memory)", it,
                    )
                }
                withContext(Dispatchers.Main) { _ui.value = _ui.value.copy(saving = true) }
            }
        }
    }

    private fun clearMissing(clipId: String) {
        _ui.value = _ui.value.copy(missingClipIds = _ui.value.missingClipIds - clipId)
    }

    private fun refreshHomeThumb() {
        val projectId = currentProjectId() ?: return
        val project = _ui.value.project ?: return
        val firstClip = project.clips.firstOrNull() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(getApplication(), Uri.parse(firstClip.uri))
                retriever.getFrameAtTime(
                    firstClip.trimStartMs * 1000,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                )?.let { repository.saveHomeThumbnail(projectId, it) }
            } catch (e: Exception) {
                // best-effort
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    // region thumbnails --------------------------------------------------------

    /** Loads a decoded thumbnail from disk with a bounded in-memory cache. */
    fun loadThumbnail(path: String): android.graphics.Bitmap? {
        thumbnailCache.get(path)?.let { return it }
        val bitmap = android.graphics.BitmapFactory.decodeFile(path) ?: return null
        thumbnailCache.put(path, bitmap)
        return bitmap
    }

    fun thumbFilesForClip(clip: Clip, projectId: String): List<File> {
        val dir = repository.timelineThumbsDir(projectId)
        return (0 until MediaImporter.MAX_THUMBS_PER_CLIP)
            .map { File(dir, "${clip.id}_$it.jpg") }
            .filter { it.isFile }
    }

    fun homeThumb(projectId: String): File? = repository.homeThumbnailFile(projectId)
}

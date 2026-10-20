package com.onevideog.editor.editor

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.onevideog.editor.data.AudioClipItem
import com.onevideog.editor.data.Clip
import com.onevideog.editor.data.Interpolation
import com.onevideog.editor.data.Keyframe
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind
import com.onevideog.editor.data.OverlayTransform
import com.onevideog.editor.data.ProjectAspect
import com.onevideog.editor.data.ProjectRepository
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
            history.reset(project)
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
                    project = project,
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
            withContext(Dispatchers.Main) {
                _ui.value = UiState(project = project)
                onCreated(project.id)
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
                refreshHomeThumb()
                DiagnosticsLogger.i(
                    MediaImporter.SUBSYSTEM, "VIDEO_IMPORT", opId,
                    "video import completed",
                    meta = listOf(
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
        commitProject { p ->
            val nextZ = (p.overlays.maxOfOrNull { it.zOrder } ?: 0) + 1
            // Render once to learn the bitmap aspect, then set the initial
            // width fraction so the text appears at sizeFrac of the height.
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
            p.copy(
                overlays = p.overlays + probe.copy(
                    id = TimelineLogic.newId(),
                    startMs = 0,
                    endMs = duration,
                    zOrder = nextZ,
                    transform = OverlayTransform(x = 0.5f, y = 0.8f, scale = initialScale),
                ),
            )
        }
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
                probe.recycle()
                var createdId = ""
                commitProject { p ->
                    val nextZ = (p.overlays.maxOfOrNull { it.zOrder } ?: 0) + 1
                    val overlay = OverlayItem(
                        id = TimelineLogic.newId(),
                        kind = OverlayKind.IMAGE,
                        startMs = 0,
                        endMs = duration,
                        transform = OverlayTransform(x = 0.5f, y = 0.5f, scale = 0.4f),
                        zOrder = nextZ,
                        uri = uri.toString(),
                    )
                    createdId = overlay.id
                    p.copy(overlays = p.overlays + overlay)
                }
                DiagnosticsLogger.i(
                    MediaImporter.SUBSYSTEM, ImageImportStages.OVERLAY_CREATE, opId,
                    "image overlay created",
                    meta = listOf(
                        "ovId" to DiagSanitizer.fingerprint(createdId),
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
        val snapped = KeyframeEvaluator.snapTime(newTimeMs)
        updateOverlay(overlayId) { overlay ->
            val others = overlay.keyframes.filterNot { it.timeMs == oldTimeMs || it.timeMs == snapped }
            val moved = overlay.keyframes
                .firstOrNull { it.timeMs == oldTimeMs }
                ?.copy(timeMs = snapped) ?: return@updateOverlay overlay
            overlay.copy(keyframes = (others + moved).sortedBy { it.timeMs })
        }
    }

    fun setInterpolation(overlayId: String, interpolation: Interpolation) {
        updateOverlay(overlayId) { it.copy(interpolation = interpolation) }
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
                commitProject {
                    it.copy(
                        audioClips = it.audioClips + AudioClipItem(
                            id = TimelineLogic.newId(),
                            uri = uri.toString(),
                            displayName = displayName,
                            sourceDurationMs = meta.durationMs,
                            startMs = 0,
                            trimStartMs = 0,
                            trimEndMs = meta.durationMs,
                        ),
                    )
                }
                DiagnosticsLogger.i(
                    MediaImporter.SUBSYSTEM, "AUDIO_IMPORT", opId,
                    "audio import completed",
                    meta = listOf(
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

    fun selectClip(id: String?) {
        _ui.value = _ui.value.copy(selectedClipId = id)
    }

    fun selectOverlay(id: String?) {
        _ui.value = _ui.value.copy(selectedOverlayId = id)
    }

    fun selectAudioClip(id: String?) {
        _ui.value = _ui.value.copy(selectedAudioClipId = id)
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

    private fun commitProject(transform: (OvgProject) -> OvgProject) {
        commitProjectOrNull(transform)
    }

    private fun commitProjectOrNull(transform: (OvgProject) -> OvgProject?) {
        val current = _ui.value.project ?: return
        val next = transform(current) ?: return
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

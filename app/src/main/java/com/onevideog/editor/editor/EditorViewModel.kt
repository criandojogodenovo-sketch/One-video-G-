package com.onevideog.editor.editor

import android.app.Application
import android.net.Uri
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
                withContext(Dispatchers.Main) { _ui.value = UiState(loadError = true) }
                return@launch
            }
            val project = loaded.migrated()
            history.reset(project)
            val missing = project.clips
                .filter { !importer.isReadable(Uri.parse(it.uri)) }
                .map { it.id }
                .toSet()
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                importer.persistReadPermission(uri)
                val thumbsDir = repository.timelineThumbsDir(projectId)
                val clip = importer.createClip(uri, displayName, projectId, thumbsDir)
                commitProject { it.copy(clips = it.clips + clip) }
                refreshHomeThumb()
            } catch (e: MediaImporter.ImportException) {
                importError.value = e.kind
            } catch (e: Exception) {
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
            editError.value = if (project.clips.isEmpty()) {
                EditError.NO_CLIP
            } else {
                EditError.SPLIT_TOO_SHORT
            }
            return
        }
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

    fun addImageOverlay(uri: Uri) {
        val duration = durationMs
        if (duration <= 0) return
        importer.persistReadPermission(uri)
        commitProject { p ->
            val nextZ = (p.overlays.maxOfOrNull { it.zOrder } ?: 0) + 1
            p.copy(
                overlays = p.overlays + OverlayItem(
                    id = TimelineLogic.newId(),
                    kind = OverlayKind.IMAGE,
                    startMs = 0,
                    endMs = duration,
                    transform = OverlayTransform(x = 0.5f, y = 0.5f, scale = 0.4f),
                    zOrder = nextZ,
                    uri = uri.toString(),
                ),
            )
        }
    }

    fun updateOverlay(overlayId: String, transform: (OverlayItem) -> OverlayItem) {
        commitProjectOrNull { project ->
            val overlays = project.overlays
            if (overlays.none { it.id == overlayId }) return@commitProjectOrNull null
            project.copy(overlays = overlays.map { if (it.id == overlayId) transform(it) else it })
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
            if (project.overlays.none { it.id == id }) null
            else project.copy(
                overlays = project.overlays.map {
                    if (it.id == id) it.copy(zOrder = bottom) else it
                },
            )
        }
    }

    fun removeOverlay(id: String) {
        commitProject { it.copy(overlays = it.overlays.filterNot { o -> o.id == id }) }
        if (_ui.value.selectedOverlayId == id) {
            _ui.value = _ui.value.copy(selectedOverlayId = null)
        }
    }

    fun bringOverlayToFront(id: String) {
        commitProjectOrNull { project ->
            val top = (project.overlays.maxOfOrNull { it.zOrder } ?: 0) + 1
            if (project.overlays.none { it.id == id }) null
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
     * go to logcat only.
     */
    fun addAudioClip(uri: Uri, displayName: String) {
        val projectId = _ui.value.project?.id ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!importer.isReadable(uri)) {
                    importError.value = MediaImporter.ImportException.Kind.UNREADABLE
                    return@launch
                }
                importer.persistReadPermission(uri)
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
            } catch (e: MediaImporter.ImportException) {
                android.util.Log.w(
                    "OvgAudioImport",
                    "audio import failed kind=${e.kind} cause=${e.cause?.javaClass?.simpleName}",
                )
                importError.value = e.kind
            } catch (t: Throwable) {
                android.util.Log.w(
                    "OvgAudioImport",
                    "audio import failed unexpected=${t.javaClass.simpleName}",
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
     * previous playing state is restored on [endScrub].
     */
    fun beginScrub() {
        if (_ui.value.isScrubbing) return
        wasPlayingBeforeScrub = _ui.value.isPlaying
        _ui.value = _ui.value.copy(isScrubbing = true, isPlaying = false)
    }

    /**
     * End of a scrub: seek the player to the final playhead (async seek —
     * the UI playhead stays where the user left it) and resume playback if
     * it was running when the drag started.
     */
    fun endScrub() {
        if (!_ui.value.isScrubbing) return
        _ui.value = _ui.value.copy(isScrubbing = false)
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

    fun saveNow(onSaved: (() -> Unit)? = null) {
        val project = _ui.value.project ?: return
        autosaveJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            repository.save(project.copy(updatedAtEpochMs = System.currentTimeMillis()))
            withContext(Dispatchers.Main) {
                _ui.value = _ui.value.copy(saving = false)
                onSaved?.invoke()
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
                repository.save(project.copy(updatedAtEpochMs = System.currentTimeMillis()))
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

package com.onevideog.editor.editor

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.onevideog.editor.data.Clip
import com.onevideog.editor.data.ImageOverlayItem
import com.onevideog.editor.data.MusicTrack
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.OverlayPosition
import com.onevideog.editor.data.ProjectAspect
import com.onevideog.editor.data.ProjectRepository
import com.onevideog.editor.data.TextOverlayItem
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Editor state holder.
 *
 * - Immutable project snapshots; every edit commits a new snapshot to the
 *   undo/redo manager and schedules a debounced autosave.
 * - Playhead state lives here so configuration changes never lose it.
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
        val playheadMs: Long = 0L,
        val isPlaying: Boolean = false,
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

    // region project lifecycle ------------------------------------------------

    fun open(projectId: String) {
        if (_ui.value.project?.id == projectId) return
        viewModelScope.launch(Dispatchers.IO) {
            val project = repository.load(projectId)
            if (project == null) {
                withContext(Dispatchers.Main) { _ui.value = UiState(loadError = true) }
                return@launch
            }
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

    // region editing operations ----------------------------------------------

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
        val result = TimelineLogic.split(project.clips, state.playheadMs) ?: return
        commitProject { it.copy(clips = result) }
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

    fun addTextOverlay(text: String, colorArgb: Int, position: OverlayPosition) {
        val state = _ui.value
        val project = state.project ?: return
        val total = project.totalDurationMs
        commitProject {
            it.copy(
                textOverlays = it.textOverlays + TextOverlayItem(
                    id = TimelineLogic.newId(),
                    text = text,
                    colorArgb = colorArgb,
                    position = position.name,
                    startMs = 0,
                    endMs = total,
                ),
            )
        }
    }

    fun removeTextOverlay(id: String) {
        commitProject { it.copy(textOverlays = it.textOverlays.filterNot { t -> t.id == id }) }
    }

    fun addImageOverlay(uri: Uri, position: OverlayPosition, scale: Float) {
        val state = _ui.value
        val project = state.project ?: return
        val total = project.totalDurationMs
        importer.persistReadPermission(uri)
        commitProject {
            it.copy(
                imageOverlays = it.imageOverlays + ImageOverlayItem(
                    id = TimelineLogic.newId(),
                    uri = uri.toString(),
                    position = position.name,
                    scale = scale.coerceIn(0.1f, 1f),
                    startMs = 0,
                    endMs = total,
                ),
            )
        }
    }

    fun removeImageOverlay(id: String) {
        commitProject { it.copy(imageOverlays = it.imageOverlays.filterNot { i -> i.id == id }) }
    }

    fun setMusic(uri: Uri, displayName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                importer.persistReadPermission(uri)
                val meta = importer.probeAudio(uri)
                commitProject {
                    it.copy(
                        music = MusicTrack(
                            uri = uri.toString(),
                            displayName = displayName,
                            durationMs = meta.durationMs,
                        ),
                    )
                }
            } catch (e: Exception) {
                importError.value = MediaImporter.ImportException.Kind.UNREADABLE
            }
        }
    }

    fun removeMusic() {
        commitProject { it.copy(music = null) }
    }

    fun setMusicVolume(volume: Float) {
        commitProject { project ->
            project.music?.let { project.copy(music = it.copy(volume = volume.coerceIn(0f, 2f))) }
                ?: project
        }
    }

    // region playback state ---------------------------------------------------

    fun setPlayhead(ms: Long) {
        _ui.value = _ui.value.copy(playheadMs = ms.coerceAtLeast(0))
    }

    fun setPlaying(playing: Boolean) {
        _ui.value = _ui.value.copy(isPlaying = playing)
    }

    fun seekTo(ms: Long) {
        setPlayhead(ms)
        _seekToken.value++
    }

    private val _seekToken = MutableStateFlow(0)
    val seekToken: StateFlow<Int> get() = _seekToken

    fun selectClip(id: String?) {
        _ui.value = _ui.value.copy(selectedClipId = id)
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
                // Brief "saved" indicator lifecycle handled by UI polling state.
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
        val bitmap = BitmapFactory.decodeFile(path) ?: return null
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

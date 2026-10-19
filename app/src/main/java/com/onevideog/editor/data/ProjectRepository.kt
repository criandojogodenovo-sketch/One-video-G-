package com.onevideog.editor.data

import android.content.Context
import android.graphics.Bitmap
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID

/**
 * Local project persistence.
 *
 * Layout:
 *   files/projects/<projectId>/project.json   — serialized OvgProject
 *   files/projects/<projectId>/thumb.jpg      — home screen thumbnail
 *   files/projects/<projectId>/thumbs/<clipId>_<i>.jpg — timeline thumbnails
 *
 * All methods perform blocking IO; callers must use a background dispatcher.
 * Writes are atomic (temp file + rename) so a crash can never leave a
 * half-written project file behind.
 */
class ProjectRepository(context: Context) {

    private val projectsDir: File = File(context.filesDir, "projects")

    init {
        projectsDir.mkdirs()
    }

    /** Lists all readable projects, newest update first. */
    fun list(): List<OvgProject> =
        projectsDir.listFiles()
            ?.mapNotNull { dir -> runCatching { loadFrom(dir) }.getOrNull() }
            ?.sortedByDescending { it.updatedAtEpochMs }
            ?: emptyList()

    fun create(name: String, nowEpochMs: Long = System.currentTimeMillis()): OvgProject {
        val project = OvgProject(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Project" },
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
        )
        dirFor(project.id).mkdirs()
        save(project)
        return project
    }

    fun load(id: String): OvgProject? = runCatching { loadFrom(dirFor(id)) }.getOrNull()

    fun save(project: OvgProject) {
        val dir = dirFor(project.id)
        dir.mkdirs()
        val target = File(dir, PROJECT_FILE)
        val tmp = File(dir, "$PROJECT_FILE.tmp")
        tmp.writeText(projectJson.encodeToString(project))
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Could not persist project ${project.id}" }
        }
    }

    fun rename(id: String, name: String): OvgProject? {
        val project = load(id) ?: return null
        val updated = project.copy(name = name, updatedAtEpochMs = System.currentTimeMillis())
        save(updated)
        return updated
    }

    fun delete(id: String) {
        dirFor(id).deleteRecursively()
    }

    /** Saves the home-screen thumbnail (downscaled). */
    fun saveHomeThumbnail(projectId: String, bitmap: Bitmap) {
        val dir = dirFor(projectId)
        dir.mkdirs()
        val out = File(dir, HOME_THUMB_FILE)
        val scaled = if (bitmap.width > 320) {
            val h = bitmap.height * 320 / bitmap.width
            Bitmap.createScaledBitmap(bitmap, 320, h, true)
        } else bitmap
        out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }

    fun homeThumbnailFile(projectId: String): File? {
        val f = File(dirFor(projectId), HOME_THUMB_FILE)
        return if (f.exists() && f.length() > 0) f else null
    }

    fun timelineThumbsDir(projectId: String): File =
        File(dirFor(projectId), "thumbs").apply { mkdirs() }

    private fun dirFor(id: String) = File(projectsDir, id)

    private fun loadFrom(dir: File): OvgProject? {
        val file = File(dir, PROJECT_FILE)
        if (!file.isFile) return null
        return projectJson.decodeFromString<OvgProject>(file.readText())
    }

    companion object {
        private const val PROJECT_FILE = "project.json"
        private const val HOME_THUMB_FILE = "thumb.jpg"
    }
}

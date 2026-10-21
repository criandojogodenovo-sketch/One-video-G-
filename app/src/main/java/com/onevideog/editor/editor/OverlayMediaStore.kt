package com.onevideog.editor.editor

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID

/**
 * v0.5.4 — app-local storage for imported image overlays
 * ("persistência dos recursos", docs/AUDIT_v0.5.3_problems.md §3).
 *
 * SAF read grants are best-effort: some providers refuse persistable
 * permissions, the system caps persistable grants, and the source file can
 * be moved or deleted by the user at any time. An overlay that references
 * the picked `content://` URI can therefore silently lose its pixels — the
 * preview decode fails (the overlay renders nothing, never retried in the
 * session) and the export composes without it.
 *
 * The import now copies the VALIDATED bitmap (bounded 1024 px decode,
 * JPEG 88 — a few hundred KB per image) into `files/overlay_media/` and the
 * overlay stores the `file://` URI. `contentResolver.openInputStream` reads
 * `file://` transparently, so BOTH the preview decode
 * ([OverlayBitmapRenderer.decodeImage]) and the export
 * (`ClipEffectsFactory` → `OverlayBitmapRenderer.imageBitmap`) keep working
 * with NO export change. Existing projects keep their `content://` URIs —
 * no migration.
 *
 * Copies are never deleted while the app lives: undo restores project
 * snapshots, so a remove-then-delete file would break an undone overlay.
 * The bounded per-image cost is accepted deliberately.
 */
object OverlayMediaStore {

    /** Directory that holds the app-local copies (created on demand). */
    fun mediaDir(context: Context): File =
        File(context.filesDir, "overlay_media").apply { mkdirs() }

    /**
     * Bounded, collision-free file name for a new copy. Pure (UUID is
     * java.util) — unit-tested for shape and uniqueness.
     */
    fun newFileName(): String = "img_${UUID.randomUUID()}.jpg"

    /** Builds the stored URI for a copy (file scheme, readable everywhere). */
    fun toLocalUriString(dir: File, fileName: String): String =
        Uri.fromFile(File(dir, fileName)).toString()
}

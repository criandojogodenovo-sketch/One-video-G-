package com.onevideog.editor.editor

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.onevideog.editor.data.Clip
import java.io.File
import kotlin.math.ceil

/**
 * Media import pipeline built on the Storage Access Framework.
 *
 * - No broad storage permissions: the user picks files with the document
 *   picker and read permission is persisted per document.
 * - Metadata probing and thumbnails use MediaMetadataRetriever, which streams
 *   from the file — long videos are never loaded into RAM.
 */
class MediaImporter(private val context: Context) {

    class ImportException(val kind: Kind, cause: Throwable? = null) : Exception(kind.name, cause) {
        enum class Kind { UNREADABLE, NO_VIDEO, UNSUPPORTED }
    }

    data class VideoMeta(
        val durationMs: Long,
        val width: Int,
        val height: Int,
    )

    data class AudioMeta(val durationMs: Long)

    /** Persists read permission so the document survives process restarts. */
    fun persistReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    /** True when the document can still be opened (used for missing-media detection). */
    fun isReadable(uri: Uri): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    /** Probes a video document. Throws [ImportException] with a UI-mappable kind. */
    fun probeVideo(uri: Uri): VideoMeta {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            if (durationMs <= 0) throw ImportException(ImportException.Kind.UNSUPPORTED)
            val frame = runCatching {
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }.getOrNull()
            if (frame == null && width <= 0) {
                throw ImportException(ImportException.Kind.NO_VIDEO)
            }
            val (w, h) = if (rotation == 90 || rotation == 270) height to width else width to height
            return VideoMeta(durationMs, w.coerceAtLeast(0), h.coerceAtLeast(0))
        } catch (e: ImportException) {
            throw e
        } catch (e: Exception) {
            throw ImportException(ImportException.Kind.UNREADABLE, e)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Probes an audio document (music import). Crash-hardened: every
     * failure — unreadable document, retriever runtime failure, unknown
     * duration — maps to an [ImportException] the UI can localize. Diagnostics
     * are sanitized (kind + exception class only; never file names or URIs).
     */
    fun probeAudio(uri: Uri): AudioMeta {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            if (durationMs <= 0) {
                // Metadata present but unusable (e.g. a document with an audio
                // extension that contains no decodable media).
                throw ImportException(ImportException.Kind.UNSUPPORTED)
            }
            return AudioMeta(durationMs)
        } catch (e: ImportException) {
            Log.w(DIAG_TAG, "audio probe unsupported (no decodable media)")
            throw e
        } catch (t: Throwable) {
            // Includes RuntimeException from setDataSource and any OEM
            // retriever quirk — surfaced as a localized error, never a crash.
            Log.w(DIAG_TAG, "audio probe failed: ${t.javaClass.simpleName}")
            throw ImportException(ImportException.Kind.UNREADABLE, t)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Builds a [Clip] from a picked video and generates bounded thumbnails
     * (max [MAX_THUMBS_PER_CLIP], downscaled JPEGs on disk).
     */
    fun createClip(
        uri: Uri,
        displayName: String,
        projectId: String,
        thumbsDir: File,
    ): Clip {
        val meta = probeVideo(uri)
        val clip = Clip(
            id = TimelineLogic.newId(),
            uri = uri.toString(),
            displayName = displayName,
            sourceDurationMs = meta.durationMs,
            width = meta.width,
            height = meta.height,
            trimStartMs = 0,
            trimEndMs = meta.durationMs,
        )
        generateThumbnails(clip, thumbsDir)
        return clip
    }

    /**
     * Generates up to [MAX_THUMBS_PER_CLIP] thumbnails for [clip], sampled
     * across its trimmed range, each downscaled to [THUMB_WIDTH] px.
     */
    fun generateThumbnails(clip: Clip, thumbsDir: File, thumbWidth: Int = THUMB_WIDTH): Int {
        val durationSec = (clip.trimEndMs - clip.trimStartMs) / 1000.0
        val count = ceil(durationSec / 2.0).toInt().coerceIn(1, MAX_THUMBS_PER_CLIP)
        val retriever = MediaMetadataRetriever()
        var written = 0
        try {
            retriever.setDataSource(context, Uri.parse(clip.uri))
            for (i in 0 until count) {
                val localMs = clip.trimStartMs +
                    (clip.trimEndMs - clip.trimStartMs) * i / count
                val frame = runCatching {
                    retriever.getFrameAtTime(
                        localMs * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    )
                }.getOrNull() ?: continue
                val scaled = downscale(frame, thumbWidth)
                val file = File(thumbsDir, "${clip.id}_$i.jpg")
                file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 70, it) }
                if (scaled !== frame) scaled.recycle()
                frame.recycle()
                written++
            }
        } catch (e: Exception) {
            // Thumbnails are best-effort; the clip itself remains usable.
        } finally {
            runCatching { retriever.release() }
        }
        return written
    }

    private fun downscale(bitmap: Bitmap, targetWidth: Int): Bitmap {
        if (bitmap.width <= targetWidth) return bitmap
        val h = bitmap.height.toLong() * targetWidth / bitmap.width
        return Bitmap.createScaledBitmap(bitmap, targetWidth, h.toInt().coerceAtLeast(1), true)
    }

    companion object {
        const val MAX_THUMBS_PER_CLIP = 8
        const val THUMB_WIDTH = 160
        private const val DIAG_TAG = "OvgMediaImport"
    }
}

/** Small bounded LruCache for decoded timeline/home thumbnails. */
class ThumbnailCache(maxBytes: Int = 6 * 1024 * 1024) {
    private val cache = object : android.util.LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(path: String): Bitmap? = cache.get(path)

    fun put(path: String, bitmap: Bitmap) {
        cache.put(path, bitmap)
    }
}



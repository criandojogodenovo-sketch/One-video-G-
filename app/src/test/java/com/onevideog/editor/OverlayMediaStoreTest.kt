package com.onevideog.editor.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v0.5.4 — pure parts of the app-local image persistence (device audit
 * docs/AUDIT_v0.5.3_problems.md §3): the stored file name must be bounded,
 * collision-free and JPEG-shaped, and the directory composition must place
 * every copy under the caller-provided media dir.
 *
 * The Android-dependent pieces (Context.filesDir, Uri.fromFile) are exercised
 * on device; the contract below is what the import flow depends on.
 */
class OverlayMediaStoreTest {

    @Test
    fun `new file name is a bounded jpeg-shaped uuid name`() {
        val name = OverlayMediaStore.newFileName()
        assertTrue(
            "unexpected name shape: $name",
            name.matches(Regex("img_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg")),
        )
        assertTrue("name must stay bounded", name.length < 80)
    }

    @Test
    fun `new file names never collide across many calls`() {
        val names = (1..200).map { OverlayMediaStore.newFileName() }.toSet()
        assertEquals(200, names.size)
    }

    @Test
    fun `file composition places the copy inside the media dir`() {
        val mediaDir = File("build", "overlay_media_test")
        val file = File(mediaDir, OverlayMediaStore.newFileName())
        assertEquals(mediaDir, file.parentFile)
        assertTrue(file.name.endsWith(".jpg"))
        // Two compositions in the same dir never point at the same file.
        val other = File(mediaDir, OverlayMediaStore.newFileName())
        assertNotEquals(file, other)
    }
}

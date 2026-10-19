package com.onevideog.editor

import com.onevideog.editor.diag.DiagSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Privacy sanitization contract (spec section 6: sanitize before persist/clipboard). */
class DiagSanitizerTest {

    @Test
    fun `redact hides content uris with stable fingerprint`() {
        val raw = "open failed for content://com.android.providers.media/documents/12%C3%A4bc"
        val out = DiagSanitizer.redact(raw)
        assertFalse(out.contains("documents"))
        assertFalse(out.contains("media"))
        assertTrue(out.contains("<uri:"))
        // stable: same input -> same fingerprint
        assertEquals(out, DiagSanitizer.redact(raw))
    }

    @Test
    fun `redact hides file paths`() {
        val out = DiagSanitizer.redact("cannot write /storage/emulated/0/DCIM/photo.jpg today")
        assertFalse(out.contains("/storage/emulated"))
        assertTrue(out.contains("<path:"))
    }

    @Test
    fun `redact collapses long opaque runs`() {
        val token = "ghp_" + "aB3xY9zQ".repeat(8) // 64+ chars, token-like
        val out = DiagSanitizer.redact("token=$token end")
        assertFalse(out.contains(token))
        assertTrue(out.contains("<tok>"))
    }

    @Test
    fun `redact flattens newlines and caps length`() {
        val out = DiagSanitizer.redact("line1\nline2\r\nline3\ttab", maxChars = 10)
        assertFalse(out.contains('\n'))
        assertFalse(out.contains('\r'))
        assertTrue(out.length <= 10 + 1) // + ellipsis char
    }

    @Test
    fun `sanitizeUri never exposes document id or filename`() {
        val uri = "content://com.android.externalstorage.documents/document/primary%3ADCIM%2Fmy_secret_trip.jpg"
        val out = DiagSanitizer.sanitizeUri(uri)
        assertFalse(out.contains("DCIM"))
        assertFalse(out.contains("secret"))
        assertTrue(out.startsWith("uri:content://com.android.externalstorage.documents#"))
        // same uri -> same representation; different uri -> different fp
        assertEquals(out, DiagSanitizer.sanitizeUri(uri))
        assertNotEquals(out, DiagSanitizer.sanitizeUri("content://other/thing"))
    }

    @Test
    fun `sanitizeUri handles null and garbage`() {
        assertEquals("<null>", DiagSanitizer.sanitizeUri(null))
        assertEquals("<null>", DiagSanitizer.sanitizeUri(""))
        assertTrue(DiagSanitizer.sanitizeUri("not-a-uri").isNotBlank())
    }

    @Test
    fun `fingerprint is stable and short`() {
        assertEquals(DiagSanitizer.fingerprint("abc"), DiagSanitizer.fingerprint("abc"))
        assertTrue(DiagSanitizer.fingerprint("abc").length == 8)
    }

    @Test
    fun `sanitizeFileName hides name but keeps extension`() {
        val out = DiagSanitizer.sanitizeFileName("my_private_video_final_v2.mp4")
        assertFalse(out.contains("private"))
        assertTrue(out.endsWith(".mp4"))
    }

    @Test
    fun `sanitizeFrame renders class-method-file-line`() {
        val frame = StackTraceElement("androidx.media3.transformer.Transformer", "start", "Transformer.java", 412)
        val out = DiagSanitizer.sanitizeFrame(frame)
        assertEquals("androidx.media3.transformer.Transformer.start(Transformer.java:412)", out)
    }

    @Test
    fun `redact of null and blank is empty`() {
        assertEquals("", DiagSanitizer.redact(null))
        assertEquals("", DiagSanitizer.redact("   "))
    }
}

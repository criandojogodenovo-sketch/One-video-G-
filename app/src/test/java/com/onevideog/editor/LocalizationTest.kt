package com.onevideog.editor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Localization completeness: every user-facing key in the default (English)
 * strings.xml must exist in the Portuguese strings.xml with non-empty text,
 * and vice versa. The product name stays untranslated.
 *
 * Runs on the JVM by locating res/ from the module working directory.
 */
class LocalizationTest {

    private fun findResDir(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(5) {
            val candidate = File(dir, "app/src/main/res")
            if (candidate.isDirectory) return candidate
            val candidate2 = File(dir, "src/main/res")
            if (candidate2.isDirectory) return candidate2
            dir = dir.parentFile ?: return@repeat
        }
        error("res directory not found from ${System.getProperty("user.dir")}")
    }

    private fun parseKeys(file: File): Map<String, String> {
        val content = file.readText()
        val regex = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        return regex.findAll(content).associate { match ->
            match.groupValues[1] to match.groupValues[2].trim()
        }
    }

    @Test
    fun `english and portuguese have identical key sets`() {
        val res = findResDir()
        val en = parseKeys(File(res, "values/strings.xml"))
        val pt = parseKeys(File(res, "values-pt/strings.xml"))
        assertTrue("English strings.xml should define many keys", en.size > 80)
        val missingInPt = en.keys - pt.keys
        val missingInEn = pt.keys - en.keys
        assertTrue("Keys missing in pt: $missingInPt", missingInPt.isEmpty())
        assertTrue("Keys missing in en: $missingInEn", missingInEn.isEmpty())
    }

    @Test
    fun `portuguese values are non-empty`() {
        val res = findResDir()
        val pt = parseKeys(File(res, "values-pt/strings.xml"))
        val empty = pt.filter { it.value.isEmpty() }
        assertTrue("Empty pt values: ${empty.keys}", empty.isEmpty())
    }

    @Test
    fun `product name is One Video G in both languages`() {
        val res = findResDir()
        val en = parseKeys(File(res, "values/strings.xml"))
        val pt = parseKeys(File(res, "values-pt/strings.xml"))
        assertEquals("One Video G", en["app_name"])
        assertEquals("One Video G", pt["app_name"])
    }

    @Test
    fun `format placeholders match between languages`() {
        val res = findResDir()
        val en = parseKeys(File(res, "values/strings.xml"))
        val pt = parseKeys(File(res, "values-pt/strings.xml"))
        val placeholder = Regex("""%[0-9]+\$[sd]|%%""")
        en.forEach { (key, value) ->
            val enCount = placeholder.findAll(value).map { it.value }.sorted().toList()
            val ptValue = pt[key] ?: return@forEach
            val ptCount = placeholder.findAll(ptValue).map { it.value }.sorted().toList()
            assertEquals(
                "Placeholder mismatch for key '$key'",
                enCount,
                ptCount,
            )
        }
    }

    @Test
    fun `key locales config lists both languages`() {
        val res = findResDir()
        val content = File(res, "xml/locales_config.xml").readText()
        assertTrue(content.contains("en"))
        assertTrue(content.contains("pt"))
        assertFalse(content.contains("fr"))
    }
}

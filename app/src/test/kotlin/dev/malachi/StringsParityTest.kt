package dev.malachi

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every user-facing string must exist in both locales: a key added to values/strings.xml must be
 * added to values-es/strings.xml too, and the other way round. Without this, a missing
 * translation only surfaces as a half-English screen on somebody else's phone.
 */
class StringsParityTest {

    private val keyPattern = Regex("<(string|plurals) name=\"([^\"]+)\"")

    /** Deliberately untranslated: a brand name falls back to the default locale. */
    private val untranslated = setOf("app_name")

    private fun keysOf(relativePath: String): Set<String> {
        // Gradle runs module tests with the module directory as CWD; tolerate a root runner too.
        val file = sequenceOf(File(relativePath), File("app/$relativePath")).first { it.exists() }
        return keyPattern.findAll(file.readText()).map { it.groupValues[2] }.toSet()
    }

    @Test
    fun `every english string has a spanish translation and vice versa`() {
        val english = keysOf("src/main/res/values/strings.xml")
        val spanish = keysOf("src/main/res/values-es/strings.xml")

        val missingInSpanish = english - spanish - untranslated
        val missingInEnglish = spanish - english
        assertTrue(
            missingInSpanish.isEmpty() && missingInEnglish.isEmpty(),
            "Missing in values-es: $missingInSpanish\nMissing in values: $missingInEnglish",
        )
    }

    @Test
    fun `both locales carry a meaningful number of strings`() {
        // Guards against the pattern silently matching nothing and parity passing on two
        // empty sets.
        assertTrue(keysOf("src/main/res/values/strings.xml").size > 150)
    }
}

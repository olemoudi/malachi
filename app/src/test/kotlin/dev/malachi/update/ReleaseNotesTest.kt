package dev.malachi.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The changelog that travels with a release, and the fallbacks that keep it from going silent.
 *
 * Showing nothing is indistinguishable from a release that changed nothing, so every path here
 * ends in the best available sentence rather than in an empty one.
 */
class ReleaseNotesTest {

    private val bilingual = UpdateInfo(
        versionCode = 44,
        versionName = "1.0.0-beta",
        apk = "https://github.com/olemoudi/malachi/releases/download/v1.0.0-beta/malachi.apk",
        notes = mapOf("en" to "What changed", "es" to "Qué ha cambiado"),
    )

    @Test
    fun `the device's language wins`() {
        assertEquals("Qué ha cambiado", bilingual.notesFor("es"))
        assertEquals("What changed", bilingual.notesFor("en"))
    }

    @Test
    fun `a language the manifest does not carry falls back rather than going blank`() {
        assertEquals("What changed", bilingual.notesFor("de"))
        // And with no English either, whatever is there beats nothing.
        val onlySpanish = bilingual.copy(notes = mapOf("es" to "Qué ha cambiado"))
        assertEquals("Qué ha cambiado", onlySpanish.notesFor("de"))
    }

    @Test
    fun `a release with nothing to say still parses and still installs`() {
        val quiet = UpdateInfo.parse(
            """{"versionCode": 44, "versionName": "1.0.0-beta", "apk": "https://example.test/a.apk"}""",
        )
        assertEquals(emptyMap<String, String>(), quiet?.notes)
        assertEquals("", quiet?.notesFor("es"))
        assertTrue(quiet!!.isNewerThan(43))
    }

    @Test
    fun `a changelog written wrongly costs the changelog and not the update`() {
        // The one part of this app that cannot be fixed remotely. A manifest whose notes are the
        // wrong shape — a stray string where an object belongs, a field that meant something else
        // once — must lose its notes and nothing else, or a typo in a changelog becomes a fleet
        // that never updates again.
        val stringNotes = UpdateInfo.parse(
            """{"versionCode":44,"apk":"https://example.test/a.apk","notes":"oops"}""",
        )
        assertEquals(44, stringNotes?.versionCode)
        assertEquals(emptyMap<String, String>(), stringNotes?.notes)

        val listNotes = UpdateInfo.parse(
            """{"versionCode":44,"apk":"https://example.test/a.apk","notes":[1,2]}""",
        )
        assertEquals(44, listNotes?.versionCode)

        // And one language written wrongly does not silence the others.
        val mixed = UpdateInfo.parse(
            """{"versionCode":44,"apk":"https://example.test/a.apk","notes":{"en":"fine","es":{"oops":1}}}""",
        )
        assertEquals("fine", mixed?.notesFor("es"))
    }

    @Test
    fun `what is kept on disk is bounded, and still says something`() {
        // The changelog is the one thing this app stores that it did not compose itself, and it
        // lands in the settings blob that is decoded on every read. Every file Malachi writes has
        // a bound; truncated rather than dropped, because half a sentence still says a release
        // happened and nothing at all reads as a release that changed nothing.
        val long = bilingual.copy(notes = mapOf("en" to "x".repeat(10_000)))
        val kept = long.notesWorthKeeping()
        assertEquals(UpdateInfo.MAX_NOTE_CHARS, kept.getValue("en").length)

        val many = bilingual.copy(notes = (1..40).associate { "l$it" to "note $it" })
        assertEquals(UpdateInfo.MAX_NOTE_LANGUAGES, many.notesWorthKeeping().size)

        // The ordinary case passes through untouched, or the bound would be a quiet edit.
        assertEquals(bilingual.notes, bilingual.notesWorthKeeping())
    }

    @Test
    fun `a manifest from a newer app is read as far as this one understands it`() {
        // The same promise the settings and the backup make: unknown keys are ignored, so a
        // field added later cannot stop today's app updating itself.
        val info = UpdateInfo.parse(
            """
            {"versionCode": 45, "versionName": "1.1.0-alpha", "apk": "https://example.test/a.apk",
             "notes": {"en": "hello"}, "somethingFromTheFuture": {"nested": true}}
            """.trimIndent(),
        )
        assertEquals(45, info?.versionCode)
        assertEquals("hello", info?.notesFor("en"))
    }
}

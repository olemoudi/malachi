package dev.malachi.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The manifest carries one string and the sheet draws a list, so this is where the two meet.
 * A bullet that arrived as text and stayed text is the whole regression: it reads as a paragraph
 * starting with a stray glyph, which is what the sheet looked like before it drew them itself.
 */
class ReleaseNotesTest {

    @Test
    fun `a paragraph followed by bullets comes back as one paragraph and its items`() {
        val lines = releaseNoteLines(
            "Fixes for a phone that would not settle.\n\n" +
                "• Your router keeps working.\n" +
                "• Ping works again.",
        )

        assertEquals(
            listOf(
                NoteLine("Fixes for a phone that would not settle.", bullet = false),
                NoteLine("Your router keeps working.", bullet = true),
                NoteLine("Ping works again.", bullet = true),
            ),
            lines,
        )
    }

    @Test
    fun `the glyph is dropped so the sheet can draw it in a column of its own`() {
        assertEquals(listOf(NoteLine("Ping works again.", bullet = true)), releaseNoteLines("• Ping works again."))
        assertEquals(listOf(NoteLine("Ping works again.", bullet = true)), releaseNoteLines("- Ping works again."))
    }

    @Test
    fun `a hyphenated sentence is not a list`() {
        val notes = "Wi-Fi handovers are followed properly now."
        assertEquals(listOf(NoteLine(notes, bullet = false)), releaseNoteLines(notes))
    }

    @Test
    fun `blank lines are separators rather than content`() {
        assertEquals(2, releaseNoteLines("One.\n\n\n\nTwo.\n").size)
    }
}

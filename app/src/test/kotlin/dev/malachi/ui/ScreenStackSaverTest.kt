package dev.malachi.ui

import dev.malachi.lists.BlocklistCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where the user was, across an activity the system threw away and built again.
 *
 * Configuration changes are not an edge case on the devices this app is sideloaded onto: a
 * rotation, a font-size change, a theme switch, unfolding a foldable, resizing a window in a
 * desktop mode — every one of those destroys and recreates the activity. The stack used to be a
 * plain `remember`, so all of them dropped the user back on the home screen from wherever they
 * were, which is worst exactly where it happens most: half of a guided search is spent leaving
 * the app and coming back to it.
 */
class ScreenStackSaverTest {

    private val everyScreen = listOf(
        Screen.Home,
        Screen.Apps,
        Screen.AppDetail("com.example.app"),
        Screen.Lists,
        Screen.ListCategory(BlocklistCategory.entries.first()),
        Screen.Activity,
        Screen.Diagnose,
        Screen.Rules,
        Screen.Settings,
        Screen.AdvancedSettings,
        Screen.DebugLog,
        Screen.About,
    )

    @Test
    fun `every screen survives the round trip`() {
        // Named individually rather than derived, so a destination added later fails here until
        // somebody decides how it saves — which is the only moment that decision is cheap.
        everyScreen.forEach { screen ->
            assertEquals(screen, decodeScreen(encodeScreen(screen)), "$screen did not come back")
        }
    }

    @Test
    fun `a package name with the separator in it is still read back whole`() {
        // Not hypothetical: a package name may not contain a colon, but the encoding must not
        // depend on believing that of a string that came out of a Bundle.
        val awkward = Screen.AppDetail("com.example.app${ARGUMENT_SEPARATOR}odd")
        assertEquals(awkward, decodeScreen(encodeScreen(awkward)))
    }

    @Test
    fun `an entry keeps its identity across the round trip`() {
        val entry = Entry(Screen.AppDetail("com.example.app"), id = 7)
        assertEquals(entry, decodeEntry(encodeEntry(entry)))
        // The identity is the half that matters here: two visits to one destination are two
        // entries, and confusing them is how a screen comes back showing where it was left the
        // *last* time it was open.
        assertNotEquals(decodeEntry(encodeEntry(entry)), decodeEntry(encodeEntry(entry.copy(id = 8))))
    }

    @Test
    fun `an entry that is not one is dropped rather than thrown`() {
        assertNull(decodeEntry("notanumber|Home"))
        assertNull(decodeEntry("7|SomeScreenFromTheFuture"))
        assertNull(decodeEntry("7"))
        assertNull(decodeEntry(""))
    }

    @Test
    fun `going deeper slides forward and coming back slides back`() {
        // The bug this replaces guessed the direction from the stack's depth and the
        // destination's name — "deeper unless we are landing on Home" — which is right for the
        // two journeys anybody tries first and wrong for every other one. Returning from an app's
        // detail to the list of apps is not landing on Home, so the screen you were coming *back*
        // to slid in from the right as though it were somewhere new.
        val home = Entry(Screen.Home, 0)
        val apps = Entry(Screen.Apps, 1)
        val detail = Entry(Screen.AppDetail("com.example.app"), 2)

        assertTrue(isForward(home, apps))
        assertTrue(isForward(apps, detail))
        // The case that was wrong, and the one a person meets most: back, but not to Home.
        assertFalse(isForward(detail, apps))
        assertFalse(isForward(apps, home))

        // And a second visit to a destination is forward again, though its screen is one already
        // seen — which a comparison of destinations could not tell.
        val appsAgain = Entry(Screen.Apps, 3)
        assertTrue(isForward(home, appsAgain))
        assertFalse(isForward(appsAgain, home))
    }

    @Test
    fun `anything that cannot be read back is dropped rather than thrown`() {
        // The case that happens for real: Malachi updates itself while the activity is in the
        // background, and comes back to a saved stack naming a category this version no longer
        // has. Arriving at the top is a small disappointment; a crash on resume is not.
        assertNull(decodeScreen("ListCategory${ARGUMENT_SEPARATOR}A_CATEGORY_THAT_WENT_AWAY"))
        assertNull(decodeScreen("AppDetail"))
        assertNull(decodeScreen("AppDetail$ARGUMENT_SEPARATOR"))
        assertNull(decodeScreen("SomeScreenFromTheFuture"))
        assertNull(decodeScreen(""))
    }
}

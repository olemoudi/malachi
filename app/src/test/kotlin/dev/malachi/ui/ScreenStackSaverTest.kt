package dev.malachi.ui

import dev.malachi.lists.BlocklistCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

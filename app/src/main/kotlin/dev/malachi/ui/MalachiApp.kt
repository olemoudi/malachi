package dev.malachi.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import dev.malachi.lists.BlocklistCategory
import dev.malachi.ui.screens.AboutScreen
import dev.malachi.ui.screens.ActivityScreen
import dev.malachi.ui.screens.AdvancedSettingsScreen
import dev.malachi.ui.screens.AppDetailScreen
import dev.malachi.ui.screens.AppsScreen
import dev.malachi.ui.screens.DebugLogScreen
import dev.malachi.ui.screens.DiagnoseScreen
import dev.malachi.ui.screens.HomeScreen
import dev.malachi.ui.screens.ListCategoryScreen
import dev.malachi.ui.screens.ListsScreen
import dev.malachi.ui.screens.RulesScreen
import dev.malachi.ui.screens.SettingsScreen
import dev.malachi.ui.screens.WelcomeScreen
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.malachi.ui.theme.Tokens

/** Every place the app can be. A sealed set rather than route strings: it can't be misspelled. */
sealed interface Screen {
    data object Home : Screen
    data object Apps : Screen
    data class AppDetail(val packageName: String) : Screen
    data object Lists : Screen
    data class ListCategory(val category: BlocklistCategory) : Screen
    data object Activity : Screen
    data object Diagnose : Screen
    data object Rules : Screen
    data object Settings : Screen
    data object AdvancedSettings : Screen
    data object DebugLog : Screen
    data object About : Screen
}

/** Separates a destination's name from its one argument; see [encodeScreen]. */
internal const val ARGUMENT_SEPARATOR = ':'

/**
 * One destination, as something the platform can put in a Bundle and hand back.
 *
 * A screen is a name and at most one argument, so it saves as one string. The argument is
 * whatever follows the *first* separator, so a name that happens to contain one comes back whole.
 */
internal fun encodeScreen(screen: Screen): String = when (screen) {
    is Screen.AppDetail -> "AppDetail$ARGUMENT_SEPARATOR${screen.packageName}"
    is Screen.ListCategory -> "ListCategory$ARGUMENT_SEPARATOR${screen.category.name}"
    Screen.Home -> "Home"
    Screen.Apps -> "Apps"
    Screen.Lists -> "Lists"
    Screen.Activity -> "Activity"
    Screen.Diagnose -> "Diagnose"
    Screen.Rules -> "Rules"
    Screen.Settings -> "Settings"
    Screen.AdvancedSettings -> "AdvancedSettings"
    Screen.DebugLog -> "DebugLog"
    Screen.About -> "About"
}

/**
 * The destination [saved] named, or null when this version cannot make sense of it.
 *
 * Null rather than a throw, because the case is real: Malachi updates itself while the activity is
 * in the background, and comes back to a saved stack naming a list category the new version no
 * longer has. Arriving at the top of the app is a small disappointment; a crash on resume, from a
 * self-update nobody asked to notice, is not.
 */
internal fun decodeScreen(saved: String): Screen? {
    val name = saved.substringBefore(ARGUMENT_SEPARATOR)
    val argument = saved.substringAfter(ARGUMENT_SEPARATOR, "")
    return when (name) {
        "AppDetail" -> argument.takeIf { it.isNotEmpty() }?.let { Screen.AppDetail(it) }
        "ListCategory" -> BlocklistCategory.entries.firstOrNull { it.name == argument }
            ?.let { Screen.ListCategory(it) }
        "Home" -> Screen.Home
        "Apps" -> Screen.Apps
        "Lists" -> Screen.Lists
        "Activity" -> Screen.Activity
        "Diagnose" -> Screen.Diagnose
        "Rules" -> Screen.Rules
        "Settings" -> Screen.Settings
        "AdvancedSettings" -> Screen.AdvancedSettings
        "DebugLog" -> Screen.DebugLog
        "About" -> Screen.About
        else -> null
    }
}

/** Separates an entry's identity from the destination it names; see [Entry]. */
private const val ID_SEPARATOR = '|'

/**
 * One place on the stack, with an identity of its own.
 *
 * The identity is what makes a screen's *position* — where it was scrolled to, what was typed
 * into its search box, which tab was open — belong to a visit rather than to a destination. Two
 * things need that. Coming back has to find the screen as it was left, while opening it afresh
 * later has to start at the top; and the entry that is animating away still holds its position
 * for the fraction of a second that takes, so pushing the same destination again in that moment
 * must not be mistaken for it.
 *
 * Ids only ever go up, which is also how a push is told from a pop — see [isForward].
 */
internal data class Entry(val screen: Screen, val id: Long)

/**
 * Whether moving from [from] to [to] is going deeper into the app rather than coming back out.
 *
 * This used to be guessed from the stack's depth and the destination's name — "deeper unless we
 * are landing on Home" — which is right for exactly the two journeys anyone tries first and wrong
 * for every other. Coming back from an app's detail to the list of apps is not landing on Home,
 * so the screen you were returning to slid in from the right as though it were somewhere new. The
 * animation is the only thing telling the user which way they went, and it was telling half of
 * them the wrong thing.
 *
 * An id is stamped on a push and never reused, so this is not a heuristic: a destination you are
 * returning to is one you opened earlier, and its id says so.
 */
internal fun isForward(from: Entry, to: Entry): Boolean = to.id > from.id

internal fun encodeEntry(entry: Entry): String = "${entry.id}$ID_SEPARATOR${encodeScreen(entry.screen)}"

internal fun decodeEntry(saved: String): Entry? {
    val id = saved.substringBefore(ID_SEPARATOR).toLongOrNull() ?: return null
    val screen = decodeScreen(saved.substringAfter(ID_SEPARATOR, "")) ?: return null
    return Entry(screen, id)
}

/** The stack itself, saved as one string per entry and rebuilt entry by entry. */
private val screenStackSaver = listSaver<SnapshotStateList<Entry>, String>(
    save = { stack -> stack.map(::encodeEntry) },
    restore = { saved ->
        saved.mapNotNull(::decodeEntry).ifEmpty { listOf(Entry(Screen.Home, 0L)) }.toMutableStateList()
    },
)

/**
 * The whole app: a home screen and a stack of detail screens on top of it.
 *
 * A hand-rolled stack rather than a navigation library, for the same reason there is no DI
 * framework here — there are nine destinations, none of them deep-linked, and a list plus a back
 * handler is the entire feature.
 */
@Composable
fun MalachiApp(vm: MalachiViewModel, onRequestVpnConsent: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()

    // Before anything else, once. The system's VPN dialog says this app "can monitor all network
    // traffic", which is true of the permission and untrue of what is done with it — and somebody
    // meeting that sentence with no context has been given every reason to uninstall. It is shown
    // instead of the app rather than over it, because a dialog on top of a screen full of
    // controls is read as an obstacle and dismissed.
    if (!settings.welcomeSeen) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                WelcomeScreen(
                    onStart = { vm.markWelcomeSeen(); onRequestVpnConsent() },
                    onSkip = vm::markWelcomeSeen,
                )
            }
        }
        return
    }

    // Saveable, not merely remembered. The activity is recreated for every configuration
    // change the device can produce — a rotation, a font-size change, a theme switch, unfolding
    // a foldable, resizing a window in a desktop mode — and with a plain `remember` every one of
    // those threw the user back to the home screen from wherever they were. On the phones where
    // that happens most it is also least excusable: half of a guided search is spent leaving the
    // app and coming back.
    val stack = rememberSaveable(saver = screenStackSaver) { mutableStateListOf(Entry(Screen.Home, 0L)) }
    var nextId by rememberSaveable { mutableStateOf(1L) }
    val current = stack.last()

    /**
     * Where each entry was left: its scroll position, its search box, its tab.
     *
     * A screen that is navigated away from leaves the composition, and everything it remembered
     * goes with it — so coming back rebuilt it from nothing. Scroll a list of two hundred apps,
     * open one, press back, and you were at the top again with an empty search box, which is the
     * one thing that makes a stack of screens feel like a set of unrelated pages. This keeps each
     * entry's `rememberSaveable` state while it is off screen and hands it back on return, and it
     * is the same holder a navigation library would install; the state is dropped when the entry
     * is popped, because a destination opened again later is a new visit and should start at the
     * top.
     *
     * It is saved with the activity too, so the position survives a rotation as well as a
     * journey.
     */
    val positions = rememberSaveableStateHolder()

    fun go(screen: Screen) {
        stack.add(Entry(screen, nextId))
        nextId++
    }

    fun back() {
        if (stack.size <= 1) return
        val leaving = stack.removeAt(stack.lastIndex)
        // Before it has finished animating away, which is deliberate: the holder marks a
        // still-composed entry as not-to-be-saved rather than racing its disposal.
        positions.removeState(leaving.id)
    }

    BackHandler(enabled = stack.size > 1) { back() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val motion = Tokens.motion
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                // Forward slides in from the right, back from the left: the animation carries
                // the direction, so the stack's depth is felt rather than announced.
                val forward = isForward(initialState, targetState)
                val duration = motion.medium
                (
                    slideInHorizontally(tween(duration, easing = motion.emphasized)) {
                        if (forward) it / 6 else -it / 6
                    } + fadeIn(tween(duration))
                    ) togetherWith (
                    slideOutHorizontally(tween(duration, easing = motion.emphasized)) {
                        if (forward) -it / 8 else it / 8
                    } + fadeOut(tween(motion.fast))
                    )
            },
            label = "screen",
        ) { entry ->
            // imePadding here rather than on each screen: the keyboard is a second bottom edge
            // for every screen that has a text field, and without it the list keeps its full
            // height and draws its first results underneath the keys — visible, and not tappable
            // until the keyboard is dismissed. Applying it once means a screen added later
            // inherits the fix instead of having to remember it.
            Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                positions.SaveableStateProvider(entry.id) {
                    when (val screen = entry.screen) {
                        Screen.Home -> HomeScreen(
                            vm = vm,
                            onRequestVpnConsent = onRequestVpnConsent,
                            onOpen = ::go,
                        )
                        Screen.Apps -> AppsScreen(vm, onBack = ::back, onOpenApp = { go(Screen.AppDetail(it)) })
                        is Screen.AppDetail -> AppDetailScreen(
                            vm = vm,
                            packageName = screen.packageName,
                            onBack = ::back,
                            // Started here rather than on arrival, so the screen never has to guess
                            // whether it was opened to watch this app or merely reached from the
                            // settings row — and so choosing an app is always a deliberate act.
                            onDiagnose = { vm.startDiagnosing(screen.packageName); go(Screen.Diagnose) },
                        )
                        Screen.Lists -> ListsScreen(
                            vm = vm,
                            onBack = ::back,
                            onOpenCategory = { go(Screen.ListCategory(it)) },
                        )
                        is Screen.ListCategory -> ListCategoryScreen(vm, screen.category, onBack = ::back)
                        Screen.Activity -> ActivityScreen(
                            vm = vm,
                            onBack = ::back,
                            onOpenApp = { go(Screen.AppDetail(it)) },
                        )
                        Screen.Diagnose -> DiagnoseScreen(vm, onBack = ::back)
                        Screen.Rules -> RulesScreen(vm, onBack = ::back)
                        Screen.Settings -> SettingsScreen(
                            vm = vm,
                            onBack = ::back,
                            onOpenAdvanced = { go(Screen.AdvancedSettings) },
                            onOpenDebugLog = { go(Screen.DebugLog) },
                            onOpenDiagnose = { go(Screen.Diagnose) },
                            onOpenAbout = { go(Screen.About) },
                        )
                        Screen.AdvancedSettings -> AdvancedSettingsScreen(vm, onBack = ::back)
                        Screen.DebugLog -> DebugLogScreen(onBack = ::back)
                        Screen.About -> AboutScreen(vm, onBack = ::back)
                    }
                }
            }
        }
    }
}

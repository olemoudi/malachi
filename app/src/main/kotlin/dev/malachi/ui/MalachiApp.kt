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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.malachi.lists.BlocklistCategory
import dev.malachi.ui.screens.AboutScreen
import dev.malachi.ui.screens.ActivityScreen
import dev.malachi.ui.screens.AdvancedSettingsScreen
import dev.malachi.ui.screens.AppDetailScreen
import dev.malachi.ui.screens.AppsScreen
import dev.malachi.ui.screens.DebugLogScreen
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
    data object Rules : Screen
    data object Settings : Screen
    data object AdvancedSettings : Screen
    data object DebugLog : Screen
    data object About : Screen
}

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

    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val current = stack.last()

    fun go(screen: Screen) = stack.add(screen)
    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    BackHandler(enabled = stack.size > 1) { back() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val motion = Tokens.motion
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                // Forward slides in from the right, back from the left: the animation carries
                // the direction, so the stack's depth is felt rather than announced.
                val forward = stack.size > 1 && targetState != Screen.Home
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
        ) { screen ->
            // imePadding here rather than on each screen: the keyboard is a second bottom edge
            // for every screen that has a text field, and without it the list keeps its full
            // height and draws its first results underneath the keys — visible, and not tappable
            // until the keyboard is dismissed. Applying it once means a screen added later
            // inherits the fix instead of having to remember it.
            Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                when (screen) {
                    Screen.Home -> HomeScreen(
                        vm = vm,
                        onRequestVpnConsent = onRequestVpnConsent,
                        onOpen = ::go,
                    )
                    Screen.Apps -> AppsScreen(vm, onBack = ::back, onOpenApp = { go(Screen.AppDetail(it)) })
                    is Screen.AppDetail -> AppDetailScreen(vm, screen.packageName, onBack = ::back)
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
                    Screen.Rules -> RulesScreen(vm, onBack = ::back)
                    Screen.Settings -> SettingsScreen(
                        vm = vm,
                        onBack = ::back,
                        onOpenAdvanced = { go(Screen.AdvancedSettings) },
                        onOpenDebugLog = { go(Screen.DebugLog) },
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

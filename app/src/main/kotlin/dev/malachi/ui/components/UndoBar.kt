package dev.malachi.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.malachi.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Says what just happened, and offers to take it back.
 *
 * Writing a rule is one tap from a list of domains — which makes it one tap from the *wrong*
 * domain, on a screen where the rows move as new lookups arrive. Until this existed the tap had
 * no feedback at all: the dialog closed, one row's colour changed somewhere below the fold, and
 * whether anything had been written was a question you answered by going to another screen.
 */
@Stable
class UndoBar internal constructor(
    val host: SnackbarHostState,
    private val scope: CoroutineScope,
    private val undoLabel: String,
) {
    /**
     * Shows [message] with an undo action. A second edit replaces the first message rather than
     * queueing behind it: somebody writing three rules in a row should not have to wait out two
     * stale offers to undo the wrong one.
     */
    fun show(message: String, onUndo: () -> Unit) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            val result = host.showSnackbar(
                message = message,
                actionLabel = undoLabel,
                withDismissAction = true,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onUndo()
        }
    }
}

@Composable
fun rememberUndoBar(): UndoBar {
    val host = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.action_undo)
    return remember(host, scope, label) { UndoBar(host, scope, label) }
}

/** Where the bar draws. Put it last in a Box so it sits over the content it is about. */
@Composable
fun UndoBarHost(bar: UndoBar, modifier: Modifier = Modifier) {
    SnackbarHost(bar.host, modifier)
}

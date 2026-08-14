package dev.malachi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import dev.malachi.R
import dev.malachi.ui.components.UndoBar

/**
 * Says which rule was just written, and offers to take it back.
 *
 * Every screen that writes a rule does it from a list of domains the tunnel resolved, one tap
 * away from the row above and the row below — and until this existed the tap produced no
 * feedback whatsoever. Shared rather than repeated because the four sentences and the undo have
 * to be the same wherever a rule is written, and because a screen that forgot the undo would
 * look exactly like a screen that had it.
 */
@Stable
class RuleAnnouncer internal constructor(
    private val bar: UndoBar,
    private val blockedEverywhere: String,
    private val allowedEverywhere: String,
    private val blockedInApp: String,
    private val allowedInApp: String,
) {
    /**
     * [edit] is null when the text was not a domain, which is the caller's error to show in its
     * own field — there is nothing to announce and nothing to undo.
     */
    fun announce(edit: MalachiViewModel.RuleEdit?, blocked: Boolean, appLabel: String? = null) {
        if (edit == null) return
        val message = when {
            appLabel != null && blocked -> String.format(blockedInApp, edit.domain, appLabel)
            appLabel != null -> String.format(allowedInApp, edit.domain, appLabel)
            blocked -> String.format(blockedEverywhere, edit.domain)
            else -> String.format(allowedEverywhere, edit.domain)
        }
        bar.show(message, edit.undo)
    }
}

@Composable
fun rememberRuleAnnouncer(bar: UndoBar): RuleAnnouncer {
    val blockedEverywhere = stringResource(R.string.rule_added_blocked)
    val allowedEverywhere = stringResource(R.string.rule_added_allowed)
    val blockedInApp = stringResource(R.string.rule_added_blocked_in)
    val allowedInApp = stringResource(R.string.rule_added_allowed_in)
    return remember(bar, blockedEverywhere, allowedEverywhere, blockedInApp, allowedInApp) {
        RuleAnnouncer(bar, blockedEverywhere, allowedEverywhere, blockedInApp, allowedInApp)
    }
}

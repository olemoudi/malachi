package dev.malachi.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.malachi.R
import dev.malachi.debug.DebugLog
import dev.malachi.ui.components.MalachiTopBar
import dev.malachi.ui.theme.MonoSmall
import dev.malachi.ui.theme.Tokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the app has been doing internally: the tunnel coming up, lists refreshing, the updater.
 *
 * Not a feature so much as the thing that makes the others supportable. A sideloaded app has no
 * crash reporting and no support channel, so when something doesn't work the only useful reply
 * is "open the log and paste it" — which needs the log to be there, to survive the process
 * restart an update causes, and to be one tap from the clipboard.
 */
@Composable
fun DebugLogScreen(onBack: () -> Unit) {
    val entries by DebugLog.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val spacing = Tokens.spacing
    val time = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(Modifier.fillMaxSize()) {
        MalachiTopBar(stringResource(R.string.settings_debug_log), onBack) {
            IconButton(onClick = { copyToClipboard(context, DebugLog.format()) }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.action_copy))
            }
            IconButton(onClick = { DebugLog.clear() }) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.action_clear))
            }
        }

        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.debug_log_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(spacing.screen),
            )
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(spacing.screen, 0.dp, spacing.screen, spacing.xxl),
        ) {
            // Newest first: the line you need is almost always the last thing that happened.
            items(entries.asReversed()) { entry ->
                Text(
                    "${time.format(Date(entry.epochMillis))} ${entry.level}/${entry.tag}: ${entry.message}",
                    style = MonoSmall,
                    color = when (entry.level) {
                        'E' -> MaterialTheme.colorScheme.error
                        'W' -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("malachi-log", text))
}

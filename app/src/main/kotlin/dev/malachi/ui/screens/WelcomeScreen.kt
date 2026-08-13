package dev.malachi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.malachi.R
import dev.malachi.ui.components.MalachiCard
import dev.malachi.ui.components.PrimaryAction
import dev.malachi.ui.components.SecondaryAction
import dev.malachi.ui.theme.Tokens

/**
 * The first thing anybody sees, once.
 *
 * It exists for one moment that this app cannot avoid and cannot soften: Android's own dialog,
 * which says that Malachi "can monitor all network traffic" and asks for a connection request.
 * That sentence is written for a VPN that carries a phone's whole life to somebody else's
 * server, and there is no way to reword it — so the only defence is to have said, a screen
 * earlier and in the user's own language, what is actually about to happen and what is not.
 *
 * Three claims, in the order somebody would ask them: what it does, why the scary dialog, and
 * where anything goes. Everything here is checkable against the code; nothing here is marketing.
 */
@Composable
fun WelcomeScreen(onStart: () -> Unit, onSkip: () -> Unit) {
    val spacing = Tokens.spacing

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing.screen, spacing.xxl, spacing.screen, spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        item {
            Text(
                stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        item {
            Text(
                stringResource(R.string.welcome_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = spacing.lg),
            )
        }

        item {
            Point(
                icon = Icons.Filled.Block,
                title = stringResource(R.string.welcome_what_title),
                body = stringResource(R.string.welcome_what_body),
            )
        }
        item {
            Point(
                icon = Icons.Filled.VpnKey,
                title = stringResource(R.string.welcome_vpn_title),
                body = stringResource(R.string.welcome_vpn_body),
            )
        }
        item {
            Point(
                icon = Icons.Filled.Lock,
                title = stringResource(R.string.welcome_privacy_title),
                body = stringResource(R.string.welcome_privacy_body),
            )
        }

        item {
            Spacer(Modifier.padding(top = spacing.lg))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A way past this that is not the switch, because somebody who wants to look
                // around before granting anything is being sensible, not awkward.
                SecondaryAction(
                    text = stringResource(R.string.welcome_later),
                    onClick = onSkip,
                    onContainer = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrimaryAction(
                    text = stringResource(R.string.welcome_start),
                    onClick = onStart,
                    onContainer = MaterialTheme.colorScheme.onPrimary,
                    container = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun Point(icon: ImageVector, title: String, body: String) {
    val spacing = Tokens.spacing
    MalachiCard {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.Top) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp).padding(top = 2.dp),
            )
            Spacer(Modifier.width(spacing.md))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

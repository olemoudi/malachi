package dev.malachi.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.malachi.Distribution
import dev.malachi.data.UpdateChannel
import dev.malachi.R
import dev.malachi.ui.MalachiViewModel
import dev.malachi.ui.components.MalachiCard
import dev.malachi.ui.components.MalachiTopBar
import dev.malachi.ui.components.NavRow
import dev.malachi.ui.components.SectionHeader
import dev.malachi.ui.theme.Tokens

/** What this is, what it can't do, and where the code lives. */
@Composable
fun AboutScreen(vm: MalachiViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val spacing = Tokens.spacing
    val settings by vm.settings.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        MalachiTopBar(stringResource(R.string.settings_about), onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(spacing.screen, 0.dp, spacing.screen, spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item {
                MalachiCard {
                    Column(Modifier.padding(spacing.lg)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.about_version, vm.versionName, vm.versionCode) +
                                // So a screenshot answers "which stream is this phone on".
                                " · " + stringResource(channelName(settings.updateChannel)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.about_tagline),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = spacing.md),
                        )
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.about_limits_title)) }
            item {
                MalachiCard {
                    Column(Modifier.padding(spacing.lg)) {
                        listOf(
                            R.string.about_limit_doh,
                            R.string.about_limit_hardcoded_ip,
                            R.string.about_limit_in_page,
                            R.string.about_limit_one_vpn,
                            R.string.about_limit_connectivity_checks,
                        ).forEach { line ->
                            Text(
                                "· " + stringResource(line),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = spacing.sm),
                            )
                        }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.about_source_title)) }
            item {
                NavRow(
                    icon = Icons.Filled.Code,
                    title = stringResource(R.string.about_repository),
                    subtitle = Distribution.REPO_URL,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Distribution.REPO_URL.toUri())
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
            }
        }
    }
}

/** The channel's name, so the version line says which stream this build came from. */
private fun channelName(channel: UpdateChannel) = when (channel) {
    UpdateChannel.STABLE -> R.string.settings_channel_stable
    UpdateChannel.TESTING -> R.string.settings_channel_testing
}

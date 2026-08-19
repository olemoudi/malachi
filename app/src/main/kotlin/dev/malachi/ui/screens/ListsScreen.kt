package dev.malachi.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.malachi.R
import dev.malachi.lists.BlocklistCatalog
import dev.malachi.lists.BlocklistCategory
import dev.malachi.lists.BreakageRisk
import dev.malachi.lists.ListState
import dev.malachi.ui.MalachiViewModel
import dev.malachi.ui.components.CardGroup
import dev.malachi.ui.components.CardPosition
import dev.malachi.ui.components.MalachiCard
import dev.malachi.ui.components.MalachiTopBar
import dev.malachi.ui.components.NavRow
import dev.malachi.ui.components.SectionHeader
import dev.malachi.ui.components.RiskMarkHeight
import dev.malachi.ui.components.RiskMarks
import dev.malachi.ui.components.riskLabel
import dev.malachi.ui.components.SwitchRow
import dev.malachi.ui.components.ValueRow
import dev.malachi.ui.components.cardPosition
import dev.malachi.ui.theme.MonoSmall
import dev.malachi.ui.theme.Tokens
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/**
 * The catalogue, one category at a time.
 *
 * Fifty-odd lists in a single column is a wall nobody reads, and the two that matter — the ones
 * already on — are lost in it. So this screen answers only "what kinds of thing can I block, and
 * how much of each is on", and the choosing happens one category down.
 *
 * The refresh control stays here rather than moving into each category: it refreshes everything
 * subscribed, and a button that means "all of them" belongs on the screen that shows all of them.
 */
@Composable
fun ListsScreen(vm: MalachiViewModel, onBack: () -> Unit, onOpenCategory: (BlocklistCategory) -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val refreshing by vm.refreshingLists.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing

    // Above everything, because this is the screen somebody opens when an app has just broken —
    // and the answer to that is nearly always the list they turned on last. It is absent for
    // anybody who has never turned one on, so it costs the other case nothing.
    val recent = remember(settings.listChoices, settings.listEnabledAtMs) {
        BlocklistCatalog.recentlyEnabled(settings.listChoices, settings.listEnabledAtMs, RECENT_SHOWN)
    }

    Column(Modifier.fillMaxSize()) {
        MalachiTopBar(stringResource(R.string.nav_lists), onBack) {
            if (refreshing) {
                CircularProgressIndicator(Modifier.size(20.dp).padding(end = spacing.sm), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = vm::refreshLists) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                }
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(spacing.screen, 0.dp, spacing.screen, spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            if (recent.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = stringResource(R.string.lists_recent_title),
                        supporting = stringResource(R.string.lists_recent_hint),
                    )
                }
                item {
                    CardGroup {
                        recent.forEachIndexed { index, source ->
                            SwitchRow(
                                title = source.title,
                                subtitle = turnedOn(settings.listEnabledAtMs[source.id]),
                                checked = true,
                                onCheckedChange = { vm.setListEnabled(source.id, it) },
                                position = cardPosition(index, recent.size),
                            )
                        }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.lists_schedule_title)) }
            item {
                CardGroup {
                    ValueRow(
                        title = stringResource(R.string.lists_schedule_every),
                        subtitle = stringResource(R.string.lists_schedule_every_subtitle),
                        value = stringResource(R.string.lists_schedule_hours, settings.listUpdateHours),
                        onClick = { vm.setListUpdateHours(nextInterval(settings.listUpdateHours)) },
                        position = cardPosition(0, 2),
                    )
                    SwitchRow(
                        title = stringResource(R.string.lists_wifi_only),
                        subtitle = stringResource(R.string.lists_wifi_only_subtitle),
                        checked = settings.listUpdateWifiOnly,
                        onCheckedChange = vm::setListUpdateWifiOnly,
                        position = cardPosition(1, 2),
                    )
                }
            }

            item {
                SectionHeader(
                    title = stringResource(R.string.lists_catalogue_title),
                    supporting = stringResource(R.string.lists_catalogue_hint),
                )
            }
            item {
                CardGroup {
                    val categories = BlocklistCategory.entries
                    categories.forEachIndexed { index, category ->
                        val all = BlocklistCatalog.inCategory(category)
                        NavRow(
                            icon = categoryIcon(category),
                            title = stringResource(categoryTitle(category)),
                            subtitle = stringResource(
                                R.string.lists_category_enabled,
                                BlocklistCatalog.enabledCount(category, settings.listChoices),
                                all.size,
                            ),
                            onClick = { onOpenCategory(category) },
                            position = cardPosition(index, categories.size),
                        )
                    }
                }
            }
        }
    }
}

/**
 * One category's lists, grouped by how much trouble each is likely to cause.
 *
 * The category answers "what does this block"; the sub-groups answer the question that actually
 * decides whether to switch one on, which is "what will this cost me". Both matter and they are
 * genuinely independent: the safest and the most dangerous list in the catalogue are both ad
 * blocklists, and sorting a category by size alone puts the dangerous one on top.
 *
 * Each row carries the URL it is fetched from. That is not for most people, and it is small and
 * grey for that reason — but a filter that decides what a phone can reach should be willing to
 * say where its rules came from, and anybody who wants to read a list before trusting it needs
 * exactly this one string.
 */
@Composable
fun ListCategoryScreen(
    vm: MalachiViewModel,
    category: BlocklistCategory,
    onBack: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val states by vm.listStates.collectAsStateWithLifecycle()
    val spacing = Tokens.spacing
    val sources = BlocklistCatalog.inCategory(category)

    Column(Modifier.fillMaxSize()) {
        MalachiTopBar(stringResource(categoryTitle(category)), onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(spacing.screen, 0.dp, spacing.screen, spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item {
                SectionHeader(
                    title = stringResource(
                        R.string.lists_category_enabled,
                        BlocklistCatalog.enabledCount(category, settings.listChoices),
                        sources.size,
                    ),
                    supporting = stringResource(categoryHint(category)),
                )
            }

            BreakageRisk.entries.forEach { risk ->
                val tier = BlocklistCatalog.inCategory(category, risk)
                if (tier.isEmpty()) return@forEach

                item(key = "header-$risk") {
                    SectionHeader(
                        title = stringResource(riskLabel(risk)),
                        supporting = stringResource(riskHint(risk)),
                        // The same dots that mark a verdict elsewhere, here on the heading that
                        // names the tier. This is where the scale is learned: somebody choosing
                        // a list reads "two amber dots" beside the words that explain it, and
                        // meets those two dots again months later beside the domain that broke
                        // something — without a legend having to teach it twice.
                        leading = { RiskMarks(risk, Modifier.height(RiskMarkHeight)) },
                    )
                }
                item(key = "group-$risk") {
                    CardGroup {
                        tier.forEachIndexed { index, source ->
                            ListRow(
                                title = source.title,
                                maintainer = source.maintainer,
                                description = stringResource(listDescription(source.id)),
                                url = source.url,
                                state = states[source.id],
                                approximateEntries = source.approximateEntries,
                                enabled = BlocklistCatalog.isEnabled(source.id, settings.listChoices),
                                enabledAtMs = settings.listEnabledAtMs[source.id],
                                onToggle = { vm.setListEnabled(source.id, it) },
                                position = cardPosition(index, tier.size),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListRow(
    title: String,
    maintainer: String,
    description: String,
    url: String,
    state: ListState?,
    approximateEntries: Int,
    enabled: Boolean,
    enabledAtMs: Long?,
    onToggle: (Boolean) -> Unit,
    position: CardPosition,
) {
    val spacing = Tokens.spacing
    val numbers = NumberFormat.getInstance()
    MalachiCard(onClick = { onToggle(!enabled) }, position = position) {
        Row(Modifier.padding(spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(top = 2.dp))
                Text(
                    when {
                        state?.lastError?.isNotEmpty() == true ->
                            stringResource(R.string.lists_last_error, state.lastError)
                        state != null && state.isDownloaded -> stringResource(
                            R.string.lists_entries_updated,
                            numbers.format(state.entries),
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(state.fetchedAtMs)),
                        )
                        enabled -> stringResource(R.string.lists_pending)
                        else -> stringResource(R.string.lists_about, maintainer, numbers.format(approximateEntries))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state?.lastError?.isNotEmpty() == true) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                // When this one was switched on, so the list that broke something last week can be
                // recognised without remembering the day it was added.
                turnedOn(enabledAtMs)?.let { since ->
                    Text(
                        since,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Where the rules come from. One line, and shortened from the middle rather than
                // the end: most of the catalogue is fetched from one host, so a plain ellipsis
                // truncates every row to the same forty characters of "adguardteam.github.io/Host…"
                // and hides the only part that differs.
                Text(
                    shortSource(url),
                    style = MonoSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(spacing.md))
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

/**
 * "Turned on 3 days ago", in the device's own words, or null when nothing was recorded.
 *
 * Relative rather than a date, because the question being asked is "which of these did I add
 * last" and a short date makes somebody count backwards to answer it. The platform formats it,
 * so it is translated everywhere the app is and reads naturally in each language.
 */
@Composable
private fun turnedOn(atMs: Long?): String? {
    if (atMs == null || atMs <= 0L) return null
    // One instant for the life of the composition: rows compared against different "now"s could
    // disagree about their order, which is the one thing this line exists to settle.
    val now = remember { System.currentTimeMillis() }
    return stringResource(
        R.string.lists_enabled_when,
        DateUtils.getRelativeTimeSpanString(atMs, now, DateUtils.MINUTE_IN_MILLIS),
    )
}

/**
 * A download URL as one short line: the host, and the filename that distinguishes it.
 *
 * The scheme is dropped because a test guarantees every one of them is https, so it is eight
 * characters that say nothing. The middle of the path goes because "which host" and "which file"
 * are the two questions somebody reading this has, and on the registry the answer to the second
 * is the last segment — `filter_44.txt` — sitting behind a directory name that is the same for
 * all fifty of them.
 */
internal fun shortSource(url: String): String {
    val withoutScheme = url.substringAfter("://")
    val host = withoutScheme.substringBefore('/')
    val path = withoutScheme.removePrefix(host).trim('/')
    if (path.isEmpty()) return host
    val last = path.substringAfterLast('/')
    return if (path == last) "$host/$path" else "$host/…/$last"
}

/**
 * How many of the recently enabled lists the shortcut at the top shows.
 *
 * Not all of them: somebody who has turned on twenty lists would get the catalogue again, sorted
 * by date, above the catalogue. Everything past this still carries its own date on its own row,
 * so nothing is hidden — only the shortcut is bounded.
 */
private const val RECENT_SHOWN = 5

/** Cycles the refresh interval through the handful of values anyone actually wants. */
private fun nextInterval(current: Int): Int = when (current) {
    in 0..1 -> 6
    in 2..6 -> 12
    in 7..12 -> 24
    in 13..24 -> 72
    else -> 1
}

private fun categoryTitle(category: BlocklistCategory) = when (category) {
    BlocklistCategory.ADS -> R.string.lists_category_ads
    BlocklistCategory.PRIVACY -> R.string.lists_category_privacy
    BlocklistCategory.ANNOYANCES -> R.string.lists_category_annoyances
    BlocklistCategory.SECURITY -> R.string.lists_category_security
    BlocklistCategory.NATIVE -> R.string.lists_category_native
    BlocklistCategory.REGIONAL -> R.string.lists_category_regional
    BlocklistCategory.OTHER -> R.string.lists_category_other
}

private fun categoryHint(category: BlocklistCategory) = when (category) {
    BlocklistCategory.ADS -> R.string.lists_category_ads_hint
    BlocklistCategory.PRIVACY -> R.string.lists_category_privacy_hint
    BlocklistCategory.ANNOYANCES -> R.string.lists_category_annoyances_hint
    BlocklistCategory.SECURITY -> R.string.lists_category_security_hint
    BlocklistCategory.NATIVE -> R.string.lists_category_native_hint
    BlocklistCategory.REGIONAL -> R.string.lists_category_regional_hint
    BlocklistCategory.OTHER -> R.string.lists_category_other_hint
}

private fun riskHint(risk: BreakageRisk) = when (risk) {
    BreakageRisk.SAFE -> R.string.lists_risk_safe_hint
    BreakageRisk.MODERATE -> R.string.lists_risk_moderate_hint
    BreakageRisk.AGGRESSIVE -> R.string.lists_risk_aggressive_hint
}

private fun categoryIcon(category: BlocklistCategory) = when (category) {
    BlocklistCategory.ADS -> Icons.Filled.Block
    BlocklistCategory.PRIVACY -> Icons.Filled.VisibilityOff
    BlocklistCategory.ANNOYANCES -> Icons.Filled.NotificationsOff
    BlocklistCategory.SECURITY -> Icons.Filled.Shield
    BlocklistCategory.NATIVE -> Icons.Filled.PhoneAndroid
    BlocklistCategory.REGIONAL -> Icons.Filled.Translate
    BlocklistCategory.OTHER -> Icons.Filled.Tune
}

/**
 * The human explanation of each source. Kept here rather than in the catalog so the catalog
 * stays a plain data object with no Android dependency, and so the text can be translated.
 *
 * Every id in the catalogue has an entry; `CatalogTest` fails the build when one doesn't, because
 * the fallback is a list that describes itself as "a subscribed list" and tells nobody anything.
 */
internal fun listDescription(id: String) = when (id) {
    "adguard-dns" -> R.string.list_adguard_dns
    "adaway" -> R.string.list_adaway
    "easyprivacy" -> R.string.list_easyprivacy
    "yoyo" -> R.string.list_yoyo
    "oisd-small" -> R.string.list_oisd_small
    "oisd-big" -> R.string.list_oisd_big
    "oisd-nsfw" -> R.string.list_oisd_nsfw
    "hagezi-normal" -> R.string.list_hagezi_normal
    "hagezi-pro" -> R.string.list_hagezi_pro
    "hagezi-tif" -> R.string.list_hagezi_tif
    "hagezi-badware" -> R.string.list_hagezi_badware
    "1hosts-lite" -> R.string.list_1hosts_lite
    "stevenblack" -> R.string.list_stevenblack
    "someonewhocares" -> R.string.list_someonewhocares
    "shadowwhisperer-tracking" -> R.string.list_shadowwhisperer_tracking
    "shadowwhisperer-malware" -> R.string.list_shadowwhisperer_malware
    "frogeye-first" -> R.string.list_frogeye_first
    "adguard-popups" -> R.string.list_adguard_popups
    "push-notifications" -> R.string.list_push_notifications
    "nocoin" -> R.string.list_nocoin
    "phishing-army" -> R.string.list_phishing_army
    "phishtank" -> R.string.list_phishtank
    "dandelion-malware" -> R.string.list_dandelion_malware
    "urlhaus" -> R.string.list_urlhaus
    "stalkerware" -> R.string.list_stalkerware
    "native-apple" -> R.string.list_native_apple
    "native-samsung" -> R.string.list_native_samsung
    "native-xiaomi" -> R.string.list_native_xiaomi
    "native-windows" -> R.string.list_native_windows
    "native-vivo" -> R.string.list_native_vivo
    "native-oppo" -> R.string.list_native_oppo
    "smart-tv" -> R.string.list_smart_tv
    "no-google" -> R.string.list_no_google
    "gambling" -> R.string.list_gambling
    "piracy" -> R.string.list_piracy
    "dating" -> R.string.list_dating
    "regional-chn-antiad" -> R.string.list_regional_chn_antiad
    "regional-chn-adrules" -> R.string.list_regional_chn_adrules
    "regional-kor-listkr" -> R.string.list_regional_kor_listkr
    "regional-kor-yous" -> R.string.list_regional_kor_yous
    "regional-pol-pihole" -> R.string.list_regional_pol_pihole
    "regional-pol-cert" -> R.string.list_regional_pol_cert
    "regional-irn-persian" -> R.string.list_regional_irn_persian
    "regional-tur-adlist" -> R.string.list_regional_tur_adlist
    "regional-tur-hosts" -> R.string.list_regional_tur_hosts
    "regional-idn-abpindo" -> R.string.list_regional_idn_abpindo
    "regional-vnm-abpvn" -> R.string.list_regional_vnm_abpvn
    "regional-nor-nordic" -> R.string.list_regional_nor_nordic
    "regional-swe-frellwit" -> R.string.list_regional_swe_frellwit
    "regional-hun-hufilter" -> R.string.list_regional_hun_hufilter
    "regional-isr-hebrew" -> R.string.list_regional_isr_hebrew
    "regional-mkd-pihole" -> R.string.list_regional_mkd_pihole
    "regional-lit-easylist" -> R.string.list_regional_lit_easylist
    "regional-ukr-security" -> R.string.list_regional_ukr_security
    else -> R.string.list_unknown
}

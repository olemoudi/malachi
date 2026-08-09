package dev.malachi.data

import dev.malachi.filter.AppDomainRule
import dev.malachi.filter.dns.BlockAnswer
import kotlinx.serialization.Serializable

/**
 * Which apps the filter covers.
 *
 * Both directions exist because both requests are real: "block ads everywhere, but leave my
 * banking app alone" and "I only want this one game filtered". They are the same switch read
 * from opposite ends, so they share one setting rather than becoming two features that can
 * disagree with each other.
 */
enum class AppScopeMode { ALL_EXCEPT, ONLY_SELECTED }

/** What a blocked lookup is answered with; see [BlockAnswer] for why this is the user's choice. */
enum class BlockAnswerMode {
    NULL_ADDRESS, NXDOMAIN, REFUSED;

    fun toBlockAnswer(): BlockAnswer = when (this) {
        NULL_ADDRESS -> BlockAnswer.NULL_ADDRESS
        NXDOMAIN -> BlockAnswer.NXDOMAIN
        REFUSED -> BlockAnswer.REFUSED
    }
}

/** Where allowed lookups are sent. [SYSTEM] follows whatever the current network hands out. */
enum class UpstreamDns(val addresses: List<String>) {
    SYSTEM(emptyList()),
    CLOUDFLARE(listOf("1.1.1.1", "1.0.0.1")),
    GOOGLE(listOf("8.8.8.8", "8.8.4.4")),
    QUAD9(listOf("9.9.9.9", "149.112.112.112")),
    ADGUARD(listOf("94.140.14.140", "94.140.14.141")),
    CUSTOM(emptyList()),
}

/**
 * How hard to work at stopping an app from going around the filter.
 *
 * A VPN routes by address, not by port, so "capture all DNS" is not something the platform can
 * be asked for directly — each resolver an app might use has to be routed into the tunnel by
 * name. Each step up catches more and risks more, so it is a dial rather than a switch.
 */
enum class BypassGuard {
    /** Only the tunnel's own resolver. Nothing an app does can break because of us. */
    OFF,

    /** Also the resolvers this network handed out, which is where a hardcoded lookup usually goes. */
    SYSTEM_RESOLVERS,

    /** Also the well-known public resolvers apps embed to escape network-level filtering. */
    PUBLIC_RESOLVERS,
}

/** A user rule scoped to one app, in persistable form. */
@Serializable
data class AppRule(
    val domain: String,
    val packageName: String,
    val block: Boolean,
) {
    fun toDomainRule() = AppDomainRule(domain = domain, packageName = packageName, block = block)
}

/**
 * Everything the user has decided. One JSON blob in DataStore (see [SettingsStore]).
 *
 * Every field has a default and decoding ignores unknown keys, so adding a setting never needs a
 * migration and an install that skipped versions still reads. Only a *rename* would, and that is
 * handled in the store's read path rather than by breaking existing installs.
 */
@Serializable
data class MalachiSettings(
    /** The user's intent, not the tunnel's state: it stays true while a tunnel is down. */
    val filteringEnabled: Boolean = false,

    /** Wall clock until which filtering is suspended; 0 when it isn't. See [isPaused]. */
    val pausedUntilMs: Long = 0,

    val blockAnswer: BlockAnswerMode = BlockAnswerMode.NULL_ADDRESS,

    val scopeMode: AppScopeMode = AppScopeMode.ALL_EXCEPT,

    /** Apps that keep unfiltered DNS in [AppScopeMode.ALL_EXCEPT]. */
    val excludedApps: Set<String> = emptySet(),

    /** The only apps that get filtered in [AppScopeMode.ONLY_SELECTED]. */
    val includedApps: Set<String> = emptySet(),

    val userBlocked: Set<String> = emptySet(),
    val userAllowed: Set<String> = emptySet(),
    val appRules: List<AppRule> = emptyList(),

    /** Catalog id → enabled. A source absent from the map uses the catalog's own default. */
    val listChoices: Map<String, Boolean> = emptyMap(),

    val listUpdateHours: Int = 24,
    val listUpdateWifiOnly: Boolean = true,

    val upstream: UpstreamDns = UpstreamDns.SYSTEM,
    val customUpstream: String = "",

    val bypassGuard: BypassGuard = BypassGuard.SYSTEM_RESOLVERS,

    val queryLogEnabled: Boolean = true,

    /** Wi-Fi-only self-update, for a phone on a small data plan. */
    val updateWifiOnly: Boolean = false,

    /**
     * The always-on suggestion has been dismissed.
     *
     * Persisted because whether always-on is *already* configured is something a normal app is
     * not allowed to read on a current Android, so the suggestion can't be state-derived. Being
     * dismissible for good is what keeps it from becoming a permanent nag aimed at people who
     * did the thing months ago.
     */
    val alwaysOnTipDismissed: Boolean = false,
) {
    fun isPaused(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs < pausedUntilMs

    /** True when the filter should be doing work right now. */
    fun isFiltering(nowMs: Long = System.currentTimeMillis()): Boolean = filteringEnabled && !isPaused(nowMs)

    fun appRulesFor(packageName: String): List<AppRule> = appRules.filter { it.packageName == packageName }

    /**
     * Whether [packageName] is in scope. Note this is *also* enforced when the tunnel is built
     * (apps out of scope never reach it at all); this is the same question asked in the UI.
     */
    fun covers(packageName: String): Boolean = when (scopeMode) {
        AppScopeMode.ALL_EXCEPT -> packageName !in excludedApps
        AppScopeMode.ONLY_SELECTED -> packageName in includedApps
    }

    /**
     * The shape of the tunnel these settings ask for. The tun is immutable once established, so
     * a change to any of this means tearing it down and building a new one — while a change to,
     * say, the blocklist does not. Comparing this string is what tells the two apart.
     */
    fun tunnelShape(): String = buildString {
        append(scopeMode.name)
        append('|')
        append(excludedApps.sorted().joinToString(","))
        append('|')
        append(includedApps.sorted().joinToString(","))
        append('|')
        append(bypassGuard.name)
    }
}

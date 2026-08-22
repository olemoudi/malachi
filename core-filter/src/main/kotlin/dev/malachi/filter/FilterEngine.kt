package dev.malachi.filter

/** Where a verdict came from. Surfaced in the query log so every block can be explained. */
enum class RuleSource {
    /** Nothing matched. */
    NONE,

    /** A per-app rule the user wrote ("block this domain in this app"). */
    APP_RULE,

    /** The user's own blocklist or allowlist. */
    USER_RULE,

    /** A subscribed public list. [Verdict.detail] names which one. */
    LIST,
}

/**
 * The answer for one lookup. [detail] is what to show a user asking "why?" — a list title, or
 * the rule they wrote — and is empty only when nothing matched.
 */
data class Verdict(
    val blocked: Boolean,
    val source: RuleSource = RuleSource.NONE,
    val detail: String = "",
) {
    companion object {
        val ALLOWED = Verdict(blocked = false)
    }
}

/** A user rule scoped to one app. [block] false means "exempt this domain in this app". */
data class AppDomainRule(
    val domain: String,
    val packageName: String,
    val block: Boolean,
)

/** Which subscribed lists carry a domain, by title. See [FilterEngine.listsCovering]. */
data class ListCoverage(
    val blocking: List<String> = emptyList(),
    val allowing: List<String> = emptyList(),
)

/** One subscribed list, compiled. [allow] holds its `@@` exceptions. */
data class CompiledList(
    val id: String,
    val title: String,
    val block: DomainIndex,
    val allow: DomainIndex = DomainIndex.EMPTY,
)

/**
 * Decides whether a DNS lookup is blocked. Pure, deterministic, and called once per query on
 * the tunnel's hot path, so it allocates nothing in the common case.
 *
 * **Precedence is by authorship first, specificity second.** A rule the user wrote always beats
 * a downloaded list, because the whole point of writing one is that the list got it wrong. Among
 * rules of equal authorship the more specific domain wins: allowing `cdn.example.com` while
 * blocking `example.com` does what it looks like it does, in either order of entry. Only at an
 * exact tie does "allow" win, on the principle that a broken app is a worse failure than an ad.
 *
 * Per-app rules sit above both. They exist for the case the query log surfaces — one app abusing
 * a domain every other app needs — and would be pointless if a global rule could override them.
 */
class FilterEngine(
    private val userBlock: DomainIndex = DomainIndex.EMPTY,
    private val userAllow: DomainIndex = DomainIndex.EMPTY,
    private val appRules: List<AppDomainRule> = emptyList(),
    private val lists: List<CompiledList> = emptyList(),
    private val connectivityChecks: DomainIndex = CONNECTIVITY_CHECK_INDEX,
) {

    /** Total domains across every subscribed list, before de-duplication between lists. */
    val listedDomains: Int get() = lists.sumOf { it.block.size }

    fun decide(host: String, packageName: String?): Verdict {
        val h = DomainIndex.normalizeHost(host) ?: return Verdict.ALLOWED

        appVerdict(h, packageName)?.let { return it }

        val userBlockDepth = userBlock.matchDepth(h)
        val userAllowDepth = userAllow.matchDepth(h)
        if (userAllowDepth >= 0 && (userBlockDepth < 0 || userAllowDepth <= userBlockDepth)) {
            return Verdict(blocked = false, source = RuleSource.USER_RULE, detail = h)
        }
        if (userBlockDepth >= 0) {
            return Verdict(blocked = true, source = RuleSource.USER_RULE, detail = h)
        }

        // Below the user and above every list: the handful of names the phone itself uses to
        // decide whether a network works. See [CONNECTIVITY_CHECKS].
        if (connectivityChecks.matches(h)) return Verdict.ALLOWED

        // Among the subscribed lists, an exception anywhere outranks a block anywhere: the lists
        // are curated together and their maintainers publish `@@` rules precisely to repair
        // over-blocking, including over-blocking caused by another list.
        var blockedBy: CompiledList? = null
        for (list in lists) {
            if (list.allow.matches(h)) return Verdict(blocked = false, source = RuleSource.LIST, detail = list.title)
            if (blockedBy == null && list.block.matches(h)) blockedBy = list
        }
        // Not an early return above: an exception in a later list must still be able to rescue a
        // domain an earlier list blocked, so the scan has to finish before the block is honoured.
        blockedBy?.let { return Verdict(blocked = true, source = RuleSource.LIST, detail = it.title) }

        return Verdict.ALLOWED
    }

    /**
     * Every subscribed list with an opinion about [host]: those that block it, and those that
     * carry an exception for it.
     *
     * [decide] deliberately names only one — a verdict has one cause and reporting four would
     * make the log unreadable — but the question a person has before writing an exception is a
     * different one. A domain on four lists is one four separate maintainers think is a tracker;
     * a domain on one is a judgement call, and possibly a mistake. That is the difference
     * between allowing it confidently and allowing it nervously.
     *
     * Off the hot path, and allocating: this is answered when somebody taps a row, never per
     * lookup.
     */
    fun listsCovering(host: String): ListCoverage {
        val h = DomainIndex.normalizeHost(host) ?: return ListCoverage()
        return ListCoverage(
            blocking = lists.filter { it.block.matches(h) }.map { it.title },
            allowing = lists.filter { it.allow.matches(h) }.map { it.title },
        )
    }

    /**
     * The most specific per-app rule for this app, or null when none applies. An unattributed
     * lookup (no [packageName]) can't match a per-app rule at all — guessing which app it was
     * would silently apply someone else's rule.
     */
    private fun appVerdict(host: String, packageName: String?): Verdict? {
        if (packageName == null || appRules.isEmpty()) return null
        var best: AppDomainRule? = null
        var bestDepth = Int.MAX_VALUE
        for (rule in appRules) {
            if (rule.packageName != packageName) continue
            val depth = matchDepth(host, rule.domain)
            if (depth < 0) continue
            // Ties go to the exemption: see the class doc.
            if (depth < bestDepth || (depth == bestDepth && !rule.block)) {
                best = rule
                bestDepth = depth
            }
        }
        val rule = best ?: return null
        return Verdict(blocked = rule.block, source = RuleSource.APP_RULE, detail = rule.domain)
    }

    companion object {

        /**
         * The names a phone uses to decide whether the network it is on works at all.
         *
         * **This is not a curated exception list and must not grow into one.** It exists because
         * of one specific, silent and very expensive failure: Android decides a Wi-Fi has no
         * internet by fetching a `generate_204` over it, and if a blocklist refuses the name that
         * probe uses, the probe fails. The phone then marks a perfectly good Wi-Fi as unvalidated,
         * shows "no internet", and — on every vendor that has an "adaptive connectivity" or
         * "switch to mobile data automatically" feature — leaves the Wi-Fi for the mobile network,
         * on somebody's data allowance. Nothing on the phone says why, and the app that caused it
         * reports itself as working perfectly, because from the filter's point of view it was.
         *
         * Every entry is a probe endpoint and nothing else: they serve an empty 204 and carry no
         * content, so allowing them costs no advertising at all. The vendor ones are here because
         * they are the ones that actually get blocked — Xiaomi's, Huawei's and vivo's probe hosts
         * appear on aggressive lists as telemetry, which is defensible about the domain and
         * disastrous about the phone.
         *
         * A rule the *user* wrote still wins, because authorship comes first everywhere in this
         * engine and somebody who deliberately blocks one of these has said what they want. What
         * this refuses is a downloaded list doing it on their behalf, silently.
         */
        val CONNECTIVITY_CHECKS = listOf(
            // Android's own, current and historical.
            "connectivitycheck.gstatic.com",
            "connectivitycheck.android.com",
            "clients3.google.com",
            "clients4.google.com",
            // The HTTPS half of the same probe on a modern Android.
            "www.google.com",
            // Vendors that ship their own probe, and whose probe hosts are on real blocklists.
            "connect.rom.miui.com",
            "connectivitycheck.platform.hicloud.com",
            "wifi.vivo.com.cn",
        )

        private val CONNECTIVITY_CHECK_INDEX = DomainIndex.of(CONNECTIVITY_CHECKS)

        /**
         * Labels dropped from [host] before it matched [domain] as a suffix, or -1. `example.com`
         * matches itself at 0 and `ads.example.com` at 1; it never matches `notexample.com`.
         */
        fun matchDepth(host: String, domain: String): Int {
            val h = DomainIndex.normalizeHost(host) ?: return -1
            val d = DomainIndex.normalizeHost(domain) ?: return -1
            if (h == d) return 0
            if (!h.endsWith(".$d")) return -1
            // The dots in the part that was dropped are the labels that were dropped: for
            // `a.b.example.com` against `example.com` the prefix is `a.b.`, so depth 2.
            var depth = 0
            for (i in 0 until h.length - d.length) if (h[i] == '.') depth++
            return depth
        }
    }
}

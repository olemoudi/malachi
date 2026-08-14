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

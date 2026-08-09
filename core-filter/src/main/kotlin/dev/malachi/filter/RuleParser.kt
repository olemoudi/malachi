package dev.malachi.filter

/** One usable line of a subscribed list. */
sealed interface Rule {
    val domain: String

    /** Block [domain] and everything under it. */
    data class Block(override val domain: String) : Rule

    /** An `@@` exception: never block [domain], whatever else a list says. */
    data class Allow(override val domain: String) : Rule
}

/**
 * Turns the lines of a public blocklist into rules.
 *
 * The reputable lists come in three shapes and we read all of them, because forcing a user to
 * care which format their list is published in would be an implementation detail leaking into
 * the product:
 *
 * - **hosts** (`0.0.0.0 ads.example.com`) — StevenBlack, AdAway, Dan Pollock, Peter Lowe.
 * - **plain domains**, one per line.
 * - **Adblock syntax** (`||ads.example.com^`, `@@||good.example.com^`) — AdGuard DNS filter,
 *   OISD, HaGeZi, EasyPrivacy.
 *
 * Adblock syntax describes far more than DNS can act on: request types, referrers, CSS
 * selectors, regular expressions, per-site scoping. A DNS blocker sees a name and nothing else,
 * so anything whose meaning depends on context it cannot observe is **skipped, not
 * approximated**. Treating `||ads.example.com^$domain=news.example` — "block this, but only for
 * requests originating on that site" — as an unconditional block would over-block silently, and
 * a user debugging a broken app would have no way to discover that the app had invented a rule
 * the list never wrote. Skipping is visible in the entry count; guessing is not.
 */
object RuleParser {

    /**
     * Modifiers that don't change what a rule means at the DNS layer, so a rule carrying only
     * these is still safe to honour. Everything else — `$domain=`, `$third-party`, `$dnsrewrite`,
     * `$client`, `$app`, request-type modifiers — makes the rule conditional on something we
     * can't see, and the rule is dropped.
     */
    private val HARMLESS_MODIFIERS = setOf("important", "all", "document", "doc")

    /** Hosts-file targets that mean "this line points somewhere harmless", not "block this". */
    private val LOOPBACK_TARGETS = setOf("0.0.0.0", "127.0.0.1", "::", "::1", "0.0.0.0.0", "255.255.255.255")

    /** Names a hosts file legitimately maps to loopback for its own sake; never blocklist entries. */
    private val HOSTS_NOISE = setOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost",
        "ip6-localhost", "ip6-loopback", "ip6-localnet", "ip6-mcastprefix",
        "ip6-allnodes", "ip6-allrouters", "ip6-allhosts", "0.0.0.0",
    )

    /**
     * Parses one line. Returns the rules it yields — usually none (comments, blanks, syntax we
     * decline to guess at) or exactly one; a hosts line may map several names to one address.
     */
    fun parseLine(raw: String): List<Rule> {
        val line = raw.trim()
        if (line.isEmpty()) return emptyList()
        // Comments first, and cosmetic syntax before any `#` is treated as one: `#` is a comment
        // marker in hosts files and a filter operator in Adblock syntax, so stripping it early
        // would turn `example.com##.ad-banner` — an instruction to hide an element on a site —
        // into an instruction to block that site's DNS entirely.
        if (line.startsWith("!") || line.startsWith("#") || line.startsWith("[")) return emptyList()
        if (line.contains("##") || line.contains("#@#") || line.contains("#%#") || line.contains("#\$#")) {
            return emptyList()
        }
        if (line.startsWith("@@")) return adblockRule(line.substring(2), allow = true)
        if (line.startsWith("||")) return adblockRule(line, allow = false)
        // Regex rules and URL-anchored rules address paths, which DNS never sees.
        if (line.startsWith("/") || line.startsWith("|")) return emptyList()
        return hostsOrPlainRule(withoutComment(line))
    }

    /** Trailing hosts-file comment removed (`0.0.0.0 ads.example.com # an ad network`). */
    private fun withoutComment(line: String): String {
        val hash = line.indexOf('#')
        return if (hash >= 0) line.substring(0, hash).trim() else line
    }

    /** `||domain^`, optionally `$`-modified, optionally `|`-anchored at the end. */
    private fun adblockRule(rule: String, allow: Boolean): List<Rule> {
        if (!rule.startsWith("||")) return emptyList()
        var body = rule.substring(2)

        val dollar = body.indexOf('$')
        if (dollar >= 0) {
            val modifiers = body.substring(dollar + 1)
            body = body.substring(0, dollar)
            val allHarmless = modifiers.split(',')
                .map { it.trim().lowercase() }
                .all { it.isNotEmpty() && it in HARMLESS_MODIFIERS }
            if (!allHarmless) return emptyList()
        }

        body = body.trimEnd('|')
        // `^` is the separator token; at the end of a DNS rule it means "and anything under it",
        // which is exactly how DomainIndex matches. Anywhere else it delimits a path we can't see.
        if (body.endsWith("^")) body = body.dropLast(1)
        // A leading `*.` is redundant with suffix matching, so it is dropped rather than refused;
        // a wildcard anywhere else describes a shape DNS names can't be matched against.
        if (body.startsWith("*.")) body = body.substring(2)
        // A path, a port, a wildcard or a scheme leaves the realm of "a domain name".
        if (body.any { it == '/' || it == '*' || it == '^' || it == ':' || it == '?' || it == '=' }) {
            return emptyList()
        }

        val domain = DomainIndex.normalizeHost(body) ?: return emptyList()
        return listOf(if (allow) Rule.Allow(domain) else Rule.Block(domain))
    }

    /** A hosts-file line (`0.0.0.0 ads.example.com`) or a bare domain on a line of its own. */
    private fun hostsOrPlainRule(line: String): List<Rule> {
        val fields = line.split(' ', '\t').filter { it.isNotEmpty() }
        if (fields.isEmpty()) return emptyList()

        val names = if (fields.size >= 2 && fields[0] in LOOPBACK_TARGETS) {
            fields.drop(1)
        } else if (fields.size == 1) {
            fields
        } else {
            // Several fields whose first isn't a loopback target: a hosts line pointing at a real
            // address (a user's own /etc/hosts entries, say). Not a blocklist instruction.
            return emptyList()
        }

        return names.mapNotNull { name ->
            if (name.lowercase() in HOSTS_NOISE) return@mapNotNull null
            DomainIndex.normalizeHost(name)?.let { Rule.Block(it) }
        }
    }
}

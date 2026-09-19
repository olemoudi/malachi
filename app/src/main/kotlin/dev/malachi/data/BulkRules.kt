package dev.malachi.data

import dev.malachi.filter.Rule
import dev.malachi.filter.RuleParser

/** What a paste turned into: the domains to write, and how many lines could not become one. */
data class BulkParse(
    val domains: List<String>,
    val skipped: Int,
    /** True when the paste held more than [BulkRules.MAX_DOMAINS] and the rest was left out. */
    val truncated: Boolean,
)

/**
 * Many rules at once, from whatever somebody has on their clipboard.
 *
 * The realistic sources are a hosts file, a few lines of an Adblock list, a column of domains
 * from a note, or URLs copied out of a browser — so every one of those shapes is read, by the same
 * [RuleParser] the blocklists go through and the same [DomainInput] the single-rule field uses.
 * Two decisions are deliberate:
 *
 * - **`@@` lines are skipped, not converted.** An exception says the opposite of a block, and the
 *   button that applies a paste decides which of the two every line becomes; honouring the line
 *   would mean a "block all" that allowed some of them.
 * - **There is a ceiling.** The user's rules are stored as text and shown as a list, and a paste of
 *   fifty thousand domains is a blocklist, which has a home that is built for it: a sorted index
 *   that costs eight bytes a domain, not a settings document that is decoded on every change.
 */
object BulkRules {

    const val MAX_DOMAINS = 1000

    fun parse(text: String): BulkParse {
        val domains = LinkedHashSet<String>()
        var skipped = 0
        var truncated = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue
            if (line.startsWith("@@")) {
                skipped++
                continue
            }
            val found = if (line.startsWith("||")) {
                // Adblock syntax carries commas of its own, inside its modifiers.
                blocks(line)
            } else {
                // Anything else may be a list typed by hand: commas and semicolons are the
                // separators people use when a line holds more than one name.
                line.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { part ->
                    if (part.any { it == ' ' || it == '\t' }) blocks(part) else listOfNotNull(DomainInput.parse(part))
                }
            }
            if (found.isEmpty()) {
                skipped++
                continue
            }
            for (domain in found) {
                if (domains.size >= MAX_DOMAINS && domain !in domains) {
                    truncated = true
                } else {
                    domains += domain
                }
            }
        }
        return BulkParse(domains.toList(), skipped, truncated)
    }

    private fun blocks(line: String): List<String> =
        RuleParser.parseLine(line).filterIsInstance<Rule.Block>().map { it.domain }
}

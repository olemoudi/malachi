package dev.malachi.data

import dev.malachi.filter.DomainIndex

/**
 * Turns what a person types into a domain, or rejects it.
 *
 * The realistic inputs are not domains: they are things copied out of a browser address bar
 * (`https://ads.example.com/tag?id=4`), out of the query log (`ads.example.com.`), or typed with
 * a stray space. Refusing all of those on a technicality would be pedantry, so they are reduced
 * to the name they contain — and anything that still isn't a hostname is refused rather than
 * quietly stored as a rule that can never match.
 */
object DomainInput {

    fun parse(raw: String): String? {
        var s = raw.trim().lowercase()
        if (s.isEmpty()) return null

        // Scheme, credentials, path, query, fragment, port — everything a URL carries that a
        // name does not.
        s.indexOf("://").let { if (it >= 0) s = s.substring(it + 3) }
        s.indexOf('@').let { if (it >= 0) s = s.substring(it + 1) }
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        // A bracketed IPv6 literal is not a name; dropping the brackets would leave a colon,
        // which normalizeHost refuses anyway, but being explicit costs nothing.
        if (s.startsWith("[")) return null
        s = s.substringBefore(':')

        // Adblock syntax, in case it was pasted from a list.
        if (s.startsWith("||")) s = s.substring(2)
        s = s.trimEnd('^', '|')

        return DomainIndex.normalizeHost(s)
    }

    /**
     * The names a rule for [domain] could reasonably be written against, most specific first:
     * the name itself, then each parent down to two labels.
     *
     * This is what a "wildcard" would be, spelled out. Matching is by suffix, so a rule for
     * `bbva.es` already catches `movil.bbva.es` and everything else under it — there is nothing
     * to type, only a choice of how far up to go, and that is a question a person can answer
     * about their own bank without knowing what a subdomain is.
     *
     * The walk stops before the last label because the engine refuses a single one
     * ([DomainIndex.normalizeHost]), and that refusal is load-bearing elsewhere: it is what keeps
     * a hosts file's `localhost` line from compiling into a rule that breaks the phone.
     */
    fun scopes(domain: String): List<String> {
        val host = DomainIndex.normalizeHost(domain) ?: return emptyList()
        val scopes = mutableListOf<String>()
        var current = host
        while (true) {
            scopes += current
            val parent = current.substringAfter('.', "")
            if (!parent.contains('.')) return scopes
            current = parent
        }
    }
}

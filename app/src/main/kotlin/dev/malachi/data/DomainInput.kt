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
}

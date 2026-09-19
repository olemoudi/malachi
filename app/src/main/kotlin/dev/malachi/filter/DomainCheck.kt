package dev.malachi.filter

import dev.malachi.data.AppRule
import dev.malachi.data.DomainInput
import dev.malachi.data.MalachiSettings

/**
 * Everything the filter would say about one name, for somebody who asked.
 *
 * [verdict] is what happens to it in an app with no rules of its own. [userRule] is the global rule
 * of the user's that decided it, spelled as written — which is often a parent: a rule for `bbva.es`
 * is what allows `movil.bbva.es`, and saying "your rule for movil.bbva.es" would send somebody
 * looking for a rule that does not exist. [appRules] are the per-app rules that reach this name,
 * because a verdict without them can be the wrong answer for the app the question is really about.
 */
data class DomainCheck(
    val domain: String,
    val verdict: Verdict,
    val connectivityCheck: Boolean,
    val userRule: String?,
    val coverage: ListCoverage,
    val appRules: List<AppRule>,
)

object DomainChecker {

    /** The answer for whatever was typed, or null when it does not contain a domain. */
    fun check(raw: String, engine: FilterEngine, settings: MalachiSettings): DomainCheck? {
        val domain = DomainInput.parse(raw) ?: return null
        val verdict = engine.decide(domain, null)
        val userRule = if (verdict.source == RuleSource.USER_RULE) {
            mostSpecific(domain, if (verdict.blocked) settings.userBlocked else settings.userAllowed)
        } else {
            null
        }
        return DomainCheck(
            domain = domain,
            verdict = verdict,
            // Only when it is the reason: a probe the user blocked themselves was decided by them.
            connectivityCheck = verdict.source == RuleSource.NONE && engine.isConnectivityCheck(domain),
            userRule = userRule,
            coverage = engine.listsCovering(domain),
            appRules = settings.appRules
                .filter { FilterEngine.matchDepth(domain, it.domain) >= 0 }
                .sortedWith(compareBy<AppRule> { it.packageName }.thenBy { it.domain }),
        )
    }

    private fun mostSpecific(domain: String, rules: Set<String>): String? =
        rules.map { it to FilterEngine.matchDepth(domain, it) }
            .filter { it.second >= 0 }
            .minByOrNull { it.second }
            ?.first
}

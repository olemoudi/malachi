package dev.malachi.data

import kotlinx.serialization.Serializable

/**
 * Where a guided search has got to. Derived from the stored state, never stored itself — an enum
 * in the settings blob is a decode failure waiting for the day a future version adds a value and
 * an older install reads it, and that failure takes the *whole* blob down with it.
 */
enum class GuideStep {
    /** Use the app until it fails, so there is something to search. */
    CAPTURE,

    /** Nothing was refused, so there is nothing here to be the cause. */
    NOTHING_REFUSED,

    /** Everything is allowed. If it still fails, the blocking was never the problem. */
    BASELINE,

    /** One name is refused and the rest are allowed. The question is whether it broke again. */
    TESTING,

    /** It broke with exactly one name refused, so that name is the one. */
    CULPRIT,

    /** It failed with nothing refused at all. */
    RULED_OUT,

    /** Every name was refused on its own and it kept working. */
    EXHAUSTED,
}

/**
 * The guided search for the one blocked name that breaks an app.
 *
 * **The method, and why it is this one.** Allow everything Malachi was refusing — the app should
 * start working — and then put the names back one at a time, asking after each. The moment it
 * breaks again, the name that is refused *right now* is the only thing that changed, so it is the
 * answer. No understanding of DNS is required at any point: every step is "force-stop it, try it
 * again, did it work?".
 *
 * **Why one at a time rather than halves.** A bisection would find it in log₂(n) rounds instead of
 * n, and each round costs the user a force-stop and a repeat of whatever they were doing, so that
 * is not nothing. It is still the wrong trade here. With exactly one name refused, a failure names
 * it outright and needs no argument; with half the list refused, the result depends on there being
 * exactly one culprit, and an app that needs two of them makes a bisection converge confidently on
 * a wrong answer. What is done about the cost instead is ordering: [candidates] arrive ranked by
 * how insistently the app asked, so the likely one is usually tested in the first round or two.
 *
 * **Every transition here is a pure function of stored state**, so the whole method is testable
 * without a device, an app to break, or a person to ask.
 */
@Serializable
data class GuidedSearch(
    val packageName: String = "",

    /** The names to test, likeliest first. Empty until the capture step has finished. */
    val candidates: List<String> = emptyList(),

    /** [BASELINE_INDEX] while everything is allowed; otherwise the one candidate left refused. */
    val index: Int = BASELINE_INDEX,

    /** The name whose refusal broke the app, once a round has found one. */
    val culprit: String = "",

    /** Set when the app failed with nothing refused at all: whatever is wrong, it is not us. */
    val ruledOut: Boolean = false,

    /** Whether the capture step has been answered, which is what tells an empty list apart. */
    val captured: Boolean = false,

    /**
     * How many refused names the capture actually found, before [candidates] was capped.
     *
     * Kept so the screen can say what it left out. A search that quietly tested the first ten of
     * forty and then reported "none of them" would be reporting something it never looked at.
     */
    val found: Int = 0,
) {

    val step: GuideStep
        get() = when {
            culprit.isNotEmpty() -> GuideStep.CULPRIT
            ruledOut -> GuideStep.RULED_OUT
            !captured -> GuideStep.CAPTURE
            candidates.isEmpty() -> GuideStep.NOTHING_REFUSED
            index == BASELINE_INDEX -> GuideStep.BASELINE
            index in candidates.indices -> GuideStep.TESTING
            else -> GuideStep.EXHAUSTED
        }

    /** The name being tested right now, or empty in every other step. */
    val testing: String get() = candidates.getOrElse(index) { "" }

    /** Which round this is, counting from one, for a screen that has to say "3 of 9". */
    val round: Int get() = index + 1

    /** True when the capture found more than the search will test. */
    val truncated: Boolean get() = found > candidates.size

    /**
     * The names that must be exempted for the app under test while this step runs.
     *
     * The terminal steps deliberately exempt nothing: what happens to the rules then is a decision
     * the user makes — keep the fix, or put everything back — and not something a step applies on
     * their behalf.
     */
    fun exemptions(): Set<String> = when (step) {
        GuideStep.BASELINE -> candidates.toSet()
        // Everything but the one under test. This is the whole search, in one line.
        GuideStep.TESTING -> candidates.toSet() - candidates[index]
        else -> emptySet()
    }

    /**
     * [rules] with this step's exemptions in place and none of the search's own left over.
     *
     * Removing every candidate first is exact rather than approximate, and the reason is worth
     * writing down: a candidate is by definition a name a *list* refused, which means no per-app
     * rule matched it when it was captured — a rule of the user's own would have won and the
     * verdict would have said so. So the search can never be deleting a decision somebody made.
     */
    fun applied(rules: List<AppRule>): List<AppRule> =
        cleared(rules) + exemptions().map { AppRule(it, packageName, block = false) }

    /** [rules] with every exemption this search wrote taken back out. */
    fun cleared(rules: List<AppRule>): List<AppRule> {
        val mine = candidates.toSet()
        return rules.filterNot { it.packageName == packageName && it.domain in mine }
    }

    /** The capture is over; these are the names it refused, likeliest first. */
    fun captured(refused: List<String>, limit: Int): GuidedSearch = copy(
        candidates = refused.distinct().take(limit),
        found = refused.distinct().size,
        index = BASELINE_INDEX,
        captured = true,
    )

    /**
     * The answer to "did it work this time?", which is the only question this search ever asks.
     *
     * From the baseline, working means the refusals really were the cause and the search can
     * begin; failing means they never were. From a test round, working clears the name that was
     * refused and moves to the next, and failing names it — because it was the only thing refused.
     */
    fun answered(worked: Boolean): GuidedSearch = when (step) {
        GuideStep.BASELINE -> if (worked) copy(index = 0) else copy(ruledOut = true)
        GuideStep.TESTING -> if (worked) copy(index = index + 1) else copy(culprit = candidates[index])
        else -> this
    }

    /**
     * Back to the start of the search, keeping the names it found.
     *
     * For the case the whole method is most vulnerable to: Android can hold on to an answer for a
     * minute or two, so a round that says "it worked" may be reporting a cached address rather
     * than a name that is innocent. Somebody who suspects that needs a way back that does not cost
     * them the capture.
     */
    fun restarted(): GuidedSearch = copy(index = BASELINE_INDEX, culprit = "", ruledOut = false)

    companion object {
        const val BASELINE_INDEX = -1

        /**
         * How many names one search will test.
         *
         * The cost of this method is paid by the user, one force-stop and one repeat per round, so
         * a list of forty is not a search anybody finishes. Ten is about the limit of what somebody
         * will sit through, and [truncated] is what stops the cap from being silent.
         */
        const val MAX_CANDIDATES = 10
    }
}

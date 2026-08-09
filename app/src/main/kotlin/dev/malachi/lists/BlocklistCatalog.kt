package dev.malachi.lists

/** What a list is for, so the picker can group sources instead of showing one long column. */
enum class BlocklistCategory { ADS, PRIVACY, SECURITY, EXTRAS }

/**
 * One subscribable list.
 *
 * [title] and [maintainer] are proper nouns and stay untranslated; the human explanation of
 * what each list does is a string resource, looked up by [id] in the UI, so it can be
 * translated without the catalog growing an Android dependency.
 */
data class BlocklistSource(
    val id: String,
    val title: String,
    val maintainer: String,
    val url: String,
    val homepage: String,
    val category: BlocklistCategory,
    val enabledByDefault: Boolean = false,
    /**
     * Roughly how many domains this list carries, for the picker. Only an order of magnitude —
     * the real count is displayed once the list has actually been downloaded.
     */
    val approximateEntries: Int = 0,
)

/**
 * The lists Malachi will subscribe to.
 *
 * The selection is deliberately small and deliberately boring. Every source here is a
 * long-running, openly maintained project with a public issue tracker where a false positive
 * can be reported and fixed — which matters more than raw entry count, because the failure mode
 * of a DNS blocker is not "an ad got through", it is "the bank app stopped working and nobody
 * knows why". Aggregators of aggregators, lists with no maintainer, and anything that blocks by
 * regular expression are out.
 *
 * Two are on by default. AdGuard's DNS filter is the broad, conservative default the whole
 * category converges on; AdAway is small, mobile-specific and catches the in-app ad SDKs a
 * desktop-derived list can miss. Everything else is opt-in, in rough order of how much it is
 * likely to break, and the picker says so.
 */
object BlocklistCatalog {

    val sources: List<BlocklistSource> = listOf(
        BlocklistSource(
            id = "adguard-dns",
            title = "AdGuard DNS filter",
            maintainer = "AdGuard",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_1.txt",
            homepage = "https://github.com/AdguardTeam/AdguardSDNSFilter",
            category = BlocklistCategory.ADS,
            enabledByDefault = true,
            approximateEntries = 60_000,
        ),
        BlocklistSource(
            id = "adaway",
            title = "AdAway",
            maintainer = "AdAway",
            url = "https://adaway.org/hosts.txt",
            homepage = "https://github.com/AdAway/adaway.github.io",
            category = BlocklistCategory.ADS,
            enabledByDefault = true,
            approximateEntries = 7_000,
        ),
        BlocklistSource(
            id = "easyprivacy",
            title = "EasyPrivacy",
            maintainer = "EasyList",
            url = "https://easylist.to/easylist/easyprivacy.txt",
            homepage = "https://easylist.to",
            category = BlocklistCategory.PRIVACY,
            approximateEntries = 20_000,
        ),
        BlocklistSource(
            id = "yoyo",
            title = "Peter Lowe's list",
            maintainer = "Peter Lowe",
            url = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
            homepage = "https://pgl.yoyo.org/adservers/",
            category = BlocklistCategory.PRIVACY,
            approximateEntries = 3_500,
        ),
        BlocklistSource(
            id = "oisd-small",
            title = "OISD Small",
            maintainer = "Stephan van Ruth",
            url = "https://small.oisd.nl/",
            homepage = "https://oisd.nl",
            category = BlocklistCategory.ADS,
            approximateEntries = 57_000,
        ),
        BlocklistSource(
            id = "oisd-big",
            title = "OISD Big",
            maintainer = "Stephan van Ruth",
            url = "https://big.oisd.nl/",
            homepage = "https://oisd.nl",
            category = BlocklistCategory.EXTRAS,
            approximateEntries = 253_000,
        ),
        BlocklistSource(
            id = "hagezi-pro",
            title = "HaGeZi Pro",
            maintainer = "HaGeZi",
            url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/pro.txt",
            homepage = "https://github.com/hagezi/dns-blocklists",
            category = BlocklistCategory.EXTRAS,
            approximateEntries = 200_000,
        ),
        BlocklistSource(
            id = "hagezi-tif",
            title = "HaGeZi Threat Intelligence Feeds",
            maintainer = "HaGeZi",
            url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/tif.txt",
            homepage = "https://github.com/hagezi/dns-blocklists",
            category = BlocklistCategory.SECURITY,
            approximateEntries = 600_000,
        ),
        BlocklistSource(
            id = "stevenblack",
            title = "StevenBlack unified hosts",
            maintainer = "Steven Black",
            url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            homepage = "https://github.com/StevenBlack/hosts",
            category = BlocklistCategory.EXTRAS,
            approximateEntries = 130_000,
        ),
        BlocklistSource(
            id = "someonewhocares",
            title = "Dan Pollock's list",
            maintainer = "Dan Pollock",
            url = "https://someonewhocares.org/hosts/zero/hosts",
            homepage = "https://someonewhocares.org/hosts/",
            category = BlocklistCategory.EXTRAS,
            approximateEntries = 13_000,
        ),
    )

    fun byId(id: String): BlocklistSource? = sources.firstOrNull { it.id == id }

    /**
     * The sources a set of choices selects. A source the user has never touched isn't in the
     * map, and falls back to its own default — which is what lets a later release add a list, or
     * change a default, without silently overriding a decision someone made on purpose.
     */
    fun enabled(choices: Map<String, Boolean>): List<BlocklistSource> =
        sources.filter { choices[it.id] ?: it.enabledByDefault }

    fun isEnabled(id: String, choices: Map<String, Boolean>): Boolean =
        choices[id] ?: (byId(id)?.enabledByDefault ?: false)
}

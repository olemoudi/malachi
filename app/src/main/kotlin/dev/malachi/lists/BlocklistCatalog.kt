package dev.malachi.lists

/** What a list is for, so the picker can group sources instead of showing one long column. */
enum class BlocklistCategory { ADS, PRIVACY, ANNOYANCES, SECURITY, NATIVE, REGIONAL, OTHER }

/**
 * How likely a list is to break something you wanted, which is the only question that decides
 * whether somebody should switch it on.
 *
 * "How many domains" is the number every blocker advertises and it is nearly useless for this:
 * a two-hundred-entry list that blocks a phone manufacturer's push service breaks more than a
 * quarter-million-entry list curated against false positives. So this is a judgement about
 * intent and track record, not a function of size — the entry count is shown separately and
 * says something else.
 *
 * Ordered least to most dangerous; the picker relies on that order.
 */
enum class BreakageRisk { SAFE, MODERATE, AGGRESSIVE }

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
    /** No default: every list has to state what it might cost, deliberately and one at a time. */
    val risk: BreakageRisk,
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
 * Every source here is a long-running, openly maintained project with somewhere a false positive
 * can be reported and fixed — which matters more than raw entry count, because the failure mode
 * of a DNS blocker is not "an ad got through", it is "the bank app stopped working and nobody
 * knows why". Lists with no maintainer, and anything that blocks by regular expression, are out.
 *
 * **Most of these are fetched from AdGuard's Hostlists Registry rather than from the project
 * that writes them.** That is deliberate, and it was learned the hard way: the two HaGeZi lists
 * this catalogue shipped pointed straight at `github.com/hagezi`, and when that account
 * disappeared both became a silent 404 for everybody who had subscribed. The registry is a
 * curated mirror — AdGuard's own DNS clients read it — so a list outlives the account that
 * publishes it, and every entry arrives already normalised. The four lists the registry does not
 * carry keep their own URL.
 *
 * **Categories are what a DNS blocker can act on, not what a browser extension can.** AdGuard's
 * app has a "social" and a large "annoyances" section; most of what fills them is cosmetic rules
 * (`example.com##.banner`) that hide page elements, and a filter that only ever sees a hostname
 * has nothing to do with those — [RuleParser] drops them. Categories here are the ones where
 * blocking a name is the whole mechanism.
 *
 * Two are on by default, and adding to this list must never change that: a source nobody has
 * touched falls back to its own default, so a release that adds thirty lists adds no downloads
 * and no memory to an install that already exists.
 */
object BlocklistCatalog {

    val sources: List<BlocklistSource> = listOf(
        // ---- ADS ---------------------------------------------------------------------
        BlocklistSource(
            id = "oisd-big",
            title = "OISD Big",
            maintainer = "oisd",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_27.txt",
            homepage = "https://oisd.nl/",
            category = BlocklistCategory.ADS,
            risk = BreakageRisk.MODERATE,
            approximateEntries = 250_000,
        ),
        BlocklistSource(
            id = "hagezi-pro",
            title = "HaGeZi Pro",
            maintainer = "HaGeZi",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_48.txt",
            homepage = "https://github.com/AdguardTeam/HostlistsRegistry",
            category = BlocklistCategory.ADS,
            risk = BreakageRisk.AGGRESSIVE,
            approximateEntries = 220_000,
        ),
        BlocklistSource(
            id = "hagezi-normal",
            title = "HaGeZi Normal",
            maintainer = "HaGeZi",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_34.txt",
            homepage = "https://github.com/AdguardTeam/HostlistsRegistry",
            category = BlocklistCategory.ADS,
            risk = BreakageRisk.MODERATE,
            approximateEntries = 180_000,
        ),
        BlocklistSource(
            id = "adguard-dns",
            title = "AdGuard DNS filter",
            maintainer = "AdGuard",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_1.txt",
            homepage = "https://github.com/AdguardTeam/AdGuardSDNSFilter",
            category = BlocklistCategory.ADS,
            risk = BreakageRisk.SAFE,
            enabledByDefault = true,
            approximateEntries = 160_000,
        ),
        BlocklistSource(
            id = "1hosts-lite",
            title = "1Hosts (Lite)",
            maintainer = "badmojr",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_24.txt",
            homepage = "https://badmojr.github.io/1Hosts/",
            category = BlocklistCategory.ADS,
            risk = BreakageRisk.MODERATE,
            approximateEntries = 100_000,
        ),
        BlocklistSource(
            id = "stevenblack",
            title = "StevenBlack unified hosts",
            maintainer = "Steven Black",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_33.txt",
            homepage = "https://github.com/StevenBlack/hosts",
            category = BlocklistCategory.ADS,
            risk = BreakageRisk.MODERATE,
            approximateEntries = 98_000,
        ),
        BlocklistSource(
            id = "oisd-small",
            title = "OISD Small",
            maintainer = "oisd",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_5.txt",
            homepage = "https://oisd.nl/",
            category = BlocklistCategory.ADS,
            risk = BreakageRisk.SAFE,
            approximateEntries = 57_000,
        ),
        BlocklistSource(
            id = "someonewhocares",
            title = "Dan Pollock's list",
            maintainer = "Dan Pollock",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_4.txt",
            homepage = "https://someonewhocares.org/",
            category = BlocklistCategory.ADS,
            risk = BreakageRisk.SAFE,
            approximateEntries = 13_000,
        ),
        BlocklistSource(
            id = "adaway",
            title = "AdAway",
            maintainer = "AdAway",
            url = "https://adaway.org/hosts.txt",
            homepage = "https://github.com/AdAway/adaway.github.io",
            category = BlocklistCategory.ADS,
            risk = BreakageRisk.SAFE,
            enabledByDefault = true,
            approximateEntries = 6_500,
        ),
        // ---- PRIVACY -----------------------------------------------------------------
        BlocklistSource(
            id = "shadowwhisperer-tracking",
            title = "ShadowWhisperer Tracking",
            maintainer = "ShadowWhisperer",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_69.txt",
            homepage = "https://github.com/ShadowWhisperer/BlockLists",
            category = BlocklistCategory.PRIVACY,
            risk = BreakageRisk.MODERATE,
            approximateEntries = 110_000,
        ),
        BlocklistSource(
            id = "easyprivacy",
            title = "EasyPrivacy",
            maintainer = "EasyList",
            url = "https://easylist.to/easylist/easyprivacy.txt",
            homepage = "https://easylist.to",
            category = BlocklistCategory.PRIVACY,
            risk = BreakageRisk.MODERATE,
            approximateEntries = 53_000,
        ),
        BlocklistSource(
            id = "frogeye-first",
            title = "Frogeye first-party trackers",
            maintainer = "Geoffrey Frogeye",
            url = "https://hostfiles.frogeye.fr/firstparty-trackers-hosts.txt",
            homepage = "https://hostfiles.frogeye.fr",
            category = BlocklistCategory.PRIVACY,
            risk = BreakageRisk.MODERATE,
            approximateEntries = 15_000,
        ),
        BlocklistSource(
            id = "yoyo",
            title = "Peter Lowe's list",
            maintainer = "Peter Lowe",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_3.txt",
            homepage = "https://pgl.yoyo.org/adservers/",
            category = BlocklistCategory.PRIVACY,
            risk = BreakageRisk.SAFE,
            approximateEntries = 3_500,
        ),
        // ---- ANNOYANCES --------------------------------------------------------------
        BlocklistSource(
            id = "push-notifications",
            title = "Anti push notifications",
            maintainer = "Dandelion Sprout",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_39.txt",
            homepage = "https://github.com/DandelionSprout/adfilt",
            category = BlocklistCategory.ANNOYANCES,
            risk = BreakageRisk.SAFE,
            approximateEntries = 440,
        ),
        BlocklistSource(
            id = "nocoin",
            title = "NoCoin",
            maintainer = "hoshsadiq",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_8.txt",
            homepage = "https://github.com/hoshsadiq/adblock-nocoin-list/",
            category = BlocklistCategory.ANNOYANCES,
            risk = BreakageRisk.SAFE,
            approximateEntries = 310,
        ),
        // ---- SECURITY ----------------------------------------------------------------
        BlocklistSource(
            id = "hagezi-tif",
            title = "HaGeZi Threat Intelligence Feeds",
            maintainer = "HaGeZi",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_44.txt",
            homepage = "https://github.com/AdguardTeam/HostlistsRegistry",
            category = BlocklistCategory.SECURITY,
            risk = BreakageRisk.AGGRESSIVE,
            approximateEntries = 2_200_000,
        ),
        BlocklistSource(
            id = "phishing-army",
            title = "Phishing Army",
            maintainer = "Andrea Draghetti",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_18.txt",
            homepage = "https://phishing.army/",
            category = BlocklistCategory.SECURITY,
            risk = BreakageRisk.SAFE,
            approximateEntries = 160_000,
        ),
        BlocklistSource(
            id = "shadowwhisperer-malware",
            title = "ShadowWhisperer Malware",
            maintainer = "ShadowWhisperer",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_42.txt",
            homepage = "https://github.com/ShadowWhisperer/BlockLists",
            category = BlocklistCategory.SECURITY,
            risk = BreakageRisk.MODERATE,
            approximateEntries = 44_000,
        ),
        BlocklistSource(
            id = "phishtank",
            title = "PhishTank & OpenPhish",
            maintainer = "AdGuard",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_30.txt",
            homepage = "https://gitlab.com/malware-filter/phishing-filter",
            category = BlocklistCategory.SECURITY,
            risk = BreakageRisk.MODERATE,
            approximateEntries = 36_000,
        ),
        BlocklistSource(
            id = "dandelion-malware",
            title = "Dandelion Sprout Anti-Malware",
            maintainer = "Dandelion Sprout",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_12.txt",
            homepage = "https://github.com/DandelionSprout/adfilt",
            category = BlocklistCategory.SECURITY,
            risk = BreakageRisk.MODERATE,
            approximateEntries = 12_000,
        ),
        BlocklistSource(
            id = "urlhaus",
            title = "URLhaus",
            maintainer = "abuse.ch",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_11.txt",
            homepage = "https://urlhaus.abuse.ch/",
            category = BlocklistCategory.SECURITY,
            risk = BreakageRisk.SAFE,
            approximateEntries = 6_000,
        ),
        BlocklistSource(
            id = "hagezi-badware",
            title = "HaGeZi Badware Hosters",
            maintainer = "HaGeZi",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_55.txt",
            homepage = "https://github.com/AdguardTeam/HostlistsRegistry",
            category = BlocklistCategory.SECURITY,
            risk = BreakageRisk.MODERATE,
            approximateEntries = 1_300,
        ),
        BlocklistSource(
            id = "stalkerware",
            title = "Stalkerware Indicators",
            maintainer = "Echap",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_31.txt",
            homepage = "https://github.com/AssoEchap/stalkerware-indicators",
            category = BlocklistCategory.SECURITY,
            risk = BreakageRisk.SAFE,
            approximateEntries = 920,
        ),
        // ---- NATIVE ------------------------------------------------------------------
        BlocklistSource(
            id = "native-oppo",
            title = "OPPO & Realme trackers",
            maintainer = "HaGeZi",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_66.txt",
            homepage = "https://github.com/AdguardTeam/HostlistsRegistry",
            category = BlocklistCategory.NATIVE,
            risk = BreakageRisk.MODERATE,
            approximateEntries = 480,
        ),
        BlocklistSource(
            id = "native-windows",
            title = "Windows & Office trackers",
            maintainer = "HaGeZi",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_63.txt",
            homepage = "https://github.com/AdguardTeam/HostlistsRegistry",
            category = BlocklistCategory.NATIVE,
            risk = BreakageRisk.MODERATE,
            approximateEntries = 390,
        ),
        BlocklistSource(
            id = "native-xiaomi",
            title = "Xiaomi trackers",
            maintainer = "HaGeZi",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_60.txt",
            homepage = "https://github.com/AdguardTeam/HostlistsRegistry",
            category = BlocklistCategory.NATIVE,
            risk = BreakageRisk.MODERATE,
            approximateEntries = 350,
        ),
        BlocklistSource(
            id = "native-vivo",
            title = "vivo trackers",
            maintainer = "HaGeZi",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_65.txt",
            homepage = "https://github.com/AdguardTeam/HostlistsRegistry",
            category = BlocklistCategory.NATIVE,
            risk = BreakageRisk.MODERATE,
            approximateEntries = 230,
        ),
        BlocklistSource(
            id = "native-samsung",
            title = "Samsung trackers",
            maintainer = "HaGeZi",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_61.txt",
            homepage = "https://github.com/AdguardTeam/HostlistsRegistry",
            category = BlocklistCategory.NATIVE,
            risk = BreakageRisk.MODERATE,
            approximateEntries = 200,
        ),
        BlocklistSource(
            id = "smart-tv",
            title = "Smart-TV trackers",
            maintainer = "Perflyst & Dandelion Sprout",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_7.txt",
            homepage = "https://github.com/Perflyst/PiHoleBlocklist",
            category = BlocklistCategory.NATIVE,
            risk = BreakageRisk.MODERATE,
            approximateEntries = 160,
        ),
        BlocklistSource(
            id = "native-apple",
            title = "Apple trackers",
            maintainer = "HaGeZi",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_67.txt",
            homepage = "https://github.com/AdguardTeam/HostlistsRegistry",
            category = BlocklistCategory.NATIVE,
            risk = BreakageRisk.MODERATE,
            approximateEntries = 110,
        ),
        // ---- REGIONAL ----------------------------------------------------------------
        BlocklistSource(
            id = "regional-chn-adrules",
            title = "CHN: AdRules DNS List",
            maintainer = "Cats-Team",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_29.txt",
            homepage = "https://github.com/Cats-Team/AdRules",
            category = BlocklistCategory.REGIONAL,
            risk = BreakageRisk.MODERATE,
            approximateEntries = 180_000,
        ),
        BlocklistSource(
            id = "regional-pol-cert",
            title = "POL: CERT Polska List of malicious domains",
            maintainer = "cert.pl",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_41.txt",
            homepage = "https://cert.pl/posts/2020/03/ostrzezenia_phishing/",
            category = BlocklistCategory.REGIONAL,
            risk = BreakageRisk.SAFE,
            approximateEntries = 110_000,
        ),
        BlocklistSource(
            id = "regional-chn-antiad",
            title = "CHN: anti-AD",
            maintainer = "anti-ad.net",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_21.txt",
            homepage = "https://anti-ad.net/",
            category = BlocklistCategory.REGIONAL,
            risk = BreakageRisk.MODERATE,
            approximateEntries = 99_000,
        ),
        BlocklistSource(
            id = "regional-vnm-abpvn",
            title = "VNM: ABPVN List",
            maintainer = "abpvn.com",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_16.txt",
            homepage = "https://abpvn.com/",
            category = BlocklistCategory.REGIONAL,
            risk = BreakageRisk.MODERATE,
            approximateEntries = 19_000,
        ),
        BlocklistSource(
            id = "regional-tur-hosts",
            title = "TUR: Turkish Ad Hosts",
            maintainer = "symbuzzer",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_40.txt",
            homepage = "https://github.com/symbuzzer/Turkish-Ad-Hosts",
            category = BlocklistCategory.REGIONAL,
            risk = BreakageRisk.SAFE,
            approximateEntries = 2_100,
        ),
        BlocklistSource(
            id = "regional-ukr-security",
            title = "Ukrainian Security Filter",
            maintainer = "braveinnovators",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_62.txt",
            homepage = "https://github.com/braveinnovators/ukrainian-security-filter",
            category = BlocklistCategory.REGIONAL,
            risk = BreakageRisk.SAFE,
            approximateEntries = 1_700,
        ),
        BlocklistSource(
            id = "regional-tur-adlist",
            title = "TUR: turk-adlist",
            maintainer = "bkrucarci",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_26.txt",
            homepage = "https://github.com/bkrucarci/turk-adlist",
            category = BlocklistCategory.REGIONAL,
            risk = BreakageRisk.SAFE,
            approximateEntries = 1_200,
        ),
        BlocklistSource(
            id = "regional-swe-frellwit",
            title = "SWE: Frellwit's Swedish Hosts File",
            maintainer = "lassekongo83",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_17.txt",
            homepage = "https://github.com/lassekongo83/Frellwits-filter-lists/",
            category = BlocklistCategory.REGIONAL,
            risk = BreakageRisk.SAFE,
            approximateEntries = 1_200,
        ),
        BlocklistSource(
            id = "regional-nor-nordic",
            title = "NOR: Dandelion Sprouts nordiske filtre",
            maintainer = "DandelionSprout",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_13.txt",
            homepage = "https://github.com/DandelionSprout/adfilt",
            category = BlocklistCategory.REGIONAL,
            risk = BreakageRisk.SAFE,
            approximateEntries = 650,
        ),
        BlocklistSource(
            id = "regional-kor-yous",
            title = "KOR: YousList",
            maintainer = "yous",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_15.txt",
            homepage = "https://github.com/yous/YousList",
            category = BlocklistCategory.REGIONAL,
            risk = BreakageRisk.SAFE,
            approximateEntries = 620,
        ),
        BlocklistSource(
            id = "regional-kor-listkr",
            title = "KOR: List-KR DNS",
            maintainer = "List-KR",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_25.txt",
            homepage = "https://github.com/List-KR/List-KR",
            category = BlocklistCategory.REGIONAL,
            risk = BreakageRisk.SAFE,
            approximateEntries = 380,
        ),
        BlocklistSource(
            id = "regional-pol-pihole",
            title = "POL: Polish filters for Pi-hole",
            maintainer = "certyficate.it",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_14.txt",
            homepage = "https://www.certyficate.it/",
            category = BlocklistCategory.REGIONAL,
            risk = BreakageRisk.SAFE,
            approximateEntries = 340,
        ),
        BlocklistSource(
            id = "regional-irn-persian",
            title = "IRN: PersianBlocker list",
            maintainer = "MasterKia",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_19.txt",
            homepage = "https://github.com/MasterKia/PersianBlocker",
            category = BlocklistCategory.REGIONAL,
            risk = BreakageRisk.SAFE,
            approximateEntries = 260,
        ),
        BlocklistSource(
            id = "regional-mkd-pihole",
            title = "MKD: Macedonian Pi-hole Blocklist",
            maintainer = "cchevy",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_20.txt",
            homepage = "https://github.com/cchevy/macedonian-pi-hole-blocklist",
            category = BlocklistCategory.REGIONAL,
            risk = BreakageRisk.SAFE,
            approximateEntries = 210,
        ),
        BlocklistSource(
            id = "regional-isr-hebrew",
            title = "ISR: EasyList Hebrew",
            maintainer = "easylist",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_43.txt",
            homepage = "https://github.com/easylist/EasyListHebrew",
            category = BlocklistCategory.REGIONAL,
            risk = BreakageRisk.SAFE,
            approximateEntries = 180,
        ),
        BlocklistSource(
            id = "regional-idn-abpindo",
            title = "IDN: ABPindo",
            maintainer = "ABPindo",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_22.txt",
            homepage = "https://github.com/ABPindo/indonesianadblockrules",
            category = BlocklistCategory.REGIONAL,
            risk = BreakageRisk.SAFE,
            approximateEntries = 160,
        ),
        BlocklistSource(
            id = "regional-hun-hufilter",
            title = "HUN: Hufilter",
            maintainer = "hufilter",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_35.txt",
            homepage = "https://github.com/hufilter/hufilter",
            category = BlocklistCategory.REGIONAL,
            risk = BreakageRisk.SAFE,
            approximateEntries = 90,
        ),
        BlocklistSource(
            id = "regional-lit-easylist",
            title = "LIT: EasyList Lithuania",
            maintainer = "EasyList-Lithuania",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_36.txt",
            homepage = "https://github.com/EasyList-Lithuania/easylist_lithuania",
            category = BlocklistCategory.REGIONAL,
            risk = BreakageRisk.SAFE,
            approximateEntries = 30,
        ),
        // ---- OTHER -------------------------------------------------------------------
        BlocklistSource(
            id = "oisd-nsfw",
            title = "OISD NSFW",
            maintainer = "oisd",
            url = "https://nsfw.oisd.nl/",
            homepage = "https://oisd.nl",
            category = BlocklistCategory.OTHER,
            risk = BreakageRisk.AGGRESSIVE,
            approximateEntries = 490_000,
        ),
        BlocklistSource(
            id = "gambling",
            title = "HaGeZi Gambling",
            maintainer = "HaGeZi",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_47.txt",
            homepage = "https://github.com/AdguardTeam/HostlistsRegistry",
            category = BlocklistCategory.OTHER,
            risk = BreakageRisk.AGGRESSIVE,
            approximateEntries = 410_000,
        ),
        BlocklistSource(
            id = "piracy",
            title = "HaGeZi Anti-Piracy",
            maintainer = "HaGeZi",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_46.txt",
            homepage = "https://github.com/AdguardTeam/HostlistsRegistry",
            category = BlocklistCategory.OTHER,
            risk = BreakageRisk.AGGRESSIVE,
            approximateEntries = 40_000,
        ),
        BlocklistSource(
            id = "no-google",
            title = "No Google",
            maintainer = "Nick Spaargaren",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_37.txt",
            homepage = "https://github.com/nickspaargaren/no-google",
            category = BlocklistCategory.OTHER,
            risk = BreakageRisk.AGGRESSIVE,
            approximateEntries = 1_600,
        ),
        BlocklistSource(
            id = "dating",
            title = "ShadowWhisperer Dating",
            maintainer = "ShadowWhisperer",
            url = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_57.txt",
            homepage = "https://github.com/ShadowWhisperer/BlockLists",
            category = BlocklistCategory.OTHER,
            risk = BreakageRisk.AGGRESSIVE,
            approximateEntries = 1_400,
        ),
    )

    fun byId(id: String): BlocklistSource? = sources.firstOrNull { it.id == id }

    /**
     * The sources in [category], safest first and largest first within a risk tier.
     *
     * The order is the recommendation: what somebody scrolling past should try before what is
     * underneath it. Sorting by size alone would put the list most likely to break their phone
     * at the top of every category.
     */
    fun inCategory(category: BlocklistCategory): List<BlocklistSource> =
        sources.filter { it.category == category }
            .sortedWith(compareBy<BlocklistSource> { it.risk.ordinal }.thenByDescending { it.approximateEntries })

    /** The sources in [category] at one risk tier, for the picker's sub-groups. */
    fun inCategory(category: BlocklistCategory, risk: BreakageRisk): List<BlocklistSource> =
        inCategory(category).filter { it.risk == risk }

    /**
     * The sources a set of choices selects. A source the user has never touched isn't in the
     * map, and falls back to its own default — which is what lets a later release add a list, or
     * change a default, without silently overriding a decision someone made on purpose.
     */
    fun enabled(choices: Map<String, Boolean>): List<BlocklistSource> =
        sources.filter { choices[it.id] ?: it.enabledByDefault }

    fun isEnabled(id: String, choices: Map<String, Boolean>): Boolean =
        choices[id] ?: (byId(id)?.enabledByDefault ?: false)

    /** How many of [category] are on, for the "3 of 7" the category index shows. */
    fun enabledCount(category: BlocklistCategory, choices: Map<String, Boolean>): Int =
        inCategory(category).count { isEnabled(it.id, choices) }
}

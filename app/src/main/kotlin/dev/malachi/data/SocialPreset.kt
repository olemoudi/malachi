package dev.malachi.data

/**
 * One social network, as the handful of names that carry its logins, pixels, embeds and SDKs.
 *
 * [name] is a proper noun and is not translated. [ownPackages] are the network's own apps, which
 * the preset is never offered to: blocking Facebook inside Facebook is not a privacy setting, it
 * is a broken app.
 */
data class SocialNetwork(
    val id: String,
    val name: String,
    val domains: List<String>,
    val ownPackages: Set<String>,
)

/**
 * "Block the social networks in this app", as one decision.
 *
 * **Why per app and never a list.** No blocklist can refuse `facebook.com` or `graph.facebook.com`
 * without breaking Facebook, Instagram and WhatsApp for everybody who has them — so every list
 * leaves them alone, and that is exactly the path a news app's Facebook SDK, its pixels and Meta's
 * ad network all take. Found on a phone where a sports newspaper was asking Facebook and LinkedIn
 * for things all day and no list could say no. Scoped to one app the answer is easy, and these are
 * written as ordinary per-app rules: listed, explained and removable one by one like any other.
 *
 * **Short on purpose, and every entry a whole registrable domain the network owns.** The rule is a
 * suffix, so `fbcdn.net` is all of Meta's CDN; there is nothing to gain from listing hosts under it
 * and a lot to lose from a name the network does not own. Link shorteners (`t.co`) are left out:
 * refusing them breaks a link the user tapped rather than something the app did behind their back.
 */
object SocialPreset {

    val networks: List<SocialNetwork> = listOf(
        SocialNetwork(
            id = "meta",
            name = "Facebook · Instagram",
            domains = listOf("facebook.com", "facebook.net", "fbcdn.net", "fbsbx.com", "instagram.com", "cdninstagram.com"),
            ownPackages = setOf(
                "com.facebook.katana", "com.facebook.lite", "com.facebook.orca", "com.facebook.mlite",
                "com.instagram.android", "com.instagram.lite", "com.instagram.barcelona",
                "com.whatsapp", "com.whatsapp.w4b",
            ),
        ),
        SocialNetwork(
            id = "x",
            name = "X",
            domains = listOf("twitter.com", "x.com", "twimg.com"),
            ownPackages = setOf("com.twitter.android"),
        ),
        SocialNetwork(
            id = "linkedin",
            name = "LinkedIn",
            domains = listOf("linkedin.com", "licdn.com"),
            ownPackages = setOf("com.linkedin.android"),
        ),
        SocialNetwork(
            id = "tiktok",
            name = "TikTok",
            domains = listOf("tiktok.com", "tiktokv.com", "tiktokcdn.com"),
            ownPackages = setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go"),
        ),
        SocialNetwork(
            id = "pinterest",
            name = "Pinterest",
            domains = listOf("pinterest.com", "pinimg.com"),
            ownPackages = setOf("com.pinterest"),
        ),
        SocialNetwork(
            id = "snapchat",
            name = "Snapchat",
            domains = listOf("snapchat.com", "sc-static.net"),
            ownPackages = setOf("com.snapchat.android"),
        ),
    )

    /** The networks that make sense to block inside [packageName]: all but its own. */
    fun offeredFor(packageName: String): List<SocialNetwork> = networks.filter { packageName !in it.ownPackages }

    /**
     * The networks already fully blocked in [packageName] by rules the user wrote there — which is
     * how the screen knows to offer undoing it rather than doing it again.
     */
    fun blockedIn(settings: MalachiSettings, packageName: String): Set<String> {
        val blocked = settings.appRulesFor(packageName).filter { it.block }.map { it.domain }.toSet()
        return offeredFor(packageName).filter { network -> network.domains.all { it in blocked } }.map { it.id }.toSet()
    }

    /**
     * [packageName]'s rules with the chosen networks blocked. A rule this app already had for one
     * of those names — an exception written on purpose, say — is replaced, because the user has
     * just said what they want for it.
     */
    fun applied(settings: MalachiSettings, packageName: String, networkIds: Set<String>): MalachiSettings {
        val domains = offeredFor(packageName).filter { it.id in networkIds }.flatMap { it.domains }.toSet()
        if (domains.isEmpty()) return settings
        val kept = settings.appRules.filterNot { it.packageName == packageName && it.domain in domains }
        return settings.copy(appRules = kept + domains.map { AppRule(it, packageName, block = true) })
    }

    /**
     * The block rules the preset would have written for [networkIds] taken away, and only those: an
     * *exception* for one of the names is a different decision and is left exactly where it is.
     */
    fun removed(settings: MalachiSettings, packageName: String, networkIds: Set<String>): MalachiSettings {
        val domains = networks.filter { it.id in networkIds }.flatMap { it.domains }.toSet()
        return settings.copy(
            appRules = settings.appRules.filterNot { it.packageName == packageName && it.block && it.domain in domains },
        )
    }
}

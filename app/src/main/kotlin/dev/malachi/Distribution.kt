package dev.malachi

import dev.malachi.data.UpdateChannel

/**
 * Where a release lives. These names are baked into install links and into every copy of the
 * app already on a phone, so they never change: the stage (beta, 1.0) is user-facing text and
 * belongs in the version label, not in a filename that has to stay resolvable forever.
 */
object Distribution {

    const val REPO_URL = "https://github.com/olemoudi/malachi"

    /**
     * Always the newest *stable* release's APK. What a sideload install link points at.
     *
     * Still `latest` and still correct now that test builds exist, because those are published
     * as GitHub pre-releases and `latest` skips them. That is the whole reason for the flag: the
     * QR in the README never has to be reprinted.
     */
    const val APK_URL = "https://github.com/olemoudi/malachi/releases/latest/download/malachi.apk"

    /**
     * The manifest an install published before channels existed still polls.
     *
     * Kept resolving on purpose. Those copies read whatever `latest` names, which is the stable
     * channel, so they migrate onto it by themselves at their next check with nothing to do.
     */
    const val VERSION_JSON_URL = "https://github.com/olemoudi/malachi/releases/latest/download/version.json"

    /**
     * What each channel currently serves, as a file committed to the repository.
     *
     * Not a release asset, because "the newest release on GitHub" and "what this channel should
     * serve" stopped being the same question the moment test builds started shipping between
     * stable ones. A committed manifest is explicit, reviewable and revertible, and the APK it
     * names is pinned to a tag rather than to a `latest` that can move between reading the
     * manifest and fetching the file it named.
     */
    fun manifestUrl(channel: UpdateChannel): String = when (channel) {
        UpdateChannel.STABLE -> "$MANIFEST_BASE/stable.json"
        UpdateChannel.TESTING -> "$MANIFEST_BASE/testing.json"
    }

    private const val MANIFEST_BASE =
        "https://raw.githubusercontent.com/olemoudi/malachi/main/channels"
}

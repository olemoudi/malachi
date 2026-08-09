package dev.malachi

/**
 * Where a release lives. These names are baked into install links and into every copy of the
 * app already on a phone, so they never change: the stage (beta, 1.0) is user-facing text and
 * belongs in the version label, not in a filename that has to stay resolvable forever.
 */
object Distribution {

    const val REPO_URL = "https://github.com/olemoudi/malachi"

    /** Always the newest release's APK. What a sideload install link points at. */
    const val APK_URL = "https://github.com/olemoudi/malachi/releases/latest/download/malachi.apk"

    /** Small JSON published by CI describing the newest release; drives the in-app update. */
    const val VERSION_JSON_URL = "https://github.com/olemoudi/malachi/releases/latest/download/version.json"
}

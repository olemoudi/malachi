package dev.malachi.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The newest release, as described by the version.json CI publishes beside the APK. */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String = "",
    val apk: String = "",
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): UpdateInfo? =
            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/** Pure update decision: a newer version code than what's installed, and an APK to fetch. */
fun UpdateInfo.isNewerThan(installedVersionCode: Int): Boolean =
    versionCode > installedVersionCode && apk.isNotBlank()

/**
 * Whether an APK url from version.json may be downloaded.
 *
 * The OS already refuses to install anything not signed with our key, so this is belt and
 * braces — but a file fetched from the network should not be able to point the downloader at
 * an arbitrary host, and the check is one line.
 */
fun trustedApkUrl(url: String): Boolean = url.startsWith("https://github.com/olemoudi/malachi/")

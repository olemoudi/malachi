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

/**
 * Why a downloaded file must not be handed to the installer, or null when it may be.
 *
 * A 200 response is not evidence that what arrived is our APK. A captive portal answers every
 * request with a login page and the right status code; a CDN can serve a stale or truncated
 * object; "latest" can move between reading version.json and fetching the file it named. All
 * three end as bytes on disk that a `PackageInstaller` session will choke on, and the session
 * failing is a worse way to find out than not opening one.
 *
 * The caller gets [archivePackage] and [archiveVersionCode] from the platform's own parse of the
 * file, so null for either means it is not a readable APK at all — which is the captive-portal
 * case, and the truncated-download case, without either needing its own check.
 */
fun rejectionReason(
    archivePackage: String?,
    archiveVersionCode: Int?,
    expectedPackage: String,
    installedVersionCode: Int,
): String? = when {
    archivePackage == null || archiveVersionCode == null -> "not a readable APK"
    archivePackage != expectedPackage -> "belongs to $archivePackage"
    // Not merely "different": installing an older or equal build is a no-op at best and a
    // downgrade the platform refuses at worst, and either way it burns the download again next
    // time. Whatever we fetched has to actually be an upgrade.
    archiveVersionCode <= installedVersionCode ->
        "version $archiveVersionCode is not newer than the installed $installedVersionCode"
    else -> null
}

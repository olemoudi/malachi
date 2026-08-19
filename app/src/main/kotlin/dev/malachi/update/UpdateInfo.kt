package dev.malachi.update

import dev.malachi.data.UpdateChannel
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

/** The newest release on a channel, as described by the manifest CI publishes for it. */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String = "",
    val apk: String = "",

    /**
     * What changed, by language tag, for the person about to be given it.
     *
     * A map rather than two fields because the app is already bilingual and a third language
     * should be a line in a manifest rather than a release of the app. Missing is not an error:
     * an update with nothing to say about itself still installs.
     *
     * Read leniently, and that is load-bearing rather than tidy — see [LenientNotes].
     */
    @Serializable(with = LenientNotes::class)
    val notes: Map<String, String> = emptyMap(),
) {

    /**
     * The notes in [language], falling back to English and then to whatever is there.
     *
     * The fallback chain matters more than it looks: a manifest written in a hurry with only one
     * language in it should still say something to everybody, and the alternative — showing
     * nothing because the exact tag is missing — is indistinguishable from a release that
     * changed nothing.
     */
    fun notesFor(language: String): String = notes.notesIn(language)

    /**
     * The notes as they will be written to disk, and no larger.
     *
     * They are kept in the settings blob so the launch after an update can say what changed
     * without waiting on a network — which makes a changelog the one thing this app stores that
     * it did not itself compose. Every file Malachi writes has a bound, and a manifest is a
     * document on the internet: a changelog written at length, or a manifest edited wrongly,
     * would otherwise sit in a blob that is decoded on every settings read for the life of the
     * install. Truncated rather than dropped — half a sentence still says a release happened.
     */
    fun notesWorthKeeping(): Map<String, String> = notes.entries
        .take(MAX_NOTE_LANGUAGES)
        .associate { (language, text) -> language to text.take(MAX_NOTE_CHARS) }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Enough for a changelog somebody will actually read, and a ceiling on one nobody will. */
        internal const val MAX_NOTE_CHARS = 2_000
        internal const val MAX_NOTE_LANGUAGES = 8

        fun parse(text: String): UpdateInfo? =
            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/**
 * The notes in [language], falling back to English and then to whatever is there.
 *
 * The fallback chain matters more than it looks: a manifest written in a hurry with only one
 * language in it should still say something to everybody, and the alternative — showing nothing
 * because the exact tag is missing — is indistinguishable from a release that changed nothing.
 *
 * On the map rather than on [UpdateInfo] because it is asked twice, of two different things: of a
 * manifest just fetched, and of the copy kept in the settings for the launch after the update.
 * Two copies of a fallback chain is two chains that can come to disagree.
 */
fun Map<String, String>.notesIn(language: String): String =
    this[language] ?: this["en"] ?: values.firstOrNull().orEmpty()

/**
 * Reads [UpdateInfo.notes] and refuses to be the reason an update does not happen.
 *
 * Anything that is not an object of strings becomes no notes at all: a hand-written changelog
 * with a stray comma, a field that meant something else in an older manifest, a future shape this
 * version has never seen. Strict, the whole document fails to decode and the update it described
 * never happens — and this is the one part of the app that cannot be fixed remotely, so the
 * failure would be permanent on every phone that read that manifest.
 *
 * Found by a test written before the field existed, which had used `"notes"` as its example of a
 * key the app did not know: making it known made that manifest unparseable.
 */
private object LenientNotes :
    JsonTransformingSerializer<Map<String, String>>(MapSerializer(String.serializer(), String.serializer())) {

    override fun transformDeserialize(element: JsonElement): JsonElement {
        val notes = element as? JsonObject ?: return JsonObject(emptyMap())
        // Per entry, not all-or-nothing: one language written wrongly must not silence the rest.
        return JsonObject(notes.filterValues { it is JsonPrimitive && it.isString })
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
    archiveVersionName: String? = null,
    expectedChannel: UpdateChannel? = null,
): String? = when {
    archivePackage == null || archiveVersionCode == null -> "not a readable APK"
    archivePackage != expectedPackage -> "belongs to $archivePackage"
    // Not merely "different": installing an older or equal build is a no-op at best and a
    // downgrade the platform refuses at worst, and either way it burns the download again next
    // time. Whatever we fetched has to actually be an upgrade.
    archiveVersionCode <= installedVersionCode ->
        "version $archiveVersionCode is not newer than the installed $installedVersionCode"
    // Asked of the *file*, not of the manifest that named it. A manifest is a document on the
    // internet, and one edited wrongly — a promotion that pointed the stable channel at a test
    // build, a copy-paste between the two — would otherwise move somebody onto a lineage they
    // never chose, silently, which is the single failure having channels at all exists to
    // prevent. The suffix is in the APK and the APK cannot be talked out of it.
    expectedChannel != null && archiveVersionName != null &&
        !UpdatePolicy.belongsToChannel(archiveVersionName, expectedChannel) ->
        "\"$archiveVersionName\" is not a ${expectedChannel.name.lowercase()} build"
    else -> null
}

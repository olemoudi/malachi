package dev.malachi.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.PendingIntentCompat
import dev.malachi.Distribution
import dev.malachi.MalachiApplication
import dev.malachi.debug.DebugLog
import dev.malachi.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Outcome of one update check, so callers can decide whether a retry makes sense. */
enum class UpdateCheckOutcome {
    UP_TO_DATE,

    /** An install session was committed; the system may still ask the user to confirm. */
    INSTALL_STARTED,

    /** Transient problem (the fetch, the download) — worth retrying with backoff. */
    TRANSIENT_FAILURE,

    /** The install session itself failed — retrying immediately won't help. */
    INSTALL_FAILURE,

    /** Nothing was looked at: another check holds the lock, or the connection is metered. */
    NOT_ATTEMPTED,
}

/**
 * Self-update from GitHub Releases, the sideload equivalent of a store.
 *
 * An app distributed outside a store has no one to push it a fix, so it has to fetch its own:
 * CI publishes an APK and a version.json beside it, and this compares version codes and
 * installs. The install is requested without user action, which Android grants once Malachi is
 * its own installer of record; when the system insists on confirming, [InstallReceiver] turns
 * that into a notification so the update isn't lost because the check ran in the background.
 */
class Updater(
    private val context: Context,
    /**
     * Where the version file lives. A parameter only so a test can point it at a server it
     * controls — the retry behaviour and the refusal to install the wrong file are the parts of
     * this class that matter most and were the parts nothing exercised.
     */
    private val versionJsonUrl: String = Distribution.VERSION_JSON_URL,
) {

    // Derived from the shared client (pools reused); longer timeouts for a ~15 MB download.
    private val client = Http.client.newBuilder()
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    /**
     * Single-flight: checks fire from app launch, from the periodic worker and from the manual
     * button, and two overlapping runs are actively harmful — [install] abandons stale sessions,
     * so a second run would abort the first one's half-written session, and both would download
     * the same APK. A second caller reports UP_TO_DATE and lets the first finish.
     */
    suspend fun checkAndUpdate(force: Boolean = false): UpdateCheckOutcome =
        try {
            attempt(force)
        } catch (cancellation: CancellationException) {
            // Cooperative cancellation is not a failure and must not be swallowed: swallowing it
            // leaves a coroutine that ignores its own scope being torn down.
            throw cancellation
        } catch (t: Throwable) {
            // The safety net, and the reason it is this wide. This is the only way a sideloaded
            // app receives a fix, so an unhandled throw here does not cost one update — it costs
            // every future one, on a phone nobody can reach. Anything unexpected becomes a
            // logged, retryable outcome rather than an exception crossing into a worker.
            DebugLog.e(TAG, "update check failed unexpectedly", t)
            UpdateCenter.report(UpdateUiState.Failed(t.javaClass.simpleName))
            UpdateCheckOutcome.TRANSIENT_FAILURE
        }

    private suspend fun attempt(force: Boolean): UpdateCheckOutcome {
        if (!force && wifiOnlyBlocks()) {
            DebugLog.i(TAG, "update skipped: Wi-Fi-only is on and this connection is metered")
            UpdateCenter.report(UpdateUiState.SkippedMetered)
            return UpdateCheckOutcome.NOT_ATTEMPTED
        }
        if (!updateMutex.tryLock()) {
            DebugLog.i(TAG, "update check already in flight; skipping")
            UpdateCenter.report(UpdateUiState.AlreadyChecking)
            return UpdateCheckOutcome.NOT_ATTEMPTED
        }
        try {
            return doCheckAndUpdate()
        } finally {
            updateMutex.unlock()
        }
    }

    /**
     * Runs [block] until it returns something, with a widening gap between goes.
     *
     * One attempt is not a check, it is a coin toss: a phone changes network mid-request, a CDN
     * edge returns a 5xx, a radio wakes up half a second after the call went out. All of those
     * presented as "update failed" on a screen the user was looking at, which is how this came to
     * be reported. The periodic worker retries too, but hours later and only for the outcomes it
     * can see — the person who just tapped the button deserves the retry now.
     */
    private suspend fun <T : Any> retrying(what: String, block: () -> T?): T? {
        var wait = RETRY_BASE_MILLIS
        repeat(FETCH_ATTEMPTS) { index ->
            val outcome = runCatching(block)
            outcome.getOrNull()?.let { return it }
            outcome.exceptionOrNull()?.let { failure ->
                if (failure is CancellationException) throw failure
                DebugLog.w(TAG, "$what failed (attempt ${index + 1} of $FETCH_ATTEMPTS)", failure)
            }
            if (index < FETCH_ATTEMPTS - 1) {
                delay(wait)
                wait *= 3
            }
        }
        return null
    }

    /** True when the user restricted updates to Wi-Fi and the active connection is metered. */
    private suspend fun wifiOnlyBlocks(): Boolean {
        val app = context.applicationContext as? MalachiApplication ?: return false
        val wifiOnly = runCatching { app.settingsStore.current().updateWifiOnly }.getOrDefault(false)
        if (!wifiOnly) return false
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        return runCatching { cm.isActiveNetworkMetered }.getOrDefault(false)
    }

    private suspend fun doCheckAndUpdate(): UpdateCheckOutcome = withContext(Dispatchers.IO) {
        DebugLog.i(TAG, "checking for an update")
        UpdateCenter.report(UpdateUiState.Checking)
        val info = retrying("fetching version.json") { fetchInfo() }
        if (info == null) {
            UpdateCenter.report(UpdateUiState.Failed("fetch"))
            return@withContext UpdateCheckOutcome.TRANSIENT_FAILURE
        }
        val current = currentVersionCode()
        DebugLog.i(TAG, "installed=$current latest=${info.versionCode}")
        if (!info.isNewerThan(current)) {
            UpdateCenter.report(UpdateUiState.UpToDate(current))
            return@withContext UpdateCheckOutcome.UP_TO_DATE
        }
        // Announced as soon as it is found, not when it finishes: the download and install can
        // fail, and "there is a new version" is true either way. Guarded because a notification
        // that cannot be posted — a revoked permission, an OEM's own rules — must not be the
        // reason an update does not happen.
        runCatching {
            UpdateNotifications.notifyUpdateFound(context, info.versionName.ifBlank { info.versionCode.toString() })
        }.onFailure { DebugLog.w(TAG, "could not post the update notification", it) }
        if (!trustedApkUrl(info.apk)) {
            DebugLog.e(TAG, "refusing an APK url outside the release host: ${info.apk}")
            UpdateCenter.report(UpdateUiState.Failed("untrusted url"))
            return@withContext UpdateCheckOutcome.INSTALL_FAILURE
        }
        // Unreadable free space is not a reason to refuse; only a definite shortage is.
        val free = runCatching { context.cacheDir.usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (free < REQUIRED_FREE_BYTES) {
            DebugLog.w(TAG, "not enough free space to download the update ($free bytes)")
            UpdateCenter.report(UpdateUiState.Failed("no space"))
            return@withContext UpdateCheckOutcome.TRANSIENT_FAILURE
        }
        UpdateCenter.report(UpdateUiState.Downloading(info))
        val apk = retrying("downloading the APK") { download(info.apk) }
        if (apk == null) {
            UpdateCenter.report(UpdateUiState.Failed("download"))
            return@withContext UpdateCheckOutcome.TRANSIENT_FAILURE
        }
        // What arrived is not necessarily what was asked for. Checked before a session is opened,
        // because the installer failing is a worse way to learn this than not starting.
        val rejection = rejectionReason(apk, expected = info, installed = current)
        if (rejection != null) {
            DebugLog.e(TAG, "refusing the downloaded file: $rejection")
            runCatching { apk.delete() }
            UpdateCenter.report(UpdateUiState.Failed("bad download: $rejection"))
            return@withContext UpdateCheckOutcome.TRANSIENT_FAILURE
        }
        DebugLog.i(TAG, "downloaded ${apk.length()} bytes; installing")
        val error = runCatching { install(apk) }
            .onFailure { DebugLog.e(TAG, "install failed", it) }
            .exceptionOrNull()
        if (error != null) {
            UpdateCenter.report(UpdateUiState.Failed("install: ${error.javaClass.simpleName}"))
            return@withContext UpdateCheckOutcome.INSTALL_FAILURE
        }
        // The terminal status — success, pending confirmation, failure — lands in InstallReceiver.
        UpdateCheckOutcome.INSTALL_STARTED
    }

    private fun currentVersionCode(): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION") info.versionCode
        }
    }

    private fun fetchInfo(): UpdateInfo? {
        client.newCall(Request.Builder().url(versionJsonUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return UpdateInfo.parse(resp.body?.string() ?: return null)
        }
    }

    /**
     * Fetches the APK to a sibling and renames it, so a killed process can never leave a
     * half-file under the name the installer reads. Bounded by [MAX_APK_BYTES] as well as by the
     * call timeout: a redirect to the wrong thing should cost a few seconds, not the free space
     * on the phone.
     */
    private fun download(url: String): File? {
        val target = File(context.cacheDir, APK_FILE)
        val tmp = File(context.cacheDir, "$APK_FILE.part")
        runCatching { tmp.delete() }
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("no body")
            var written = 0L
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > MAX_APK_BYTES) {
                            throw IllegalStateException("refusing a download past $MAX_APK_BYTES bytes")
                        }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            // A truncated body is a successful read of fewer bytes than promised, and it is the
            // commonest way a download "succeeds" wrongly on a phone that changed network.
            val expected = resp.header("Content-Length")?.toLongOrNull()
            if (expected != null && written != expected) {
                throw IllegalStateException("got $written bytes of an expected $expected")
            }
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return target
    }

    /**
     * Why [apk] must not be installed, or null when it may be. The platform's own parse of the
     * file answers all three questions at once: whether it is an APK, whose it is, and which
     * version — see [dev.malachi.update.rejectionReason].
     */
    private fun rejectionReason(apk: File, expected: UpdateInfo, installed: Int): String? {
        val archive = runCatching {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        }.getOrNull()
        val archiveVersion = archive?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toInt() else {
                @Suppress("DEPRECATION") it.versionCode
            }
        }
        val reason = rejectionReason(
            archivePackage = archive?.packageName,
            archiveVersionCode = archiveVersion,
            expectedPackage = context.packageName,
            installedVersionCode = installed,
        )
        if (reason != null) return reason
        // Not fatal on its own — "latest" can move between the two requests — but worth saying,
        // because it is the fingerprint of a stale CDN copy.
        if (archiveVersion != expected.versionCode) {
            DebugLog.w(TAG, "downloaded version $archiveVersion, version.json promised ${expected.versionCode}")
        }
        return null
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        // Abandon sessions leaked by earlier failed attempts, so createSession can't eventually
        // fail with "too many active sessions" on a phone that has had a bad week. Any
        // confirmation notification goes with them: it points at a session that no longer
        // exists, so tapping it would do nothing and explain nothing.
        runCatching {
            if (installer.mySessions.isNotEmpty()) UpdateNotifications.cancel(context)
            installer.mySessions.forEach { installer.abandonSession(it.sessionId) }
        }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("malachi", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val statusIntent = Intent(context, InstallReceiver::class.java).setAction(InstallReceiver.ACTION)
                val pending = PendingIntentCompat.getBroadcast(
                    context, sessionId, statusIntent, PendingIntent.FLAG_UPDATE_CURRENT, true,
                )!!
                session.commit(pending.intentSender)
                DebugLog.i(TAG, "install session $sessionId committed")
            }
        } catch (t: Throwable) {
            // Don't leave a half-written session behind for the next attempt to trip over.
            runCatching { installer.abandonSession(sessionId) }
            throw t
        }
    }

    companion object {
        private const val TAG = "MalachiUpdater"

        /** Process-wide: an Updater is built per check, so the lock has to outlive one. */
        private val updateMutex = Mutex()

        /** The downloaded APK, deleted once the install reaches a terminal state. */
        const val APK_FILE = "update.apk"

        /** Free space required before downloading, so a full phone fails fast, not mid-write. */
        private const val REQUIRED_FREE_BYTES = 150L * 1024 * 1024

        /** A ceiling on what a download may write, so a wrong URL costs seconds, not the disk. */
        private const val MAX_APK_BYTES = 200L * 1024 * 1024

        /** Goes at one network request before it is called a failure, and the gap after the first. */
        private const val FETCH_ATTEMPTS = 3
        private const val RETRY_BASE_MILLIS = 1_500L
    }
}

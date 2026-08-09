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
import kotlinx.coroutines.Dispatchers
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
class Updater(private val context: Context) {

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
    suspend fun checkAndUpdate(force: Boolean = false): UpdateCheckOutcome {
        if (!force && wifiOnlyBlocks()) {
            DebugLog.i(TAG, "update skipped: Wi-Fi-only is on and this connection is metered")
            return UpdateCheckOutcome.UP_TO_DATE
        }
        if (!updateMutex.tryLock()) {
            DebugLog.i(TAG, "update check already in flight; skipping")
            return UpdateCheckOutcome.UP_TO_DATE
        }
        try {
            return doCheckAndUpdate()
        } finally {
            updateMutex.unlock()
        }
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
        val info = runCatching { fetchInfo() }.onFailure { DebugLog.w(TAG, "fetch failed", it) }.getOrNull()
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
        if (!trustedApkUrl(info.apk)) {
            DebugLog.e(TAG, "refusing an APK url outside the release host: ${info.apk}")
            UpdateCenter.report(UpdateUiState.Failed("untrusted url"))
            return@withContext UpdateCheckOutcome.INSTALL_FAILURE
        }
        if (context.cacheDir.usableSpace < REQUIRED_FREE_BYTES) {
            DebugLog.w(TAG, "not enough free space to download the update")
            UpdateCenter.report(UpdateUiState.Failed("no space"))
            return@withContext UpdateCheckOutcome.TRANSIENT_FAILURE
        }
        UpdateCenter.report(UpdateUiState.Downloading(info))
        val apk = runCatching { download(info.apk) }
            .onFailure { DebugLog.w(TAG, "download failed", it) }
            .getOrNull()
        if (apk == null) {
            UpdateCenter.report(UpdateUiState.Failed("download"))
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
        client.newCall(Request.Builder().url(Distribution.VERSION_JSON_URL).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return UpdateInfo.parse(resp.body?.string() ?: return null)
        }
    }

    private fun download(url: String): File {
        val target = File(context.cacheDir, APK_FILE)
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            require(resp.isSuccessful) { "download failed: ${resp.code}" }
            resp.body!!.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        return target
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        // Abandon sessions leaked by earlier failed attempts, so createSession can't eventually
        // fail with "too many active sessions" on a phone that has had a bad week.
        runCatching { installer.mySessions.forEach { installer.abandonSession(it.sessionId) } }
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
    }
}

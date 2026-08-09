package dev.malachi.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

/** Reads device apps via PackageManager (requires QUERY_ALL_PACKAGES). */
class AppInventory(context: Context) {

    private val pm: PackageManager = context.packageManager
    private val ownPackage = context.packageName

    /**
     * Every app that can hold a network connection, launchable or not.
     *
     * The per-app screen deliberately does *not* stop at what has a launcher icon. Trackers live
     * in exactly the components that never appear in the app drawer — a preinstalled OEM service,
     * a background sync agent — and an exclusion list that couldn't name them would be a list
     * that couldn't answer the question the user came with. Malachi itself is left out: it is
     * always outside its own tunnel.
     */
    fun networkApps(): List<InstalledApp> {
        val launchable = launchablePackages()
        return runCatching {
            pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
                .asSequence()
                .filter { it.packageName != ownPackage }
                .filter { hasInternetPermission(it.packageName) }
                .map {
                    InstalledApp(
                        packageName = it.packageName,
                        label = pm.getApplicationLabel(it).toString(),
                        // "System" here means "not something the user chose to install and can
                        // find in their drawer" — which is the distinction the UI's filter is
                        // actually about, not the FLAG_SYSTEM bit on its own.
                        isSystem = it.isSystemApp() && it.packageName !in launchable,
                    )
                }
                .sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun launchablePackages(): Set<String> = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.toSet()
    }.getOrDefault(emptySet())

    /**
     * An app with no INTERNET permission cannot make a DNS lookup, so listing it would only pad
     * a screen the user has to scroll through to find the one app they care about.
     */
    private fun hasInternetPermission(packageName: String): Boolean = runCatching {
        pm.checkPermission(android.Manifest.permission.INTERNET, packageName) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(true)

    fun icon(packageName: String): Drawable? =
        runCatching { pm.getApplicationIcon(packageName) }.getOrNull()

    /** Display label for one package, or null when it isn't installed. */
    fun label(packageName: String): String? =
        runCatching { pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString() }.getOrNull()

    private fun ApplicationInfo.isSystemApp(): Boolean =
        (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
}

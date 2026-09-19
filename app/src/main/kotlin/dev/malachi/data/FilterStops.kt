package dev.malachi.data

/**
 * The filter found dead at a process start, counted, so the phone that keeps killing it can be
 * told so.
 *
 * **Why a process start is the evidence.** The tunnel lives in this process and nowhere else, and
 * while it is up the platform holds the process for it. So a fresh process with the filter meant
 * to be running is, by construction, a filter that was not running a moment ago: something killed
 * it — a vendor battery manager, a "clean up" in recents, a low-memory kill — and every lookup in
 * between went straight to the network. This was diagnosed from a phone where full-screen ads got
 * through "now and then": nothing on screen could say why, because the query log is memory and
 * died with the process, and the app looked perfect when opened because opening it restarted it.
 *
 * **What is not a stop.** A reboot and a self-update both start a fresh process with the filter
 * on, and both are the platform working as intended, so the first minutes after either are
 * excused. So is the end of a pause, which the pause alarm announces by reviving the process. And
 * a filter without VPN consent was not killed; another VPN has the slot, which the home screen
 * already explains.
 *
 * Only timestamps are kept, never an app or a domain, and only [MAX_KEPT] of them within
 * [WINDOW_MS] — the bound is part of the write, like every file this app keeps.
 */
object FilterStops {

    /** How far back the notice looks. */
    const val WINDOW_MS = 7 * 24 * 60 * 60 * 1000L

    /** How many stops within the window before the phone is worth talking to about it. */
    const val NOTICE_THRESHOLD = 2

    const val MAX_KEPT = 20

    /** `BOOT_COMPLETED` arrives up to a few minutes late; a start inside this is the reboot. */
    const val GRACE_AFTER_BOOT_MS = 10 * 60 * 1000L

    /** A self-update replaces the process; a start inside this after one is the update. */
    const val GRACE_AFTER_UPDATE_MS = 10 * 60 * 1000L

    /** The pause alarm revives a killed process to end the pause; that start is the alarm. */
    const val GRACE_AFTER_PAUSE_MS = 5 * 60 * 1000L

    /**
     * Whether a process starting now, with [settings] as stored, means the filter had stopped.
     *
     * [sinceBootMs] is time since the device booted, [updatedAtMs] the moment this package was
     * last installed or updated, and [hasConsent] whether Malachi still holds the VPN consent.
     */
    fun isStop(
        settings: MalachiSettings,
        nowMs: Long,
        sinceBootMs: Long,
        updatedAtMs: Long,
        hasConsent: Boolean,
    ): Boolean = settings.isFiltering(nowMs) &&
        hasConsent &&
        sinceBootMs >= GRACE_AFTER_BOOT_MS &&
        nowMs - updatedAtMs >= GRACE_AFTER_UPDATE_MS &&
        !(settings.pausedUntilMs > 0 && nowMs - settings.pausedUntilMs < GRACE_AFTER_PAUSE_MS)

    /** One stop recorded now, with anything past the window or the count bound dropped. */
    fun recorded(settings: MalachiSettings, nowMs: Long): MalachiSettings = settings.copy(
        filterStopsAtMs = (settings.filterStopsAtMs + nowMs).filter { nowMs - it < WINDOW_MS }.takeLast(MAX_KEPT),
    )

    /** The stops inside the window, oldest first. */
    fun recent(settings: MalachiSettings, nowMs: Long): List<Long> =
        settings.filterStopsAtMs.filter { nowMs - it < WINDOW_MS }

    /**
     * Whether the notice should be on screen: enough stops to be a pattern rather than an
     * accident, and at least one the user has not already dismissed.
     */
    fun noticeDue(settings: MalachiSettings, nowMs: Long): Boolean {
        val recent = recent(settings, nowMs)
        return recent.size >= NOTICE_THRESHOLD && recent.any { it > settings.filterStopsSeenAtMs }
    }

    fun dismissed(settings: MalachiSettings, nowMs: Long): MalachiSettings =
        settings.copy(filterStopsSeenAtMs = nowMs)

    /**
     * Whose battery manager is doing it, as far as the build says — which decides the words the
     * notice uses, because each vendor hides the setting that fixes it somewhere different.
     */
    enum class Vendor { XIAOMI, SAMSUNG, HUAWEI, OPPO, VIVO, OTHER }

    fun vendor(manufacturer: String): Vendor = when (manufacturer.trim().lowercase()) {
        "xiaomi", "redmi", "poco" -> Vendor.XIAOMI
        "samsung" -> Vendor.SAMSUNG
        "huawei", "honor" -> Vendor.HUAWEI
        "oppo", "realme", "oneplus" -> Vendor.OPPO
        "vivo", "iqoo" -> Vendor.VIVO
        else -> Vendor.OTHER
    }
}

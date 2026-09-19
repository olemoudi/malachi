package dev.malachi.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The filter found dead at a process start, and the notice that tells somebody their phone keeps
 * doing it. Both directions are pinned: every excused start has to stay excused, or the notice
 * cries wolf after each reboot and update and is dismissed for good; and a real kill has to count,
 * or the phone that was reported — ads getting through "now and then" on a Xiaomi — is never told.
 */
class FilterStopsTest {

    private val day = 24 * 60 * 60 * 1000L
    private val now = 100 * day
    private val filtering = MalachiSettings(filteringEnabled = true)

    private fun isStop(
        settings: MalachiSettings = filtering,
        sinceBootMs: Long = day,
        updatedAtMs: Long = now - day,
        hasConsent: Boolean = true,
    ) = FilterStops.isStop(settings, now, sinceBootMs, updatedAtMs, hasConsent)

    @Test
    fun `a process starting with the filter wanted is a filter that had stopped`() {
        assertTrue(isStop())
    }

    @Test
    fun `nothing is a stop while nobody wants the filter`() {
        assertFalse(isStop(settings = MalachiSettings(filteringEnabled = false)))
        assertFalse(isStop(settings = filtering.copy(pausedUntilMs = now + 60_000)))
    }

    @Test
    fun `a reboot and a self-update are the platform working, not a kill`() {
        assertFalse(isStop(sinceBootMs = 3 * 60_000))
        assertFalse(isStop(updatedAtMs = now - 60_000))
        assertTrue(isStop(sinceBootMs = FilterStops.GRACE_AFTER_BOOT_MS))
    }

    @Test
    fun `the pause alarm reviving the process is the end of the pause`() {
        assertFalse(isStop(settings = filtering.copy(pausedUntilMs = now - 30_000)))
        // Long after a pause has ended, a start is a start again.
        assertTrue(isStop(settings = filtering.copy(pausedUntilMs = now - day)))
    }

    @Test
    fun `without VPN consent another VPN has the slot, which is not a kill`() {
        assertFalse(isStop(hasConsent = false))
    }

    @Test
    fun `the record is bounded by age and by count`() {
        var settings = filtering.copy(filterStopsAtMs = listOf(now - 8 * day, now - 2 * day))
        settings = FilterStops.recorded(settings, now)
        assertEquals(listOf(now - 2 * day, now), settings.filterStopsAtMs)

        repeat(50) { settings = FilterStops.recorded(settings, now + it) }
        assertEquals(FilterStops.MAX_KEPT, settings.filterStopsAtMs.size)
        assertEquals(now + 49, settings.filterStopsAtMs.last())
    }

    @Test
    fun `one stop is an accident and two in a week are a pattern`() {
        val once = FilterStops.recorded(filtering, now)
        assertFalse(FilterStops.noticeDue(once, now))
        val twice = FilterStops.recorded(once, now + 1)
        assertTrue(FilterStops.noticeDue(twice, now + 1))
        // A week later both have aged out of the question.
        assertFalse(FilterStops.noticeDue(twice, now + 8 * day))
    }

    @Test
    fun `dismissing lasts until the phone does it again`() {
        val twice = FilterStops.recorded(FilterStops.recorded(filtering, now), now + 1)
        val dismissed = FilterStops.dismissed(twice, now + 2)
        assertFalse(FilterStops.noticeDue(dismissed, now + 2))
        val again = FilterStops.recorded(dismissed, now + 3)
        assertTrue(FilterStops.noticeDue(again, now + 3))
    }

    @Test
    fun `the vendor is read from the manufacturer whatever its spelling`() {
        assertEquals(FilterStops.Vendor.XIAOMI, FilterStops.vendor("Xiaomi"))
        assertEquals(FilterStops.Vendor.XIAOMI, FilterStops.vendor(" POCO "))
        assertEquals(FilterStops.Vendor.SAMSUNG, FilterStops.vendor("samsung"))
        assertEquals(FilterStops.Vendor.HUAWEI, FilterStops.vendor("HONOR"))
        assertEquals(FilterStops.Vendor.OPPO, FilterStops.vendor("OnePlus"))
        assertEquals(FilterStops.Vendor.VIVO, FilterStops.vendor("vivo"))
        assertEquals(FilterStops.Vendor.OTHER, FilterStops.vendor("Google"))
    }

    @Test
    fun `the record survives a settings round trip and never reaches a backup`() {
        val settings = FilterStops.recorded(filtering, now)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val back = json.decodeFromString(MalachiSettings.serializer(), json.encodeToString(MalachiSettings.serializer(), settings))
        assertEquals(settings.filterStopsAtMs, back.filterStopsAtMs)
        val restored = Backup.of(settings, "test", now).restoredInto(MalachiSettings())
        assertTrue(restored.filterStopsAtMs.isEmpty())
    }
}

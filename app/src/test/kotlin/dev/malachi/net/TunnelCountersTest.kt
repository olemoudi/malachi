package dev.malachi.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The one line of the diagnostics header that says what this process has cost, pinned because
 * it is read at a distance, from a copy somebody pasted, and a format that drifts is a number
 * that gets misread.
 */
class TunnelCountersTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun theLineNamesCpuAgainstUptimeAndEveryCounter() {
        val counters = TunnelCounters().apply {
            tunnels = 1
            events = 143
            capabilityEvents = 120
            adoptions = 6
            packets = 1_204
            forwarded = 880
        }
        assertEquals(
            "process: cpu=12.3s (0.01% of 1d 4h 12m up; service up 1d 4h 11m); " +
                "tunnels=1 events=143 (capabilities 120) adoptions=6 packets=1204 forwarded=880 threads=9",
            counters.describe(
                cpuMs = 12_340,
                processUpMs = day + 4 * hour + 12 * minute,
                serviceUpMs = day + 4 * hour + 11 * minute,
                threads = 9,
            ),
        )
    }

    @Test
    fun aProcessBurningACoreIsSaidInPercent() {
        // 40 minutes of CPU over two days is the number that separates "Malachi is idle" from
        // "Malachi is the drain", and it should read as a percentage, not as a count of seconds
        // somebody has to divide.
        val line = TunnelCounters().describe(cpuMs = 40 * minute, processUpMs = 2 * day, serviceUpMs = 2 * day, threads = 8)
        assertEquals("process: cpu=2400.0s (1.39% of 2d 0h 0m up; service up 2d 0h 0m); ", line.substringBefore("tunnels"))
    }

    @Test
    fun zeroUptimeCannotDivide() {
        val line = TunnelCounters().describe(cpuMs = 5, processUpMs = 0, serviceUpMs = 0, threads = 1)
        assertEquals("process: cpu=0.0s (?% of 0s up; service up 0s); ", line.substringBefore("tunnels"))
    }

    @Test
    fun durationsReadAsPeopleSayThem() {
        assertEquals("0s", TunnelCounters.duration(0))
        assertEquals("59s", TunnelCounters.duration(59_999))
        assertEquals("1m 1s", TunnelCounters.duration(61_000))
        assertEquals("1h 0m", TunnelCounters.duration(hour))
        assertEquals("1d 1h 0m", TunnelCounters.duration(day + hour))
    }
}

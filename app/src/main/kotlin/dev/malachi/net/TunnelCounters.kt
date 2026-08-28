package dev.malachi.net

import java.util.Locale

/**
 * What this process has done since it started, for the diagnostics header.
 *
 * The question a battery report asks is not "what did the filter do" but "what did it cost",
 * and nothing in the log answered it: every line is an event, and a process that is quietly
 * expensive produces no events at all. The CPU time of the process against its uptime is the
 * one number that settles it, and the counters beside it are what to look at when that number
 * is wrong — a tunnel rebuilt fifty times, ten thousand capability callbacks, a read loop that
 * saw a million packets from two apps that are used "now and then".
 *
 * Plain longs, incremented from the thread that owns each one (the read loop for packets and
 * forwards, the connectivity callbacks for events, whoever holds the tunnel lock for tunnels).
 * Adoptions can also be counted from a forwarder that found every resolver silent, and losing
 * one increment to that race costs nothing worth a lock on the hot path.
 */
class TunnelCounters {
    /** Times `establish()` succeeded — every one a route table rebuilt and a read loop started. */
    @Volatile var tunnels = 0L

    /** Every network callback delivered, of any kind, on either registration. */
    @Volatile var events = 0L

    /** The subset that were capability changes, which is the idle path nobody profiles. */
    @Volatile var capabilityEvents = 0L

    /** Adoptions that actually changed something: closed sockets, re-read resolvers. */
    @Volatile var adoptions = 0L

    /** Packets read off the tun. */
    @Volatile var packets = 0L

    /** Lookups handed to the forwarders, which is the part that leaves the read loop. */
    @Volatile var forwarded = 0L

    fun describe(cpuMs: Long, processUpMs: Long, serviceUpMs: Long, threads: Int): String {
        val share = if (processUpMs > 0) String.format(Locale.ROOT, "%.2f", cpuMs * 100.0 / processUpMs) else "?"
        val cpu = String.format(Locale.ROOT, "%.1f", cpuMs / 1000.0)
        return "process: cpu=${cpu}s ($share% of ${duration(processUpMs)} up; service up ${duration(serviceUpMs)}); " +
            "tunnels=$tunnels events=$events (capabilities $capabilityEvents) adoptions=$adoptions " +
            "packets=$packets forwarded=$forwarded threads=$threads"
    }

    companion object {
        fun duration(ms: Long): String {
            val seconds = ms / 1000
            val days = seconds / 86_400
            val hours = seconds % 86_400 / 3_600
            val minutes = seconds % 3_600 / 60
            return when {
                days > 0 -> "${days}d ${hours}h ${minutes}m"
                hours > 0 -> "${hours}h ${minutes}m"
                minutes > 0 -> "${minutes}m ${seconds % 60}s"
                else -> "${seconds}s"
            }
        }
    }
}

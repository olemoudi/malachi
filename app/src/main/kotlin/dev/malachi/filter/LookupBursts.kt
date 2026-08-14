package dev.malachi.filter

import dev.malachi.filter.dns.DnsMessage

/**
 * Tells one lookup from the burst of DNS queries it actually arrives as.
 *
 * Resolving a hostname is never one query. Android's resolver asks for `A` and `AAAA` in
 * parallel for every `getaddrinfo`, and a browser adds `HTTPS` on top, so a domain a person
 * looked up **once** reached the tunnel two or three times and was counted that many — every
 * row in the log said "seen 2 times" the first time it was seen, and the statistics were
 * inflated by the same factor. Neither number was wrong about queries; both were wrong about
 * the question anybody was asking them.
 *
 * The rule is: within [windowMs] of each other, queries for the same name from the same app
 * are one lookup *until a record type repeats*. A repeat is the signal that cannot be a
 * companion — the resolver does not ask for `A` twice in one breath — so a client retrying
 * still counts, which is exactly what the retry display depends on.
 *
 * Kept deliberately small and allocation-free: this sits on the hot path, once per query, for
 * the life of the process.
 *
 * - Slots are keyed by a **hash** of app and domain rather than the string, so the path where
 *   the query log is switched off does not have to build a key it would never use. A collision
 *   costs one miscounted lookup and nothing else, and two queries of the same type collide
 *   into the answer they would have got anyway.
 * - The ring is a fixed [SLOTS] entries, round-robin. More bursts in flight than that and the
 *   oldest is forgotten, which counts a companion as its own lookup. That needs sixteen
 *   distinct names being resolved inside two seconds to happen at all.
 */
internal class LookupBursts(private val windowMs: Long = WINDOW_MS) {

    private val keys = IntArray(SLOTS)
    private val masks = IntArray(SLOTS)
    private val stamps = LongArray(SLOTS)
    private var next = 0

    /**
     * True when this query begins a lookup rather than continuing one already counted.
     *
     * Also records it, so this is called exactly once per query.
     */
    fun beginsLookup(key: Int, type: Int, nowMs: Long): Boolean {
        val bit = bitFor(type)
        val found = indexOf(key)
        if (found >= 0 && nowMs - stamps[found] <= windowMs && masks[found] and bit == 0) {
            masks[found] = masks[found] or bit
            stamps[found] = nowMs
            return false
        }
        // A new lookup: either nothing was in flight for this name, the burst has aged out, or
        // this record type has already been asked for and so cannot be a companion. Reuse the
        // name's own slot when it has one, so a chatty domain never takes two.
        val slot = if (found >= 0) found else claim()
        keys[slot] = key
        masks[slot] = bit
        stamps[slot] = nowMs
        return true
    }

    fun clear() {
        keys.fill(0)
        masks.fill(0)
        stamps.fill(0)
        next = 0
    }

    private fun indexOf(key: Int): Int {
        for (i in 0 until SLOTS) if (keys[i] == key) return i
        return -1
    }

    private fun claim(): Int {
        val slot = next
        next = (next + 1) % SLOTS
        return slot
    }

    companion object {
        /**
         * How far apart two queries may be and still be one lookup.
         *
         * Companions are sent together and arrive milliseconds apart; the margin is for the
         * libraries that wait for the `A` answer before asking for `AAAA`. It does not need to
         * cover a retry, because a retry repeats a record type and is counted by that.
         */
        const val WINDOW_MS = 2_000L

        const val SLOTS = 16

        /**
         * One bit per record type worth telling apart. Everything else shares a bit: the point
         * is only to notice a *repeat*, and two different oddities in one burst are rare enough
         * that treating them as one is cheaper than being exact about them.
         */
        private fun bitFor(type: Int): Int = when (type) {
            DnsMessage.TYPE_A -> 1
            DnsMessage.TYPE_AAAA -> 2
            DnsMessage.TYPE_HTTPS -> 4
            DnsMessage.TYPE_SVCB -> 8
            else -> 16
        }
    }
}

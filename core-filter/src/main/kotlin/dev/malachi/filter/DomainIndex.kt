package dev.malachi.filter

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * A blocklist, compiled.
 *
 * The public lists this app subscribes to run from tens of thousands to a quarter of a million
 * domains each, and several are enabled at once. Holding them as `Set<String>` costs roughly
 * 60-100 bytes per entry — hundreds of megabytes across a full subscription, on a phone, in a
 * process that must stay alive to answer every DNS query. So a list is not kept as text: each
 * domain is reduced to a 64-bit hash, and the hashes are held sorted in one `LongArray`. That is
 * 8 bytes per domain, contiguous, with no per-entry object at all — a 250k-domain list occupies
 * 2 MB, and a lookup is a binary search over primitives.
 *
 * The cost of that choice is that a compiled list can't be read back out (a hash doesn't
 * remember its domain) and that two domains could in principle collide. Neither matters here:
 * nothing displays the contents of a public list, and at a quarter of a million entries the
 * chance of any 64-bit collision at all is about one in a billion — while the *consequence* of
 * one would be a single unrelated domain blocked, which the user can override with an allow rule.
 * The rules the user writes are kept as text elsewhere ([FilterEngine]), because those do have to
 * be shown, edited and explained.
 *
 * Matching is suffix-based, the way DNS blocklists are meant to be read: an entry for
 * `ads.example.com` blocks `ads.example.com` and anything under it, and never `example.com`.
 */
class DomainIndex internal constructor(internal val hashes: LongArray) {

    val size: Int get() = hashes.size

    /** True when [host] or any of its parent domains is in this list. */
    fun matches(host: String): Boolean = matchDepth(host) >= 0

    /**
     * How many labels had to be dropped from [host] before a match, or -1 for no match. Exposed
     * because "which rule won" is decided by specificity: a `sub.example.com` allow rule must
     * beat an `example.com` block rule, and the depth is what makes those comparable.
     */
    fun matchDepth(host: String): Int {
        val h = normalizeHost(host) ?: return -1
        var start = 0
        var depth = 0
        while (true) {
            if (contains(hash(h, start, h.length))) return depth
            val dot = h.indexOf('.', start)
            if (dot < 0) return -1
            start = dot + 1
            depth++
        }
    }

    /** True when exactly this domain is an entry (no suffix walking). */
    fun containsExact(domain: String): Boolean {
        val d = normalizeHost(domain) ?: return false
        return contains(hash(d, 0, d.length))
    }

    private fun contains(key: Long): Boolean {
        var low = 0
        var high = hashes.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = hashes[mid]
            when {
                value < key -> low = mid + 1
                value > key -> high = mid - 1
                else -> return true
            }
        }
        return false
    }

    /**
     * Writes the compiled form. Paired with [read]; the version guards against format drift.
     *
     * Flushed but deliberately not closed: the stream belongs to the caller, who needs it open
     * afterwards to force the bytes to the disk before renaming the file into place.
     */
    fun write(stream: OutputStream) {
        val out = DataOutputStream(stream.buffered())
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeInt(hashes.size)
        for (h in hashes) out.writeLong(h)
        out.flush()
    }

    /**
     * Accumulates domains and compiles them. Sorting and de-duplicating once at the end is
     * what makes ingesting a 250k-line list a linear scan plus one sort, rather than a quarter
     * of a million insertions into a growing set.
     */
    class Builder {
        private var buffer = LongArray(1024)
        private var count = 0

        /** Returns true when [domain] was accepted (i.e. it looked like a domain at all). */
        fun add(domain: String): Boolean {
            val d = normalizeHost(domain) ?: return false
            if (buffer.size == count) buffer = buffer.copyOf(count * 2)
            buffer[count++] = hash(d, 0, d.length)
            return true
        }

        fun build(): DomainIndex {
            val sorted = buffer.copyOf(count)
            sorted.sort()
            var unique = 0
            for (i in sorted.indices) {
                if (i == 0 || sorted[i] != sorted[i - 1]) sorted[unique++] = sorted[i]
            }
            return DomainIndex(sorted.copyOf(unique))
        }
    }

    companion object {
        private const val MAGIC = 0x4D4C4348 // "MLCH"
        private const val VERSION = 1

        val EMPTY = DomainIndex(LongArray(0))

        fun of(domains: Iterable<String>): DomainIndex {
            val builder = Builder()
            domains.forEach { builder.add(it) }
            return builder.build()
        }

        /**
         * The largest index we will read back. Four million domains is an order of magnitude
         * past the biggest list anyone publishes, and the bound is the point: the entry count
         * comes off the disk, and a file damaged in exactly those four bytes would otherwise
         * ask for an array of whatever number it happens to spell — gigabytes, on a phone,
         * before anything has had a chance to notice the file is nonsense.
         */
        private const val MAX_ENTRIES = 4_000_000

        /** Reads a compiled index, or throws if the bytes aren't one of ours. */
        fun read(stream: InputStream): DomainIndex {
            DataInputStream(stream.buffered()).use { input ->
                require(input.readInt() == MAGIC) { "not a domain index" }
                require(input.readInt() == VERSION) { "unsupported index version" }
                val count = input.readInt()
                require(count in 0..MAX_ENTRIES) { "corrupt index length: $count" }
                val hashes = LongArray(count)
                for (i in 0 until count) hashes[i] = input.readLong()
                return DomainIndex(hashes)
            }
        }

        /**
         * Lowercased, trailing dot removed, `*.` prefix removed, and rejected outright unless it
         * looks like a domain with at least two labels.
         *
         * The two-label floor is a safety rail, not pedantry: a single malformed line in a
         * downloaded list that reduced to `com` would take the internet down for the user, and no
         * list we subscribe to legitimately blocks a whole top-level domain.
         */
        fun normalizeHost(raw: String): String? {
            var s = raw.trim()
            if (s.startsWith("*.")) s = s.substring(2)
            if (s.endsWith(".")) s = s.dropLast(1)
            if (s.isEmpty() || s.length > 253) return null
            var hasDot = false
            var labelLength = 0
            for (i in s.indices) {
                val c = s[i]
                when {
                    c == '.' -> {
                        if (labelLength == 0) return null // empty label: ".." or a leading dot
                        hasDot = true
                        labelLength = 0
                    }
                    c == '-' || c == '_' || c.isDigit() ||
                        (c in 'a'..'z') || (c in 'A'..'Z') -> labelLength++
                    else -> return null
                }
                if (labelLength > 63) return null
            }
            if (!hasDot || labelLength == 0) return null
            return s.lowercase()
        }

        /** FNV-1a over [from, to). Domains are ASCII by the time they get here. */
        internal fun hash(s: String, from: Int, to: Int): Long {
            var h = -0x340d631b7bdddcdbL // 14695981039346656037
            for (i in from until to) {
                h = h xor (s[i].code.toLong() and 0xFF)
                h *= 0x100000001b3L
            }
            return h
        }
    }
}

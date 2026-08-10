package dev.malachi.filter.dns

/** The question a DNS query asks. [endOffset] is where the question section ends. */
data class DnsQuestion(
    val name: String,
    val type: Int,
    val dnsClass: Int,
    val endOffset: Int,
)

/**
 * How a blocked lookup is answered. There is no universally right choice, so it is the user's:
 *
 * - [NULL_ADDRESS] answers `0.0.0.0` / `::`. The app believes the name resolved and fails on
 *   connect, which most ad SDKs treat as "no ad this time" and drop quietly. Best default.
 * - [NXDOMAIN] says the name does not exist. Cleaner semantically, but some apps read it as a
 *   broken network and retry in a loop.
 * - [REFUSED] says the resolver declined. Some system resolvers respond by trying the next
 *   configured server, which is not what we want, but it makes blocking unambiguous when
 *   debugging.
 */
enum class BlockAnswer { NULL_ADDRESS, NXDOMAIN, REFUSED }

/**
 * Just enough DNS to read a question and forge an answer. Deliberately not a resolver: every
 * query we allow is forwarded verbatim to a real upstream and its reply relayed back untouched,
 * so the only messages this builds are the ones for names we refuse.
 */
object DnsMessage {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_SVCB = 64

    /** HTTPS records carry ECH keys and alternative endpoints; blocking a name must cover them. */
    const val TYPE_HTTPS = 65

    /** Fixed DNS header: id, flags, and the four section counts. */
    const val HEADER_BYTES = 12

    private const val CLASS_IN = 1
    private const val BLOCK_TTL_SECONDS = 60

    private const val RCODE_NO_ERROR = 0
    private const val RCODE_NXDOMAIN = 3
    private const val RCODE_REFUSED = 5

    /**
     * The transaction id of [data], or null when it is too short to be a DNS message at all.
     *
     * Null rather than an exception because both callers are on a path that must never throw:
     * one decides whether a packet is worth forwarding, the other matches a reply to the query
     * it belongs to, and a stray byte from any app on the phone reaches both.
     */
    fun transactionId(data: ByteArray): Int? {
        if (data.size < HEADER_BYTES) return null
        return ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
    }

    /** True when [data] is a standard query (QR=0, OPCODE=0) with at least one question. */
    fun isStandardQuery(data: ByteArray): Boolean {
        if (data.size < HEADER_BYTES) return false
        val flags = data[2].toInt() and 0xFF
        if (flags and 0x80 != 0) return false // QR set: this is a response
        if (flags and 0x78 != 0) return false // OPCODE != QUERY
        return readShort(data, 4) >= 1
    }

    /**
     * Reads the first question. Returns null for anything malformed — a truncated name, an
     * empty name, or a compression pointer, which cannot legally appear in a question section
     * and in practice only shows up in traffic trying to confuse a middlebox.
     */
    fun parseQuestion(data: ByteArray): DnsQuestion? {
        if (!isStandardQuery(data)) return null
        var i = HEADER_BYTES
        val name = StringBuilder()
        while (true) {
            if (i >= data.size) return null
            val len = data[i].toInt() and 0xFF
            if (len == 0) {
                i++
                break
            }
            if (len and 0xC0 != 0) return null
            i++
            if (i + len > data.size) return null
            for (j in 0 until len) name.append((data[i + j].toInt() and 0xFF).toChar())
            name.append('.')
            i += len
        }
        if (i + 4 > data.size) return null
        if (name.isEmpty()) return null // the root, which is never a name we filter
        return DnsQuestion(
            name = name.toString().dropLast(1).lowercase(),
            type = readShort(data, i),
            dnsClass = readShort(data, i + 2),
            endOffset = i + 4,
        )
    }

    /** The response to send for a name we refuse, in the shape the user asked for. */
    fun blockedResponse(query: ByteArray, question: DnsQuestion, answer: BlockAnswer): ByteArray =
        when (answer) {
            BlockAnswer.NXDOMAIN -> emptyResponse(query, question, RCODE_NXDOMAIN)
            BlockAnswer.REFUSED -> emptyResponse(query, question, RCODE_REFUSED)
            BlockAnswer.NULL_ADDRESS -> nullAddressResponse(query, question)
        }

    /**
     * Header + question, no records. Also what [BlockAnswer.NULL_ADDRESS] falls back to for
     * record types that have no address to null out (HTTPS, SVCB, TXT, …): an empty NOERROR is
     * a NODATA answer, the correct way to say "this name has nothing of that kind", and it stops
     * an app from finding the ad server via an HTTPS record after its A lookup was blocked.
     */
    private fun emptyResponse(query: ByteArray, question: DnsQuestion, rcode: Int): ByteArray {
        val out = query.copyOf(question.endOffset)
        writeHeader(out, query, rcode, answerCount = 0)
        return out
    }

    private fun nullAddressResponse(query: ByteArray, question: DnsQuestion): ByteArray {
        val rdata = when (question.type) {
            TYPE_A -> ByteArray(4)
            TYPE_AAAA -> ByteArray(16)
            else -> return emptyResponse(query, question, RCODE_NO_ERROR)
        }
        val out = ByteArray(question.endOffset + 12 + rdata.size)
        query.copyInto(out, 0, 0, question.endOffset)
        writeHeader(out, query, RCODE_NO_ERROR, answerCount = 1)

        var i = question.endOffset
        // A pointer back to the question's name rather than repeating it — the compression every
        // resolver expects, and the reason the answer fits in a handful of bytes.
        out[i++] = 0xC0.toByte()
        out[i++] = HEADER_BYTES.toByte()
        i = writeShort(out, i, question.type)
        i = writeShort(out, i, CLASS_IN)
        i = writeShort(out, i, BLOCK_TTL_SECONDS ushr 16)
        i = writeShort(out, i, BLOCK_TTL_SECONDS and 0xFFFF)
        i = writeShort(out, i, rdata.size)
        rdata.copyInto(out, i)
        return out
    }

    /**
     * Turns [out]'s copied header into a response: same transaction id and opcode, QR and RA set,
     * the query's RD echoed back, and every section count but the answers zeroed — including the
     * additional section, which drops the query's EDNS OPT record. A resolver reads a reply
     * without OPT as a plain non-EDNS answer, which is exactly what this is.
     */
    private fun writeHeader(out: ByteArray, query: ByteArray, rcode: Int, answerCount: Int) {
        out[2] = ((query[2].toInt() and 0x79) or 0x80).toByte()
        out[3] = (0x80 or rcode).toByte()
        writeShort(out, 4, 1) // QDCOUNT: exactly the one question we answered
        writeShort(out, 6, answerCount)
        writeShort(out, 8, 0)
        writeShort(out, 10, 0)
    }

    private fun readShort(data: ByteArray, at: Int): Int =
        ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)

    private fun writeShort(data: ByteArray, at: Int, value: Int): Int {
        data[at] = (value ushr 8).toByte()
        data[at + 1] = value.toByte()
        return at + 2
    }
}

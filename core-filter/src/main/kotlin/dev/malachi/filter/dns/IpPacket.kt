package dev.malachi.filter.dns

/**
 * A UDP datagram lifted out of an IP packet read from the tun. Holds no copies — [payloadOffset]
 * and [payloadLength] point back into the original buffer, because this is built once per DNS
 * query on a phone and copying every packet twice to be tidy would be paid for in battery.
 */
class UdpDatagram(
    val ipVersion: Int,
    val sourceAddress: ByteArray,
    val destinationAddress: ByteArray,
    val sourcePort: Int,
    val destinationPort: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
) {
    fun payload(packet: ByteArray): ByteArray =
        packet.copyOfRange(payloadOffset, payloadOffset + payloadLength)
}

/**
 * An ICMP echo request — a ping — lifted out of a packet read from the tun.
 *
 * The tun routes a handful of addresses and nothing else, so a ping to one of them lands here
 * with nowhere to go. Dropping it is what "the Wi-Fi doesn't ping" looks like from the outside:
 * ping is the one test everybody runs, and the addresses the bypass guard routes — the DNS
 * servers a network handed out, the public ones apps hardcode — are exactly the ones people ping.
 * [message] is the ICMP message as it should leave through a ping socket, header included; the
 * kernel rewrites the identifier on the way out, which is why [identifier] is kept separately
 * so the reply can be handed back under the one the app chose.
 */
class IcmpEcho(
    val ipVersion: Int,
    val sourceAddress: ByteArray,
    val destinationAddress: ByteArray,
    val identifier: Int,
    val sequence: Int,
    val message: ByteArray,
)

/**
 * A TCP segment addressed to something this tunnel routes, reduced to what refusing it needs.
 *
 * Refusing is the point. Every address routed into this tun is routed for DNS, and this tunnel
 * carries no TCP at all — so a connection to one of them used to be swallowed, and a swallowed
 * SYN is not a refusal, it is a wait: the client retries for a minute or more before giving up.
 * That is how the bypass guard turned "this app cannot reach 8.8.8.8" into "this app hangs", and
 * how a DNS answer too big for UDP — the client's next move is the same lookup over TCP — turned
 * into a lookup that never finished. A reset says no in one packet.
 */
class TcpSegment(
    val ipVersion: Int,
    val sourceAddress: ByteArray,
    val destinationAddress: ByteArray,
    val sourcePort: Int,
    val destinationPort: Int,
    val sequence: Long,
    val acknowledgement: Long,
    val flags: Int,
    val dataLength: Int,
)

/**
 * IPv4 and IPv6 packet reading and writing, for UDP and for ICMP echo.
 *
 * IPv6 is not optional politeness here: a phone on a mobile network is routinely IPv6-only, and
 * a filter that understood v4 alone would silently pass every lookup on those networks while
 * still reporting itself as running.
 */
object IpPacket {

    const val PROTOCOL_UDP = 17
    const val PROTOCOL_TCP = 6
    const val PROTOCOL_ICMP = 1
    const val PROTOCOL_ICMPV6 = 58

    private const val IPV4_HEADER_BYTES = 20
    private const val IPV6_HEADER_BYTES = 40
    private const val UDP_HEADER_BYTES = 8
    private const val TCP_HEADER_BYTES = 20

    private const val TCP_FIN = 0x01
    private const val TCP_SYN = 0x02
    private const val TCP_RST = 0x04
    private const val TCP_ACK = 0x10
    private const val ICMP_HEADER_BYTES = 8
    private const val DEFAULT_HOP_LIMIT = 64

    private const val ICMP_ECHO_REQUEST = 8
    private const val ICMP_ECHO_REPLY = 0
    private const val ICMPV6_ECHO_REQUEST = 128
    private const val ICMPV6_ECHO_REPLY = 129

    /**
     * The protocol carried by [packet] — IPv4's protocol field or IPv6's next header — or null
     * when it isn't IP at all. For IPv6 this is the *first* header, which for anything carrying
     * extension headers is the extension rather than the transport.
     */
    fun protocol(packet: ByteArray, length: Int): Int? {
        if (length < 1) return null
        return when ((packet[0].toInt() and 0xF0) shr 4) {
            4 -> if (length >= 10) packet[9].toInt() and 0xFF else null
            6 -> if (length >= 7) packet[6].toInt() and 0xFF else null
            else -> null
        }
    }

    /**
     * Protocols that arrive on a tun as a matter of course rather than as a symptom.
     *
     * Neighbour discovery and multicast listener traffic are how IPv6 works; a tunnel that
     * exists to see DNS can't carry any of it and never could. Naming them is what keeps the
     * debug log from spending its whole byte budget on the one message that never means
     * anything, once a minute, for as long as the filter is on.
     */
    val ROUTINE_ON_A_TUN = setOf(
        0, // IPv6 hop-by-hop, which in practice is MLD
        1, // ICMP
        2, // IGMP
        58, // ICMPv6: router and neighbour solicitations, and the rest of discovery
    )

    /** Reads a UDP datagram out of [packet], or null when it isn't one we can act on. */
    fun parseUdp(packet: ByteArray, length: Int): UdpDatagram? {
        if (length < 1) return null
        return when ((packet[0].toInt() and 0xF0) shr 4) {
            4 -> parseIpv4(packet, length)
            6 -> parseIpv6(packet, length)
            else -> null
        }
    }

    private fun parseIpv4(packet: ByteArray, length: Int): UdpDatagram? {
        if (length < IPV4_HEADER_BYTES) return null
        val headerBytes = (packet[0].toInt() and 0x0F) * 4
        if (headerBytes < IPV4_HEADER_BYTES || length < headerBytes + UDP_HEADER_BYTES) return null
        if (packet[9].toInt() and 0xFF != PROTOCOL_UDP) return null
        // A fragment carries only part of the datagram, so its payload isn't a whole DNS message.
        // Reassembly is not worth building: DNS queries are one packet, always.
        val fragmentOffset = readShort(packet, 6) and 0x1FFF
        if (fragmentOffset != 0) return null
        return udpAt(
            packet, length, headerBytes, ipVersion = 4,
            source = packet.copyOfRange(12, 16),
            destination = packet.copyOfRange(16, 20),
        )
    }

    private fun parseIpv6(packet: ByteArray, length: Int): UdpDatagram? {
        if (length < IPV6_HEADER_BYTES + UDP_HEADER_BYTES) return null
        // Extension headers would have to be walked to find the payload. Nothing generates them
        // for a DNS query, and mishandling one is worse than declining it (which falls through
        // to "forward untouched" at the call site).
        if (packet[6].toInt() and 0xFF != PROTOCOL_UDP) return null
        return udpAt(
            packet, length, IPV6_HEADER_BYTES, ipVersion = 6,
            source = packet.copyOfRange(8, 24),
            destination = packet.copyOfRange(24, 40),
        )
    }

    private fun udpAt(
        packet: ByteArray,
        length: Int,
        headerBytes: Int,
        ipVersion: Int,
        source: ByteArray,
        destination: ByteArray,
    ): UdpDatagram? {
        val udpLength = readShort(packet, headerBytes + 4)
        if (udpLength < UDP_HEADER_BYTES) return null
        val payloadLength = minOf(udpLength - UDP_HEADER_BYTES, length - headerBytes - UDP_HEADER_BYTES)
        if (payloadLength < 0) return null
        return UdpDatagram(
            ipVersion = ipVersion,
            sourceAddress = source,
            destinationAddress = destination,
            sourcePort = readShort(packet, headerBytes),
            destinationPort = readShort(packet, headerBytes + 2),
            payloadOffset = headerBytes + UDP_HEADER_BYTES,
            payloadLength = payloadLength,
        )
    }

    /**
     * Builds the packet that answers [request]: same addresses and ports, reversed, carrying
     * [payload]. This is how a forged DNS answer gets back to the app that asked — it has to
     * look as though it came from the resolver the app addressed.
     */
    fun buildUdpResponse(request: UdpDatagram, payload: ByteArray): ByteArray =
        if (request.ipVersion == 4) {
            buildIpv4(request.destinationAddress, request.sourceAddress, request.destinationPort, request.sourcePort, payload)
        } else {
            buildIpv6(request.destinationAddress, request.sourceAddress, request.destinationPort, request.sourcePort, payload)
        }

    /** Reads a TCP segment out of [packet], or null when it isn't one we can refuse. */
    fun parseTcp(packet: ByteArray, length: Int): TcpSegment? {
        if (length < 1) return null
        return when ((packet[0].toInt() and 0xF0) shr 4) {
            4 -> parseTcpV4(packet, length)
            6 -> parseTcpV6(packet, length)
            else -> null
        }
    }

    private fun parseTcpV4(packet: ByteArray, length: Int): TcpSegment? {
        if (length < IPV4_HEADER_BYTES) return null
        val headerBytes = (packet[0].toInt() and 0x0F) * 4
        if (headerBytes < IPV4_HEADER_BYTES || length < headerBytes + TCP_HEADER_BYTES) return null
        if (packet[9].toInt() and 0xFF != PROTOCOL_TCP) return null
        // A fragment carries part of a segment, so its header may not even be here.
        if (readShort(packet, 6) and 0x1FFF != 0) return null
        val total = minOf(length, readShort(packet, 2))
        return tcpAt(
            packet, total, headerBytes, ipVersion = 4,
            source = packet.copyOfRange(12, 16),
            destination = packet.copyOfRange(16, 20),
        )
    }

    private fun parseTcpV6(packet: ByteArray, length: Int): TcpSegment? {
        if (length < IPV6_HEADER_BYTES + TCP_HEADER_BYTES) return null
        // Extension headers are not walked, for the same reason they are not walked for UDP:
        // nothing generates them here, and declining is safe where guessing is not.
        if (packet[6].toInt() and 0xFF != PROTOCOL_TCP) return null
        val total = minOf(length, IPV6_HEADER_BYTES + readShort(packet, 4))
        return tcpAt(
            packet, total, IPV6_HEADER_BYTES, ipVersion = 6,
            source = packet.copyOfRange(8, 24),
            destination = packet.copyOfRange(24, 40),
        )
    }

    private fun tcpAt(
        packet: ByteArray,
        length: Int,
        headerBytes: Int,
        ipVersion: Int,
        source: ByteArray,
        destination: ByteArray,
    ): TcpSegment? {
        val dataOffset = ((packet[headerBytes + 12].toInt() and 0xF0) shr 4) * 4
        if (dataOffset < TCP_HEADER_BYTES || length < headerBytes + dataOffset) return null
        return TcpSegment(
            ipVersion = ipVersion,
            sourceAddress = source,
            destinationAddress = destination,
            sourcePort = readShort(packet, headerBytes),
            destinationPort = readShort(packet, headerBytes + 2),
            sequence = readInt(packet, headerBytes + 4),
            acknowledgement = readInt(packet, headerBytes + 8),
            flags = packet[headerBytes + 13].toInt() and 0xFF,
            dataLength = length - headerBytes - dataOffset,
        )
    }

    /**
     * The reset that refuses [segment], addressed back the way it came — or null when there is
     * nothing to refuse.
     *
     * Follows RFC 793 §3.4 exactly, because a client that reads the sequence numbers and finds
     * them wrong ignores the reset and goes back to waiting, which is the outcome this exists to
     * avoid: an acknowledged segment is answered with its own acknowledgement number and no ACK
     * flag, anything else with sequence zero and an acknowledgement covering what arrived — a SYN
     * and a FIN each counting as one byte. A reset is never answered with a reset.
     */
    fun buildTcpReset(segment: TcpSegment): ByteArray? {
        if (segment.flags and TCP_RST != 0) return null
        val acknowledged = segment.flags and TCP_ACK != 0
        val sequence = if (acknowledged) segment.acknowledgement else 0L
        val acknowledgement = if (acknowledged) {
            0L
        } else {
            val counted = segment.dataLength +
                (if (segment.flags and TCP_SYN != 0) 1 else 0) +
                (if (segment.flags and TCP_FIN != 0) 1 else 0)
            (segment.sequence + counted) and 0xFFFFFFFFL
        }
        val flags = if (acknowledged) TCP_RST else TCP_RST or TCP_ACK
        val ipHeaderBytes = if (segment.ipVersion == 4) IPV4_HEADER_BYTES else IPV6_HEADER_BYTES
        val out = ByteArray(ipHeaderBytes + TCP_HEADER_BYTES)
        // Source and destination swapped: this has to look like the host that was addressed.
        val source = segment.destinationAddress
        val destination = segment.sourceAddress
        if (segment.ipVersion == 4) {
            writeIpv4Header(out, source, destination, PROTOCOL_TCP)
        } else {
            writeIpv6Header(out, source, destination, PROTOCOL_TCP, TCP_HEADER_BYTES)
        }
        writeShort(out, ipHeaderBytes, segment.destinationPort)
        writeShort(out, ipHeaderBytes + 2, segment.sourcePort)
        writeInt(out, ipHeaderBytes + 4, sequence)
        writeInt(out, ipHeaderBytes + 8, acknowledgement)
        out[ipHeaderBytes + 12] = 0x50 // five words of header, no options
        out[ipHeaderBytes + 13] = flags.toByte()
        // Window zero: there is nothing to receive, and this connection is over.
        writeShort(out, ipHeaderBytes + 16, tcpChecksum(out, source, destination, ipHeaderBytes))
        return out
    }

    /** TCP checksum over the IP pseudo-header plus the header itself; no payload to cover. */
    private fun tcpChecksum(packet: ByteArray, source: ByteArray, destination: ByteArray, tcpStart: Int): Int {
        val total = sum(source, 0, source.size) +
            sum(destination, 0, destination.size) +
            PROTOCOL_TCP.toLong() +
            TCP_HEADER_BYTES.toLong() +
            sum(packet, tcpStart, TCP_HEADER_BYTES)
        return onesComplement(total)
    }

    /**
     * Reads an ICMP or ICMPv6 echo request out of [packet], or null when it is anything else.
     *
     * Strict on purpose: only an unfragmented echo request with code 0 is a ping we can carry.
     * Neighbour discovery, router solicitations and the rest of ICMPv6 arrive on every tun and
     * are declined here exactly as before, so the call site's "routine on a tun" silence for
     * them is unchanged.
     */
    fun parseEcho(packet: ByteArray, length: Int): IcmpEcho? {
        if (length < 1) return null
        return when ((packet[0].toInt() and 0xF0) shr 4) {
            4 -> parseEchoV4(packet, length)
            6 -> parseEchoV6(packet, length)
            else -> null
        }
    }

    private fun parseEchoV4(packet: ByteArray, length: Int): IcmpEcho? {
        if (length < IPV4_HEADER_BYTES) return null
        val headerBytes = (packet[0].toInt() and 0x0F) * 4
        if (headerBytes < IPV4_HEADER_BYTES || length < headerBytes + ICMP_HEADER_BYTES) return null
        if (packet[9].toInt() and 0xFF != PROTOCOL_ICMP) return null
        // A fragment is part of a ping, and a ping that needs fragmenting is not one we relay.
        if (readShort(packet, 6) and 0x3FFF != 0) return null
        if (packet[headerBytes].toInt() and 0xFF != ICMP_ECHO_REQUEST) return null
        if (packet[headerBytes + 1].toInt() != 0) return null
        val end = minOf(length, readShort(packet, 2))
        if (end < headerBytes + ICMP_HEADER_BYTES) return null
        return IcmpEcho(
            ipVersion = 4,
            sourceAddress = packet.copyOfRange(12, 16),
            destinationAddress = packet.copyOfRange(16, 20),
            identifier = readShort(packet, headerBytes + 4),
            sequence = readShort(packet, headerBytes + 6),
            message = packet.copyOfRange(headerBytes, end),
        )
    }

    private fun parseEchoV6(packet: ByteArray, length: Int): IcmpEcho? {
        if (length < IPV6_HEADER_BYTES + ICMP_HEADER_BYTES) return null
        if (packet[6].toInt() and 0xFF != PROTOCOL_ICMPV6) return null
        if (packet[IPV6_HEADER_BYTES].toInt() and 0xFF != ICMPV6_ECHO_REQUEST) return null
        if (packet[IPV6_HEADER_BYTES + 1].toInt() != 0) return null
        val end = minOf(length, IPV6_HEADER_BYTES + readShort(packet, 4))
        if (end < IPV6_HEADER_BYTES + ICMP_HEADER_BYTES) return null
        return IcmpEcho(
            ipVersion = 6,
            sourceAddress = packet.copyOfRange(8, 24),
            destinationAddress = packet.copyOfRange(24, 40),
            identifier = readShort(packet, IPV6_HEADER_BYTES + 4),
            sequence = readShort(packet, IPV6_HEADER_BYTES + 6),
            message = packet.copyOfRange(IPV6_HEADER_BYTES, end),
        )
    }

    /**
     * The reply to [request] as it should go back down the tun, built from the ICMP message a
     * ping socket handed back — or from the request itself (see [echoReplyMessage]) for an
     * address that is answered locally.
     *
     * Null when [reply] is not an echo reply at all: a ping socket only ever delivers echo
     * replies to its own requests, so anything else is a malformed read and not worth forging a
     * packet for. The identifier is put back to the one the app chose — the kernel replaced it
     * with the socket's own on the way out — and the checksum is recomputed over the result,
     * because the kernel checks it before it will deliver the reply to anybody.
     */
    fun buildEchoReply(request: IcmpEcho, reply: ByteArray, replyLength: Int): ByteArray? {
        if (replyLength < ICMP_HEADER_BYTES || replyLength > reply.size) return null
        val expectedType = if (request.ipVersion == 4) ICMP_ECHO_REPLY else ICMPV6_ECHO_REPLY
        if (reply[0].toInt() and 0xFF != expectedType) return null
        val message = reply.copyOf(replyLength)
        writeShort(message, 4, request.identifier)
        writeShort(message, 2, 0)
        // Addresses reversed: the reply has to look as though it came from the host that was pinged.
        val source = request.destinationAddress
        val destination = request.sourceAddress
        return if (request.ipVersion == 4) {
            writeShort(message, 2, onesComplement(sum(message, 0, message.size)))
            val out = ByteArray(IPV4_HEADER_BYTES + message.size)
            writeIpv4Header(out, source, destination, PROTOCOL_ICMP)
            message.copyInto(out, IPV4_HEADER_BYTES)
            out
        } else {
            writeShort(message, 2, icmpv6Checksum(message, source, destination))
            val out = ByteArray(IPV6_HEADER_BYTES + message.size)
            writeIpv6Header(out, source, destination, PROTOCOL_ICMPV6, message.size)
            message.copyInto(out, IPV6_HEADER_BYTES)
            out
        }
    }

    /**
     * The echo reply a host would send to [request], as a bare ICMP message. For an address
     * that exists only inside the tunnel — the sentinel resolver — there is no host to ask, and
     * answering a ping to it ourselves is both honest and the cheapest diagnostic there is.
     */
    fun echoReplyMessage(request: IcmpEcho): ByteArray = request.message.copyOf().also {
        it[0] = (if (request.ipVersion == 4) ICMP_ECHO_REPLY else ICMPV6_ECHO_REPLY).toByte()
    }

    private fun buildIpv4(
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteArray(IPV4_HEADER_BYTES + UDP_HEADER_BYTES + payload.size)
        writeIpv4Header(out, source, destination, PROTOCOL_UDP)
        writeUdp(out, IPV4_HEADER_BYTES, sourcePort, destinationPort, payload)
        writeShort(out, IPV4_HEADER_BYTES + 6, udpChecksum(out, source, destination, IPV4_HEADER_BYTES, payload.size))
        return out
    }

    private fun buildIpv6(
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteArray(IPV6_HEADER_BYTES + UDP_HEADER_BYTES + payload.size)
        writeIpv6Header(out, source, destination, PROTOCOL_UDP, UDP_HEADER_BYTES + payload.size)
        writeUdp(out, IPV6_HEADER_BYTES, sourcePort, destinationPort, payload)
        // Unlike IPv4, a zero UDP checksum is illegal over IPv6 and the packet would be dropped.
        writeShort(out, IPV6_HEADER_BYTES + 6, udpChecksum(out, source, destination, IPV6_HEADER_BYTES, payload.size))
        return out
    }

    /** A minimal IPv4 header over the whole of [out], checksummed; the payload follows it. */
    private fun writeIpv4Header(out: ByteArray, source: ByteArray, destination: ByteArray, protocol: Int) {
        out[0] = 0x45 // version 4, 5 words of header
        writeShort(out, 2, out.size)
        out[8] = DEFAULT_HOP_LIMIT.toByte()
        out[9] = protocol.toByte()
        source.copyInto(out, 12)
        destination.copyInto(out, 16)
        writeShort(out, 10, onesComplement(sum(out, 0, IPV4_HEADER_BYTES)))
    }

    private fun writeIpv6Header(out: ByteArray, source: ByteArray, destination: ByteArray, nextHeader: Int, payloadLength: Int) {
        out[0] = 0x60 // version 6, no traffic class or flow label
        writeShort(out, 4, payloadLength)
        out[6] = nextHeader.toByte()
        out[7] = DEFAULT_HOP_LIMIT.toByte()
        source.copyInto(out, 8)
        destination.copyInto(out, 24)
    }

    /** ICMPv6 checksum: the pseudo-header is mandatory, unlike ICMP over IPv4 (RFC 4443 §2.3). */
    private fun icmpv6Checksum(message: ByteArray, source: ByteArray, destination: ByteArray): Int {
        val total = sum(source, 0, source.size) +
            sum(destination, 0, destination.size) +
            message.size.toLong() +
            PROTOCOL_ICMPV6.toLong() +
            sum(message, 0, message.size)
        return onesComplement(total)
    }

    private fun writeUdp(out: ByteArray, at: Int, sourcePort: Int, destinationPort: Int, payload: ByteArray) {
        writeShort(out, at, sourcePort)
        writeShort(out, at + 2, destinationPort)
        writeShort(out, at + 4, UDP_HEADER_BYTES + payload.size)
        payload.copyInto(out, at + UDP_HEADER_BYTES)
    }

    /** UDP checksum over the IP pseudo-header plus the UDP header and payload (RFC 768 / 2460). */
    private fun udpChecksum(
        packet: ByteArray,
        source: ByteArray,
        destination: ByteArray,
        udpStart: Int,
        payloadSize: Int,
    ): Int {
        val udpLength = UDP_HEADER_BYTES + payloadSize
        var total = sum(source, 0, source.size) +
            sum(destination, 0, destination.size) +
            PROTOCOL_UDP.toLong() +
            udpLength.toLong() +
            sum(packet, udpStart, udpLength)
        val checksum = onesComplement(total)
        // Zero means "no checksum" on the wire, so the all-ones form is sent instead.
        return if (checksum == 0) 0xFFFF else checksum
    }

    private fun sum(data: ByteArray, from: Int, length: Int): Long {
        var total = 0L
        var i = from
        val end = from + length
        while (i + 1 < end) {
            total += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) total += (data[i].toInt() and 0xFF) shl 8
        return total
    }

    private fun onesComplement(sum: Long): Int {
        var folded = sum
        while (folded shr 16 != 0L) folded = (folded and 0xFFFF) + (folded shr 16)
        return (folded.inv() and 0xFFFF).toInt()
    }

    private fun readInt(data: ByteArray, at: Int): Long =
        ((data[at].toLong() and 0xFF) shl 24) or
            ((data[at + 1].toLong() and 0xFF) shl 16) or
            ((data[at + 2].toLong() and 0xFF) shl 8) or
            (data[at + 3].toLong() and 0xFF)

    private fun writeInt(data: ByteArray, at: Int, value: Long) {
        data[at] = ((value ushr 24) and 0xFF).toByte()
        data[at + 1] = ((value ushr 16) and 0xFF).toByte()
        data[at + 2] = ((value ushr 8) and 0xFF).toByte()
        data[at + 3] = (value and 0xFF).toByte()
    }

    private fun readShort(data: ByteArray, at: Int): Int =
        ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)

    private fun writeShort(data: ByteArray, at: Int, value: Int) {
        data[at] = (value ushr 8).toByte()
        data[at + 1] = value.toByte()
    }
}

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
 * IPv4 and IPv6 packet reading and writing, for UDP only.
 *
 * IPv6 is not optional politeness here: a phone on a mobile network is routinely IPv6-only, and
 * a filter that understood v4 alone would silently pass every lookup on those networks while
 * still reporting itself as running.
 */
object IpPacket {

    const val PROTOCOL_UDP = 17

    private const val IPV4_HEADER_BYTES = 20
    private const val IPV6_HEADER_BYTES = 40
    private const val UDP_HEADER_BYTES = 8
    private const val DEFAULT_HOP_LIMIT = 64

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

    private fun buildIpv4(
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val total = IPV4_HEADER_BYTES + UDP_HEADER_BYTES + payload.size
        val out = ByteArray(total)
        out[0] = 0x45 // version 4, 5 words of header
        writeShort(out, 2, total)
        out[8] = DEFAULT_HOP_LIMIT.toByte()
        out[9] = PROTOCOL_UDP.toByte()
        source.copyInto(out, 12)
        destination.copyInto(out, 16)
        writeShort(out, 10, onesComplement(sum(out, 0, IPV4_HEADER_BYTES)))
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
        out[0] = 0x60 // version 6, no traffic class or flow label
        writeShort(out, 4, UDP_HEADER_BYTES + payload.size)
        out[6] = PROTOCOL_UDP.toByte()
        out[7] = DEFAULT_HOP_LIMIT.toByte()
        source.copyInto(out, 8)
        destination.copyInto(out, 24)
        writeUdp(out, IPV6_HEADER_BYTES, sourcePort, destinationPort, payload)
        // Unlike IPv4, a zero UDP checksum is illegal over IPv6 and the packet would be dropped.
        writeShort(out, IPV6_HEADER_BYTES + 6, udpChecksum(out, source, destination, IPV6_HEADER_BYTES, payload.size))
        return out
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

    private fun readShort(data: ByteArray, at: Int): Int =
        ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)

    private fun writeShort(data: ByteArray, at: Int, value: Int) {
        data[at] = (value ushr 8).toByte()
        data[at + 1] = value.toByte()
    }
}

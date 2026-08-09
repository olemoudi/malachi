package dev.malachi.filter.dns

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class IpPacketTest {

    private val v4Client = byteArrayOf(10, 111, 222.toByte(), 1)
    private val v4Server = byteArrayOf(10, 111, 222.toByte(), 2)
    private val v6Client = address("fd00:0000:0000:0000:0000:0000:0000:0001")
    private val v6Server = address("fd00:0000:0000:0000:0000:0000:0000:0002")
    private val payload = byteArrayOf(1, 2, 3, 4, 5)

    private fun address(text: String): ByteArray =
        text.split(':').flatMap { group ->
            val value = group.toInt(16)
            listOf((value shr 8).toByte(), value.toByte())
        }.toByteArray()

    private fun ipv4Udp(
        source: ByteArray = v4Client,
        destination: ByteArray = v4Server,
        sourcePort: Int = 40000,
        destinationPort: Int = 53,
        body: ByteArray = payload,
        protocol: Int = IpPacket.PROTOCOL_UDP,
        fragmentOffset: Int = 0,
    ): ByteArray {
        val out = ByteArray(20 + 8 + body.size)
        out[0] = 0x45
        writeShort(out, 2, out.size)
        writeShort(out, 6, fragmentOffset)
        out[8] = 64
        out[9] = protocol.toByte()
        source.copyInto(out, 12)
        destination.copyInto(out, 16)
        writeShort(out, 20, sourcePort)
        writeShort(out, 22, destinationPort)
        writeShort(out, 24, 8 + body.size)
        body.copyInto(out, 28)
        return out
    }

    private fun ipv6Udp(nextHeader: Int = IpPacket.PROTOCOL_UDP, body: ByteArray = payload): ByteArray {
        val out = ByteArray(40 + 8 + body.size)
        out[0] = 0x60
        writeShort(out, 4, 8 + body.size)
        out[6] = nextHeader.toByte()
        out[7] = 64
        v6Client.copyInto(out, 8)
        v6Server.copyInto(out, 24)
        writeShort(out, 40, 40000)
        writeShort(out, 42, 53)
        writeShort(out, 44, 8 + body.size)
        body.copyInto(out, 48)
        return out
    }

    private fun writeShort(data: ByteArray, at: Int, value: Int) {
        data[at] = (value ushr 8).toByte()
        data[at + 1] = value.toByte()
    }

    private fun shortAt(data: ByteArray, at: Int) =
        ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)

    /** A correct checksum makes the ones-complement sum over its own span come out all-ones. */
    private fun sumIsValid(data: ByteArray, from: Int, length: Int, extra: Long = 0L): Boolean {
        var total = extra
        var i = from
        while (i + 1 < from + length) {
            total += shortAt(data, i)
            i += 2
        }
        if (i < from + length) total += (data[i].toInt() and 0xFF) shl 8
        while (total shr 16 != 0L) total = (total and 0xFFFF) + (total shr 16)
        return total == 0xFFFFL
    }

    private fun pseudoHeaderSum(source: ByteArray, destination: ByteArray, udpLength: Int): Long {
        var total = 0L
        for (i in source.indices step 2) total += ((source[i].toInt() and 0xFF) shl 8) or (source[i + 1].toInt() and 0xFF)
        for (i in destination.indices step 2) total += ((destination[i].toInt() and 0xFF) shl 8) or (destination[i + 1].toInt() and 0xFF)
        return total + IpPacket.PROTOCOL_UDP + udpLength
    }

    @Test
    fun `an IPv4 UDP datagram is read back whole`() {
        val packet = ipv4Udp()
        val datagram = IpPacket.parseUdp(packet, packet.size)!!
        assertEquals(4, datagram.ipVersion)
        assertArrayEquals(v4Client, datagram.sourceAddress)
        assertArrayEquals(v4Server, datagram.destinationAddress)
        assertEquals(40000, datagram.sourcePort)
        assertEquals(53, datagram.destinationPort)
        assertArrayEquals(payload, datagram.payload(packet))
    }

    @Test
    fun `an IPv6 UDP datagram is read back whole`() {
        val packet = ipv6Udp()
        val datagram = IpPacket.parseUdp(packet, packet.size)!!
        assertEquals(6, datagram.ipVersion)
        assertArrayEquals(v6Client, datagram.sourceAddress)
        assertArrayEquals(v6Server, datagram.destinationAddress)
        assertEquals(53, datagram.destinationPort)
        assertArrayEquals(payload, datagram.payload(packet))
    }

    @Test
    fun `packets we cannot act on are declined rather than misread`() {
        assertNull(IpPacket.parseUdp(ByteArray(0), 0))
        assertNull(IpPacket.parseUdp(byteArrayOf(0x45), 1))
        // TCP, not UDP.
        val tcp = ipv4Udp(protocol = 6)
        assertNull(IpPacket.parseUdp(tcp, tcp.size))
        // A fragment carries only part of the datagram.
        val fragment = ipv4Udp(fragmentOffset = 3)
        assertNull(IpPacket.parseUdp(fragment, fragment.size))
        // An IPv6 extension header would have to be walked to find the payload.
        val extended = ipv6Udp(nextHeader = 44)
        assertNull(IpPacket.parseUdp(extended, extended.size))
        // Something that is neither IPv4 nor IPv6.
        assertNull(IpPacket.parseUdp(byteArrayOf(0x75, 0, 0, 0, 0, 0, 0, 0), 8))
    }

    @Test
    fun `a truncated read does not run off the end of the buffer`() {
        val packet = ipv4Udp()
        val datagram = IpPacket.parseUdp(packet, 30)!!
        assertEquals(2, datagram.payloadLength)
    }

    @Test
    fun `an IPv4 response reverses the conversation and checksums correctly`() {
        val packet = ipv4Udp()
        val request = IpPacket.parseUdp(packet, packet.size)!!
        val answer = byteArrayOf(9, 9, 9)
        val response = IpPacket.buildUdpResponse(request, answer)

        assertArrayEquals(v4Server, response.copyOfRange(12, 16), "reply comes from the resolver")
        assertArrayEquals(v4Client, response.copyOfRange(16, 20), "reply goes back to the caller")
        assertEquals(53, shortAt(response, 20))
        assertEquals(40000, shortAt(response, 22))
        assertArrayEquals(answer, response.copyOfRange(28, response.size))
        assertEquals(response.size, shortAt(response, 2))

        assertTrue(sumIsValid(response, 0, 20), "IPv4 header checksum")
        assertTrue(
            sumIsValid(response, 20, response.size - 20, pseudoHeaderSum(v4Server, v4Client, response.size - 20)),
            "UDP checksum",
        )
    }

    @Test
    fun `an IPv6 response carries the mandatory UDP checksum`() {
        val packet = ipv6Udp()
        val request = IpPacket.parseUdp(packet, packet.size)!!
        val response = IpPacket.buildUdpResponse(request, payload)

        assertEquals(6, (response[0].toInt() and 0xF0) shr 4)
        assertArrayEquals(v6Server, response.copyOfRange(8, 24))
        assertArrayEquals(v6Client, response.copyOfRange(24, 40))
        assertEquals(response.size - 40, shortAt(response, 4))
        // Zero would mean "not computed", which is illegal over IPv6 and gets the packet dropped.
        assertNotEquals(0, shortAt(response, 46))
        assertTrue(
            sumIsValid(response, 40, response.size - 40, pseudoHeaderSum(v6Server, v6Client, response.size - 40)),
            "UDP checksum",
        )
    }

    @Test
    fun `a response round-trips back into a readable datagram`() {
        val packet = ipv4Udp()
        val request = IpPacket.parseUdp(packet, packet.size)!!
        val response = IpPacket.buildUdpResponse(request, payload)
        val parsed = IpPacket.parseUdp(response, response.size)!!
        assertEquals(53, parsed.sourcePort)
        assertEquals(40000, parsed.destinationPort)
        assertArrayEquals(payload, parsed.payload(response))
    }

    private fun assertTrue(condition: Boolean, message: String) =
        org.junit.jupiter.api.Assertions.assertTrue(condition, message)
}

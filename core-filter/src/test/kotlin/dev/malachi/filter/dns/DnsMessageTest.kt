package dev.malachi.filter.dns

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DnsMessageTest {

    /** A standard query for [name], with recursion desired, as Android's resolver sends. */
    private fun query(name: String, type: Int = DnsMessage.TYPE_A): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun byte(vararg values: Int) = values.forEach { out.write(it and 0xFF) }
        byte(0x12, 0x34) // transaction id
        byte(0x01, 0x00) // RD set
        byte(0x00, 0x01) // QDCOUNT
        byte(0, 0, 0, 0, 0, 0) // ANCOUNT / NSCOUNT / ARCOUNT
        name.split('.').forEach { label ->
            byte(label.length)
            label.forEach { byte(it.code) }
        }
        byte(0) // root label
        byte(type shr 8, type)
        byte(0x00, 0x01) // class IN
        return out.toByteArray()
    }

    private fun shortAt(data: ByteArray, at: Int) =
        ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)

    @Test
    fun `the transaction id is the first two bytes of a message`() {
        assertEquals(0x1234, DnsMessage.transactionId(query("ads.example.com")))
    }

    @Test
    fun `anything too short to be a DNS message has no transaction id`() {
        // The caller uses this to decide whether a packet is worth a socket at all: any app on
        // the phone can send a stray byte to port 53, and reading past the end of it would throw
        // on the tunnel's own thread.
        assertNull(DnsMessage.transactionId(ByteArray(0)))
        assertNull(DnsMessage.transactionId(ByteArray(1)))
        assertNull(DnsMessage.transactionId(ByteArray(11)))
        assertEquals(0, DnsMessage.transactionId(ByteArray(12)))
    }

    @Test
    fun `a question is read back with its name and type`() {
        val question = DnsMessage.parseQuestion(query("ads.example.com"))!!
        assertEquals("ads.example.com", question.name)
        assertEquals(DnsMessage.TYPE_A, question.type)
        assertEquals(1, question.dnsClass)
    }

    @Test
    fun `names are lowercased, since DNS is case-insensitive and 0x20 encoding is real`() {
        assertEquals("ads.example.com", DnsMessage.parseQuestion(query("AdS.ExAmPlE.CoM"))!!.name)
    }

    @Test
    fun `a response is not treated as a query`() {
        val response = query("example.com").also { it[2] = 0x80.toByte() }
        assertFalse(DnsMessage.isStandardQuery(response))
        assertNull(DnsMessage.parseQuestion(response))
    }

    @Test
    fun `malformed messages are refused rather than guessed at`() {
        assertNull(DnsMessage.parseQuestion(ByteArray(4)))
        // A name whose length byte runs off the end of the buffer.
        val truncated = query("example.com").copyOf(16)
        assertNull(DnsMessage.parseQuestion(truncated))
        // A compression pointer, which cannot legally appear in a question.
        val pointer = query("example.com").also { it[12] = 0xC0.toByte() }
        assertNull(DnsMessage.parseQuestion(pointer))
    }

    @Test
    fun `NXDOMAIN keeps the transaction id and says the name does not exist`() {
        val q = query("ads.example.com")
        val question = DnsMessage.parseQuestion(q)!!
        val response = DnsMessage.blockedResponse(q, question, BlockAnswer.NXDOMAIN)

        assertEquals(0x1234, shortAt(response, 0))
        assertTrue(response[2].toInt() and 0x80 != 0, "QR must be set")
        assertTrue(response[2].toInt() and 0x01 != 0, "RD must be echoed")
        assertTrue(response[3].toInt() and 0x80 != 0, "RA must be set")
        assertEquals(3, response[3].toInt() and 0x0F, "RCODE must be NXDOMAIN")
        assertEquals(1, shortAt(response, 4))
        assertEquals(0, shortAt(response, 6))
        // The question is echoed verbatim, as every resolver expects.
        assertArrayEquals(q.copyOfRange(12, question.endOffset), response.copyOfRange(12, question.endOffset))
    }

    @Test
    fun `REFUSED sets the matching rcode`() {
        val q = query("ads.example.com")
        val response = DnsMessage.blockedResponse(q, DnsMessage.parseQuestion(q)!!, BlockAnswer.REFUSED)
        assertEquals(5, response[3].toInt() and 0x0F)
    }

    @Test
    fun `a null address answer resolves an A query to 0 0 0 0`() {
        val q = query("ads.example.com")
        val question = DnsMessage.parseQuestion(q)!!
        val response = DnsMessage.blockedResponse(q, question, BlockAnswer.NULL_ADDRESS)

        assertEquals(0, response[3].toInt() and 0x0F, "RCODE must be NOERROR")
        assertEquals(1, shortAt(response, 6), "one answer record")
        var i = question.endOffset
        assertEquals(0xC00C, shortAt(response, i)); i += 2
        assertEquals(DnsMessage.TYPE_A, shortAt(response, i)); i += 2
        assertEquals(1, shortAt(response, i)); i += 2
        i += 4 // TTL
        assertEquals(4, shortAt(response, i)); i += 2
        assertArrayEquals(ByteArray(4), response.copyOfRange(i, i + 4))
        assertEquals(response.size, i + 4)
    }

    @Test
    fun `a null address answer resolves an AAAA query to the all-zero address`() {
        val q = query("ads.example.com", DnsMessage.TYPE_AAAA)
        val question = DnsMessage.parseQuestion(q)!!
        val response = DnsMessage.blockedResponse(q, question, BlockAnswer.NULL_ADDRESS)
        assertEquals(1, shortAt(response, 6))
        // rdlength sits after the 2-byte name pointer, type, class and 4-byte TTL.
        assertEquals(16, shortAt(response, question.endOffset + 10))
        assertArrayEquals(ByteArray(16), response.copyOfRange(response.size - 16, response.size))
    }

    @Test
    fun `record types with no address to null out get an empty NOERROR`() {
        // An HTTPS record would otherwise hand the app the ad server's ECH keys and endpoints
        // after its A lookup was already refused.
        val q = query("ads.example.com", DnsMessage.TYPE_HTTPS)
        val response = DnsMessage.blockedResponse(q, DnsMessage.parseQuestion(q)!!, BlockAnswer.NULL_ADDRESS)
        assertEquals(0, response[3].toInt() and 0x0F)
        assertEquals(0, shortAt(response, 6))
    }
}

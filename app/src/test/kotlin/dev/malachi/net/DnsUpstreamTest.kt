package dev.malachi.net

import dev.malachi.filter.dns.DnsMessage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The upstream half of a lookup, over real loopback UDP.
 *
 * Real sockets rather than mocks on purpose: the two things being pinned down here — that a
 * connected socket refuses datagrams from anybody but its resolver, and that a late answer to an
 * earlier query is not relayed as the answer to this one — are kernel behaviour and mock
 * behaviour respectively, and only one of them is worth testing.
 */
class DnsUpstreamTest {

    private val loopback: InetAddress = InetAddress.getLoopbackAddress()
    private val closeables = mutableListOf<DatagramSocket>()

    @AfterEach
    fun close() = closeables.forEach { runCatching { it.close() } }

    private fun socket(): DatagramSocket = DatagramSocket(0, loopback).also { closeables += it }

    /** A client socket connected to [resolver], as the pool builds them. */
    private fun clientTo(resolver: DatagramSocket): DatagramSocket =
        socket().also { it.connect(loopback, resolver.localPort) }

    private fun query(id: Int, name: String = "example.com"): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun byte(vararg values: Int) = values.forEach { out.write(it and 0xFF) }
        byte(id shr 8, id)
        byte(0x01, 0x00)
        byte(0x00, 0x01)
        byte(0, 0, 0, 0, 0, 0)
        name.split('.').forEach { label ->
            byte(label.length)
            label.forEach { byte(it.code) }
        }
        byte(0, 0, 1, 0, 1)
        return out.toByteArray()
    }

    /** A reply with [id], distinguishable by its trailing marker byte. */
    private fun reply(id: Int, marker: Int): ByteArray =
        query(id).copyOf(20).also {
            it[2] = 0x81.toByte()
            it[19] = marker.toByte()
        }

    private fun exchange(socket: DatagramSocket, resolver: DatagramSocket, query: ByteArray, timeoutMs: Long): ByteArray? =
        DnsRelay.exchange(
            socket = socket,
            query = query,
            target = loopback,
            port = resolver.localPort,
            deadlineMs = System.nanoTime() / 1_000_000 + timeoutMs,
            bufferSize = 4032,
            nowMs = { System.nanoTime() / 1_000_000 },
        )

    // ---- the relay ---------------------------------------------------------------------------

    @Test
    fun `the answer to our query comes back byte for byte`() {
        val resolver = socket()
        val answer = reply(0x1234, marker = 7)
        val serving = Thread {
            val incoming = DatagramPacket(ByteArray(512), 512)
            resolver.receive(incoming)
            resolver.send(DatagramPacket(answer, answer.size, incoming.address, incoming.port))
        }.also { it.isDaemon = true; it.start() }

        val relayed = exchange(clientTo(resolver), resolver, query(0x1234), 2_000)
        serving.join(2_000)

        assertArrayEquals(answer, relayed)
    }

    @Test
    fun `a late answer to an earlier query is not relayed as the answer to this one`() {
        // Exactly what a pooled socket makes possible: the resolver answers a question we have
        // stopped waiting for, and then the real one. Only the second is ours.
        val resolver = socket()
        val ours = reply(0x2222, marker = 2)
        val serving = Thread {
            val incoming = DatagramPacket(ByteArray(512), 512)
            resolver.receive(incoming)
            val stale = reply(0x1111, marker = 1)
            resolver.send(DatagramPacket(stale, stale.size, incoming.address, incoming.port))
            resolver.send(DatagramPacket(ours, ours.size, incoming.address, incoming.port))
        }.also { it.isDaemon = true; it.start() }

        val relayed = exchange(clientTo(resolver), resolver, query(0x2222), 2_000)
        serving.join(2_000)

        assertArrayEquals(ours, relayed)
    }

    @Test
    fun `a resolver that never answers ends in silence, not an invented reply`() {
        val resolver = socket() // nothing ever reads it
        val started = System.nanoTime()

        val relayed = exchange(clientTo(resolver), resolver, query(0x3333), 300)

        assertNull(relayed)
        // And it waited for the deadline rather than returning at once or hanging past it.
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs in 250..3_000, "waited ${elapsedMs}ms")
    }

    @Test
    fun `a connected socket ignores an answer from anybody but its resolver`() {
        // The finding this exists for: unconnected, a stranger who reaches the port only has to
        // guess sixteen bits of transaction id to have an answer relayed to an app as genuine.
        val resolver = socket()
        val impostor = socket()
        val client = clientTo(resolver)

        val forged = reply(0x4444, marker = 9)
        val sending = Thread {
            val incoming = DatagramPacket(ByteArray(512), 512)
            resolver.receive(incoming)
            // Same transaction id, right port, wrong source.
            repeat(3) {
                impostor.send(DatagramPacket(forged, forged.size, incoming.address, incoming.port))
                Thread.sleep(20)
            }
        }.also { it.isDaemon = true; it.start() }

        val relayed = exchange(client, resolver, query(0x4444), 400)
        sending.join(2_000)

        assertNull(relayed, "a forged answer from another host was relayed")
    }

    @Test
    fun `a stray datagram too short to be DNS does not match on stale bytes`() {
        val resolver = socket()
        val ours = reply(0x5555, marker = 5)
        val serving = Thread {
            val incoming = DatagramPacket(ByteArray(512), 512)
            resolver.receive(incoming)
            val runt = byteArrayOf(0x55, 0x55)
            resolver.send(DatagramPacket(runt, runt.size, incoming.address, incoming.port))
            Thread.sleep(20)
            resolver.send(DatagramPacket(ours, ours.size, incoming.address, incoming.port))
        }.also { it.isDaemon = true; it.start() }

        val relayed = exchange(clientTo(resolver), resolver, query(0x5555), 2_000)
        serving.join(2_000)

        assertArrayEquals(ours, relayed)
    }

    @Test
    fun `a query with no room for a transaction id is never sent`() {
        val resolver = socket()
        val relayed = exchange(clientTo(resolver), resolver, ByteArray(4), 500)
        assertNull(relayed)
        assertNull(DnsMessage.transactionId(ByteArray(4)))
    }

    // ---- the pool ----------------------------------------------------------------------------

    @Test
    fun `a socket is reused for the resolver it is connected to`() {
        val resolver = socket()
        val pool = UpstreamSockets(capacity = 4) { clientTo(resolver) }

        val first = pool.borrow(loopback)!!
        pool.give(first)
        val second = pool.borrow(loopback)!!

        assertSame(first, second)
    }

    @Test
    fun `a socket connected elsewhere is never handed out for this resolver`() {
        // Connecting is what buys the kernel-side filtering, and it is also why the pool has to
        // be keyed: a v4 socket cannot carry a v6 query, and reusing it would strand the lookup.
        val resolver = socket()
        var opened = 0
        val pool = UpstreamSockets(capacity = 4) { opened++; clientTo(resolver) }

        val forLoopback = pool.borrow(loopback)!!
        pool.give(forLoopback)
        val forElsewhere = pool.borrow(InetAddress.getByName("192.0.2.1"))

        assertNotSame(forLoopback, forElsewhere)
        assertEquals(2, opened)
    }

    @Test
    fun `the pool does not grow past its cap`() {
        val resolver = socket()
        val pool = UpstreamSockets(capacity = 2) { clientTo(resolver) }

        val sockets = List(5) { pool.borrow(loopback)!! }
        sockets.forEach { pool.give(it) }

        assertEquals(2, pool.size)
        assertEquals(3, sockets.count { it.isClosed })
    }

    @Test
    fun `a socket closed while pooled is dropped rather than handed out`() {
        val resolver = socket()
        val pool = UpstreamSockets(capacity = 4) { clientTo(resolver) }

        val stale = pool.borrow(loopback)!!
        pool.give(stale)
        stale.close()

        val fresh = pool.borrow(loopback)!!
        assertNotSame(stale, fresh)
        assertTrue(!fresh.isClosed)
        assertEquals(0, pool.size)
    }

    @Test
    fun `closing the pool closes what it holds`() {
        // The network changed: every socket in here is bound to a network that has gone, and
        // keeping one is a five-second timeout waiting to happen.
        val resolver = socket()
        val pool = UpstreamSockets(capacity = 4) { clientTo(resolver) }
        val held = List(3) { pool.borrow(loopback)!! }
        held.forEach { pool.give(it) }

        pool.closeAll()

        assertEquals(0, pool.size)
        assertTrue(held.all { it.isClosed })
    }

    @Test
    fun `borrowing from several threads at once hands each one its own socket`() {
        val resolver = socket()
        val pool = UpstreamSockets(capacity = 4) { clientTo(resolver) }
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val handed = java.util.Collections.synchronizedList(mutableListOf<DatagramSocket>())

        repeat(8) {
            Thread {
                start.await()
                pool.borrow(loopback)?.let { handed += it }
                done.countDown()
            }.apply { isDaemon = true }.start()
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))

        assertEquals(8, handed.size)
        assertEquals(8, handed.distinct().size, "the same socket was handed to two threads at once")
    }
}

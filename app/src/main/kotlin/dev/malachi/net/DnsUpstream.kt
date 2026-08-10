package dev.malachi.net

import dev.malachi.filter.dns.DnsMessage
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.ArrayDeque

/**
 * The pool of upstream sockets, keyed by the resolver each one is connected to.
 *
 * Two costs are being avoided. `protect()` — which is what keeps a forwarded query from being
 * routed back into our own tunnel — is a round trip into the system server, and doing it per
 * lookup put an IPC on the hot path for a status that never expires. And an unconnected UDP
 * socket accepts a datagram from anybody who reaches its port, which leaves a sixteen-bit
 * transaction id as the only thing between a stranger and an answer relayed to an app as
 * genuine; connecting hands that filtering to the kernel.
 *
 * Connecting is also why the pool is keyed: a socket connected to one resolver cannot be reused
 * for another, so a mixed v4/v6 upstream list would otherwise churn a socket per query.
 */
class UpstreamSockets(
    private val capacity: Int,
    private val open: (InetAddress) -> DatagramSocket?,
) {

    private val pool = ArrayDeque<DatagramSocket>()

    /** A socket connected to [target], reused if the pool has one and fresh otherwise. */
    fun borrow(target: InetAddress): DatagramSocket? {
        synchronized(pool) {
            val candidates = pool.iterator()
            while (candidates.hasNext()) {
                val socket = candidates.next()
                if (socket.isClosed) {
                    candidates.remove()
                    continue
                }
                if (socket.inetAddress == target) {
                    candidates.remove()
                    return socket
                }
            }
        }
        return open(target)
    }

    /** Hands a still-usable socket back, or closes it if the pool is already full. */
    fun give(socket: DatagramSocket) {
        synchronized(pool) {
            if (pool.size >= capacity) socket.close() else pool.push(socket)
        }
    }

    /**
     * Closes everything held. Called when the network changes: these are bound to the network
     * that existed when they were made, and one bound to a Wi-Fi that has gone is a five-second
     * timeout waiting to happen.
     */
    fun closeAll() {
        synchronized(pool) {
            while (pool.isNotEmpty()) runCatching { pool.poll()?.close() }
        }
    }

    /** How many sockets are held right now. For tests and for reasoning about the cap. */
    val size: Int get() = synchronized(pool) { pool.size }
}

/**
 * Sends a query upstream and waits for *its* answer.
 *
 * The waiting is the subtle part. A pooled socket can still be holding a late reply to an
 * earlier query, so a datagram that arrives is only an answer if its transaction id matches the
 * one we sent; anything else is discarded and the wait continues against the same deadline
 * rather than restarting it.
 *
 * Null means nothing usable came back, and is also the signal not to reuse the socket: a
 * timeout is a normal event on a flaky network, but the answer it was waiting for can still
 * turn up afterwards — for whoever borrows that socket next. Dropping is right either way,
 * because the client's own resolver retries and inventing an answer is worse than silence.
 */
object DnsRelay {

    fun exchange(
        socket: DatagramSocket,
        query: ByteArray,
        target: InetAddress,
        port: Int,
        deadlineMs: Long,
        bufferSize: Int,
        nowMs: () -> Long,
    ): ByteArray? {
        val transactionId = DnsMessage.transactionId(query) ?: return null
        return try {
            socket.send(DatagramPacket(query, query.size, target, port))
            val buffer = ByteArray(bufferSize)
            var answer: ByteArray? = null
            while (answer == null) {
                val remaining = deadlineMs - nowMs()
                if (remaining <= 0) break
                socket.soTimeout = remaining.toInt()
                val reply = DatagramPacket(buffer, buffer.size)
                socket.receive(reply)
                // The length guard matters as much as the id: the buffer still holds the last
                // reply, so a stray short datagram would otherwise match on stale bytes.
                if (reply.length >= DnsMessage.HEADER_BYTES &&
                    DnsMessage.transactionId(buffer) == transactionId
                ) {
                    answer = buffer.copyOf(reply.length)
                }
            }
            answer
        } catch (t: Throwable) {
            null
        }
    }
}

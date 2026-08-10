package dev.malachi.net

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The descriptor facts the tunnel is built on, checked against the platform rather than assumed.
 *
 * Every one of these has cost this app something. A non-blocking descriptor read in a loop spun
 * a core flat out for as long as the filter was on. A `poll()` that nothing can interrupt made
 * shutdown depend on a timeout, which is a wakeup forever. And the ownership of a descriptor
 * shared between a stream and a `ParcelFileDescriptor` decides whether closing one of them is
 * safe — which is not something to be confident about from memory.
 */
@RunWith(AndroidJUnit4::class)
class TunnelDescriptorTest {

    @Test
    fun pipeIsAvailableAndWakesAPoll() {
        // Os.pipe2 is not in the SDK; Os.pipe() is. The self-pipe is what ends the read loop's
        // wait at once instead of at the next timeout.
        val pipe = Os.pipe()
        assertNotNull(pipe)
        try {
            val fds = arrayOf(
                StructPollfd().apply { fd = pipe[0]; events = OsConstants.POLLIN.toShort() },
            )

            // Nothing written yet: poll waits and gives up on its own.
            assertEquals(0, Os.poll(fds, 150))

            Os.write(pipe[1], byteArrayOf(1), 0, 1)
            fds[0].revents = 0
            assertEquals(1, Os.poll(fds, 1_000))
            assertTrue(fds[0].revents.toInt() and OsConstants.POLLIN != 0)
        } finally {
            pipe.forEach { runCatching { Os.close(it) } }
        }
    }

    @Test
    fun pollParksInsteadOfSpinning() {
        // The measurable half of the battery bug: a wait that returns immediately is a wait that
        // burns a core. This one has to actually take the time it was given.
        val pipe = Os.pipe()
        try {
            val fds = arrayOf(StructPollfd().apply { fd = pipe[0]; events = OsConstants.POLLIN.toShort() })
            val started = System.nanoTime()
            Os.poll(fds, 400)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("poll returned after ${elapsedMs}ms", elapsedMs >= 350)
        } finally {
            pipe.forEach { runCatching { Os.close(it) } }
        }
    }

    @Test
    fun closingTheTunDoesNotWakeAPollWaitingOnIt() {
        // The reason the self-pipe exists at all. If closing the descriptor were enough, the
        // read loop could be stopped by closing it and the pipe would be dead weight.
        //
        // No timeout on the poll and no sleeping before the close: the descriptor is closed only
        // once this thread has been *seen* inside poll, because a poll that had not started yet
        // would return POLLNVAL on an already-closed descriptor and prove nothing at all.
        val pipe = Os.pipe()
        val woke = CountDownLatch(1)
        val waiter = Thread {
            val fds = arrayOf(StructPollfd().apply { fd = pipe[0]; events = OsConstants.POLLIN.toShort() })
            runCatching { Os.poll(fds, -1) }
            woke.countDown()
        }.apply { isDaemon = true; start() }

        val deadline = System.nanoTime() + 10_000L * 1_000_000
        while (System.nanoTime() < deadline && waiter.stackTrace.none { it.methodName == "poll" }) {
            Thread.sleep(20)
        }
        assertTrue("the waiting thread never reached poll()", waiter.stackTrace.any { it.methodName == "poll" })

        runCatching { Os.close(pipe[0]) }

        // Nothing else can release it: there is no timeout to expire, so if it returns at all it
        // returned because of the close.
        assertTrue("poll returned when its descriptor was closed", !woke.await(2, TimeUnit.SECONDS))
        // Left parked on purpose — it is a daemon thread and there is no way to release a poll
        // whose only descriptor is gone, which is the whole point being made.
        runCatching { Os.close(pipe[1]) }
    }

    @Test
    fun aStreamBuiltOnAParcelFileDescriptorDoesNotOwnIt() {
        // Which of the two closes the descriptor decides whether stopTunnel's ordering is merely
        // careful or actively wrong. Asserting it here means the answer is the platform's, not a
        // recollection of what the platform used to do.
        val pipe = ParcelFileDescriptor.createPipe()
        val write = pipe[1]
        try {
            val stream = FileOutputStream(write.fileDescriptor)
            stream.write(byteArrayOf(1))
            stream.close()

            val stillOpen = runCatching { Os.fstat(write.fileDescriptor) }.isSuccess
            assertTrue(
                "closing the stream closed the descriptor: stopTunnel must not close both",
                stillOpen,
            )
        } finally {
            pipe.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun aClosedDescriptorIsRefusedRatherThanSilentlyReused() {
        // The hazard stopTunnel guards against: once closed, the number is free, and the kernel
        // gives it to the next thing this process opens. A read on it is an error only while
        // nothing has claimed it — which is exactly why the reader is joined before the close.
        val pipe = ParcelFileDescriptor.createPipe()
        val read = pipe[0]
        val descriptor = read.fileDescriptor
        read.close()
        pipe[1].close()

        try {
            Os.read(descriptor, ByteArray(8), 0, 8)
            fail("reading a closed descriptor succeeded")
        } catch (e: ErrnoException) {
            assertEquals(OsConstants.EBADF, e.errno)
        }
    }

    @Test
    fun aNonBlockingDescriptorReturnsNothingImmediately() {
        // The shape of the original bug: establish() hands back a non-blocking descriptor, so a
        // stream-shaped read loop gets nothing back at once, forever, as fast as it can ask.
        val pipe = Os.pipe()
        try {
            val flags = Os.fcntlInt(pipe[0], OsConstants.F_GETFL, 0)
            Os.fcntlInt(pipe[0], OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)

            val started = System.nanoTime()
            repeat(50) {
                try {
                    Os.read(pipe[0], ByteArray(64), 0, 64)
                } catch (e: ErrnoException) {
                    assertEquals(OsConstants.EAGAIN, e.errno)
                }
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("fifty reads took ${elapsedMs}ms, which is not a busy loop", elapsedMs < 200)
        } finally {
            pipe.forEach { runCatching { Os.close(it) } }
        }
    }
}

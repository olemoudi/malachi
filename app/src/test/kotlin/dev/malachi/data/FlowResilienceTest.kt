package dev.malachi.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The retry that keeps the settings flow collecting.
 *
 * Time here is virtual: `runTest` runs the scheduler's clock rather than the wall clock, so a
 * backoff that climbs to a minute is exercised in microseconds and the elapsed figures asserted
 * below are exact rather than approximate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowResilienceTest {

    @Test
    fun `the backoff doubles and then holds`() {
        assertEquals(2_000, backoffDelayMs(2_000, 5, 0))
        assertEquals(4_000, backoffDelayMs(2_000, 5, 1))
        assertEquals(64_000, backoffDelayMs(2_000, 5, 5))
        assertEquals(64_000, backoffDelayMs(2_000, 5, 6))
    }

    @Test
    fun `an attempt count from months of uptime does not overflow into nonsense`() {
        // A process that stays up for months can count high enough to wrap an Int, and a shift
        // by a wrapped value is a delay of zero — or a negative one.
        assertEquals(64_000, backoffDelayMs(2_000, 5, Long.MAX_VALUE))
        assertEquals(64_000, backoffDelayMs(2_000, 5, Int.MAX_VALUE.toLong() + 1))
        assertTrue(backoffDelayMs(2_000, 5, Long.MAX_VALUE) > 0)
    }

    @Test
    fun `a flow that throws is collected again rather than ending for good`() = runTest {
        var attempts = 0
        val flaky = flow {
            attempts++
            if (attempts < 3) throw IllegalStateException("storage is unhappy")
            emit("settings")
        }

        val collected = flaky.retryingWithBackoff(2_000, 5).take(1).toList()

        assertEquals(listOf("settings"), collected)
        assertEquals(3, attempts)
    }

    @Test
    fun `the waiting between attempts is the backoff, not a busy loop`() = runTest {
        var attempts = 0
        val flaky = flow {
            attempts++
            if (attempts < 4) throw IOException("gone")
            emit(Unit)
        }

        val started = currentTime
        flaky.retryingWithBackoff(2_000, 5).take(1).toList()

        // 2s + 4s + 8s of virtual time, and none of it real.
        assertEquals(14_000, currentTime - started)
    }

    @Test
    fun `every failure is reported so a permanent one is visible in the log`() {
        val causes = mutableListOf<String>()
        runTest {
            var attempts = 0
            flow {
                attempts++
                if (attempts < 3) throw IllegalStateException("boom $attempts")
                emit(Unit)
            }
                .retryingWithBackoff(1_000, 3) { cause, attempt -> causes += "$attempt:${cause.message}" }
                .take(1)
                .toList()
        }
        assertEquals(listOf("0:boom 1", "1:boom 2"), causes)
    }

    @Test
    fun `a flow that fails for a simulated day keeps trying at the capped interval`() {
        val day = 24 * 60 * 60 * 1000L
        runTest {
            var attempts = 0
            val started = currentTime
            flow<Unit> {
                attempts++
                if (currentTime - started < day) throw IOException("still down")
                emit(Unit)
            }
                .retryingWithBackoff(2_000, 5)
                .take(1)
                .toList()

            // A day of failure, in virtual time, at a capped 64s between attempts: bounded, and
            // still trying at the end of it.
            assertTrue(attempts in 1_300..1_400, "a simulated day took $attempts attempts")
            assertTrue(currentTime - started >= day)
        }
    }
}

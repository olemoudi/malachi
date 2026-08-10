package dev.malachi.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen

/**
 * Keeps a long-lived flow collecting after a failure.
 *
 * This exists because of what a settings flow is used for here. Five different collectors watch
 * it for the entire life of the process — the tunnel, the filter's rule assembly, the list
 * scheduler — and an exception that reaches any of them ends *that* collection for good. The
 * tunnel would then keep running on whatever settings it last saw: switching the filter off in
 * the UI would write the setting, and nothing would act on it.
 *
 * The backoff is capped rather than unbounded, because the failure this guards against is a
 * storage layer that is unhappy, and retrying it forever at full speed is how a filter becomes a
 * battery complaint.
 */
fun <T> Flow<T>.retryingWithBackoff(
    baseDelayMs: Long,
    maxShift: Int,
    onFailure: (Throwable, Long) -> Unit = { _, _ -> },
): Flow<T> = retryWhen { cause, attempt ->
    onFailure(cause, attempt)
    delay(backoffDelayMs(baseDelayMs, maxShift, attempt))
    true
}

/**
 * Doubling backoff, capped. [attempt] is a Long and is coerced before the shift: a process that
 * stays up for months can produce an attempt count that overflows an Int, and a negative shift
 * is a delay of nonsense.
 */
fun backoffDelayMs(baseDelayMs: Long, maxShift: Int, attempt: Long): Long =
    baseDelayMs shl attempt.coerceIn(0, maxShift.toLong()).toInt()

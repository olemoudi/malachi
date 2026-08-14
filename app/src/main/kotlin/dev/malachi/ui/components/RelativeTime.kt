package dev.malachi.ui.components

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.malachi.R
import java.util.Locale

/**
 * How long ago something happened — "a moment ago", "10 sec. ago", "5 min ago".
 *
 * The log has always known this and never said it, which left its two most common questions
 * unanswerable: whether a row belongs to the app you just used, and whether "seen 12 times" was
 * twelve times this minute or twelve times today.
 *
 * Resolution goes down to **seconds** on purpose. A minute is an eternity when the errand is
 * "this app failed a second ago, what did it just ask for": rounding the last ten seconds to
 * "just now" and the last fifty to the same words throws away the only thing that distinguishes
 * the lookup that broke something from the forty before it. Below five seconds there is nothing
 * left worth counting, and the platform's own phrasing for it ("0 sec. ago") is not a sentence.
 *
 * Everything above that floor is handed to the platform, which already writes and abbreviates
 * this in the device's language.
 */
@Composable
fun relativeTime(atMs: Long, nowMs: Long): String {
    val elapsed = nowMs - atMs
    return if (atMs <= 0 || elapsed < JUST_NOW_MS) {
        stringResource(R.string.time_just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(
            atMs,
            nowMs,
            DateUtils.SECOND_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
    }
}

/** The same, said in full: "last seen 10 sec. ago". For a line of its own rather than a corner. */
@Composable
fun lastSeenLabel(atMs: Long, nowMs: Long): String =
    stringResource(R.string.time_last_seen, relativeTime(atMs, nowMs))

/**
 * A length of time, short and in the device's language: "40 sec", "3 min", "2 hr".
 *
 * Through ICU rather than strings of our own. A duration needs a plural rule per unit per
 * language, which is six translations to keep in step for a phrase that the platform already
 * knows how to write — and gets right in languages neither of us was thinking about.
 */
fun shortDuration(ms: Long): String {
    val seconds = ms / 1000
    val measure = when {
        seconds < 90 -> Measure(seconds.coerceAtLeast(1), MeasureUnit.SECOND)
        // An hour is an hour, not sixty minutes: the boundary is exact rather than generous
        // because the only durations that reach it are round ones somebody chose from a list.
        seconds < 3600 -> Measure(seconds / 60, MeasureUnit.MINUTE)
        else -> Measure(seconds / 3600, MeasureUnit.HOUR)
    }
    return MeasureFormat.getInstance(Locale.getDefault(), MeasureFormat.FormatWidth.SHORT)
        .format(measure)
}

private const val JUST_NOW_MS = 5_000L

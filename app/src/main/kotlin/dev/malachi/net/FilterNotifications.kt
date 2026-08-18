package dev.malachi.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.malachi.MainActivity
import dev.malachi.R

/**
 * Malachi posts a notification only when there is something to say.
 *
 * There used to be a permanent one counting blocked lookups. It was removed on purpose: Android
 * already shows a VPN key in the status bar for as long as the tunnel is up, so a second
 * always-present indicator said nothing the system wasn't saying, and it kept a counter live —
 * and the phone awake to redraw it — for a number nobody reads.
 *
 * What is left is genuinely transient. A pause has to be visible and reversible, and it is also
 * what keeps the service alive across those fifteen minutes with no tunnel for the platform to
 * hold. A filter that has stopped and cannot restart itself has to say so, or the app is lying
 * by omission — and that one is dismissible, because it is news, not a status.
 */
object FilterNotifications {

    const val CHANNEL = "malachi_filter"

    /** The foreground-service slot, used only while filtering is paused. */
    const val NOTIFICATION_ID = 41

    /** A separate slot so dismissing a problem can't disturb the pause notification. */
    private const val PROBLEM_ID = 42

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL,
            context.getString(R.string.filter_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.filter_channel_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Shown for the moment it takes to re-establish the tunnel after the process was killed.
     * Android will not let a background caller start a service without one, so this exists to
     * be paid and immediately withdrawn rather than to be read.
     */
    fun starting(context: Context): Notification =
        base(context)
            .setOngoing(true)
            .setContentTitle(context.getString(R.string.filter_notification_starting))
            .build()

    /** Shown while filtering is suspended, so a pause is never silent or unexplained. */
    fun paused(context: Context, untilLabel: String): Notification =
        base(context)
            .setOngoing(true)
            .setContentTitle(context.getString(R.string.filter_notification_paused))
            .setContentText(context.getString(R.string.filter_notification_paused_until, untilLabel))
            .addAction(0, context.getString(R.string.action_resume), serviceAction(context, MalachiVpnService.ACTION_RESUME, 3))
            .build()

    /** The filter has stopped and needs a person. Dismissible: it is an event, not a state. */
    fun postProblem(context: Context, reason: String) {
        val notification = base(context)
            .setContentTitle(context.getString(R.string.filter_notification_not_running))
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(PROBLEM_ID, notification) }
    }

    fun cancelProblem(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(PROBLEM_ID) }
    }

    private fun base(context: Context): NotificationCompat.Builder {
        // Cheap, idempotent, and the only thing standing between a failed channel creation at
        // service start and a foreground promotion the platform answers with a crash. `onCreate`
        // already tries; this is what covers the run where that try was the one that failed.
        runCatching { ensureChannel(context) }
        return builder(context)
    }

    private fun builder(context: Context) = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_shield)
        .setContentIntent(
            PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .setSilent(true)
        .setShowWhen(false)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)

    private fun serviceAction(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context, requestCode,
            Intent(context, MalachiVpnService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

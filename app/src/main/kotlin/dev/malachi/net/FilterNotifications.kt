package dev.malachi.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.malachi.MainActivity
import dev.malachi.R

/**
 * The ongoing notification the filter runs under.
 *
 * A foreground service is the price of a process that must survive being backgrounded, and its
 * notification is the only continuous surface Malachi has. It is used for exactly what belongs
 * there: whether filtering is on, how much it has done, and one tap to stop or pause it — so a
 * user who suspects Malachi of breaking something can rule it out without hunting through
 * settings. Low importance and silent: an always-present notification that ever makes a sound
 * is one the user will turn off, taking the service's reliability with it.
 */
object FilterNotifications {

    const val CHANNEL = "malachi_filter"
    const val NOTIFICATION_ID = 41

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL,
            context.getString(R.string.filter_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.filter_channel_description)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    /** The running notification. [blocked] and [total] are counts since the filter started. */
    fun running(context: Context, blocked: Long, total: Long): Notification =
        base(context)
            .setContentTitle(context.getString(R.string.filter_notification_active))
            .setContentText(
                if (total == 0L) {
                    context.getString(R.string.filter_notification_no_queries)
                } else {
                    context.getString(R.string.filter_notification_counts, blocked, total)
                },
            )
            .addAction(0, context.getString(R.string.action_pause), serviceAction(context, MalachiVpnService.ACTION_PAUSE, 1))
            .addAction(0, context.getString(R.string.action_stop), serviceAction(context, MalachiVpnService.ACTION_STOP, 2))
            .build()

    /** Shown while filtering is suspended, so the pause is never silent or unexplained. */
    fun paused(context: Context, untilLabel: String): Notification =
        base(context)
            .setContentTitle(context.getString(R.string.filter_notification_paused))
            .setContentText(context.getString(R.string.filter_notification_paused_until, untilLabel))
            .addAction(0, context.getString(R.string.action_resume), serviceAction(context, MalachiVpnService.ACTION_RESUME, 3))
            .build()

    /** Shown when the tunnel could not be established; [reason] is already user-facing text. */
    fun problem(context: Context, reason: String): Notification =
        base(context)
            .setContentTitle(context.getString(R.string.filter_notification_not_running))
            .setContentText(reason)
            .build()

    private fun base(context: Context) = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_shield)
        .setContentIntent(
            PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .setOngoing(true)
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

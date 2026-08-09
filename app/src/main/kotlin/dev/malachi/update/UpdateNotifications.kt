package dev.malachi.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.malachi.R

/** "Update ready — tap to install", for the case where the system wants a confirmation. */
object UpdateNotifications {

    private const val CHANNEL = "malachi_updates"
    private const val NOTIF_ID = 43

    /**
     * Says that a newer release exists, at the moment it is found.
     *
     * The check runs on its own every twelve hours whether or not anybody opens the app, and
     * until now it was entirely silent: an update was found, downloaded and installed without a
     * word, and if the install needed a hand it said so only then. Announcing the find means a
     * user who never opens Malachi still learns their filter is about to change — and, when the
     * automatic install cannot proceed, that there is something waiting for them.
     */
    fun notifyUpdateFound(context: Context, versionName: String) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.update_found_title, versionName))
            .setContentText(context.getString(R.string.update_found_text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, notification) }
    }

    private fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.update_channel_name),
                // An update is never urgent enough to buzz a phone.
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, dev.malachi.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun notifyConfirmationNeeded(context: Context, confirmIntent: Intent) {
        ensureChannel(context)
        val tap = PendingIntent.getActivity(
            context, 0, confirmIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.update_ready_title))
            .setContentText(context.getString(R.string.update_ready_text))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, notification) }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID) }
    }
}

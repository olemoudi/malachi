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

    fun notifyConfirmationNeeded(context: Context, confirmIntent: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.update_channel_name),
                // An update prompt is never urgent enough to buzz a phone.
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
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

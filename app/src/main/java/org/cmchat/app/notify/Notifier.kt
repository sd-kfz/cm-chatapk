package org.cmchat.app.notify

import org.cmchat.app.R
import org.cmchat.app.i18n.Tr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat


object Notifier {
    private const val CHANNEL_ID = "cm_activity"

    private const val BUZZ_CHANNEL_ID = "cm_buzz"
    private const val ID_ACTIVITY = 8001
    private const val ID_MESSAGE = 8002
    private const val ID_BUZZ = 8003


    const val BRAND_COLOR = 0xFF35C6F2.toInt()

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, Tr.s(R.string.notif_activity), NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun post(ctx: Context, id: Int, title: Int) {
        Tr.attach(ctx)
        ensureChannel(ctx)

        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(org.cmchat.app.R.drawable.ic_stat_flower)
            .setColor(BRAND_COLOR)
            .setContentTitle(Tr.s(title))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setShowWhen(false)
        runCatching { NotificationManagerCompat.from(ctx).notify(id, builder.build()) }
    }


    fun activity(ctx: Context) = post(ctx, ID_ACTIVITY, R.string.notif_activity)


    fun buzz(ctx: Context) {
        Tr.attach(ctx)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(BUZZ_CHANNEL_ID, Tr.s(R.string.notif_buzz_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                setShowBadge(false)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val builder = NotificationCompat.Builder(ctx, BUZZ_CHANNEL_ID)
            .setSmallIcon(org.cmchat.app.R.drawable.ic_stat_flower)
            .setColor(BRAND_COLOR)
            .setContentTitle(Tr.s(R.string.notif_activity))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setShowWhen(false)
        runCatching { NotificationManagerCompat.from(ctx).notify(ID_BUZZ, builder.build()) }
    }


    fun message(ctx: Context) = post(ctx, ID_MESSAGE, R.string.notif_message)

    fun clearAll(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx).cancelAll() }
    }
}

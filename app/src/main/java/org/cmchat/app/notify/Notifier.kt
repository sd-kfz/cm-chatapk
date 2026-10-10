package org.cmchat.app.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Deliberately generic notifications. A notification NEVER reveals who sent a
 * message: a BUZZ shows only as "Activity", a new message only as "Notification"
 * (the original CM-Chat behaviour). There is no option to show a sender name.
 * Every notification is cleared on any wipe path.
 */
object Notifier {
    private const val CHANNEL_ID = "cm_activity"
    /** Buzz gets its own HIGH-importance channel: it pops up (heads-up) and
     * vibrates, so a nudge is actually noticed. Still generic text only. */
    private const val BUZZ_CHANNEL_ID = "cm_buzz"
    private const val ID_ACTIVITY = 8001
    private const val ID_MESSAGE = 8002
    private const val ID_BUZZ = 8003

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Activity", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun post(ctx: Context, id: Int, title: String) {
        ensureChannel(ctx)
        // Title only — never a sender name and never any message content.
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setShowWhen(false)
        runCatching { NotificationManagerCompat.from(ctx).notify(id, builder.build()) }
    }

    /** Something happened (e.g. a friend request). Generic "Activity". */
    fun activity(ctx: Context) = post(ctx, ID_ACTIVITY, "Activity")

    /** A BUZZ arrived: a heads-up "Activity" notification that vibrates. No
     * sender, no content. (Needs notifications allowed for CM-Chat.) */
    fun buzz(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(BUZZ_CHANNEL_ID, "Buzz", NotificationManager.IMPORTANCE_HIGH).apply {
                setShowBadge(false)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val builder = NotificationCompat.Builder(ctx, BUZZ_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Activity")
            .setPriority(NotificationCompat.PRIORITY_HIGH)   // heads-up on Android 7
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setShowWhen(false)
        runCatching { NotificationManagerCompat.from(ctx).notify(ID_BUZZ, builder.build()) }
    }

    /** A new message arrived. Generic "Notification" — no sender, no content. */
    fun message(ctx: Context) = post(ctx, ID_MESSAGE, "Notification")

    fun clearAll(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx).cancelAll() }
    }
}

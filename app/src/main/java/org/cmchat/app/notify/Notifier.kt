package org.cmchat.app.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.cmchat.app.settings.AppSettings

/**
 * Deliberately generic notifications. By default nothing on the lock screen
 * names a sender or shows content: a BUZZ shows as "Activity", a new message as
 * "Notification" (the original CM-Chat behaviour). A single setting can switch
 * on the sender's nickname. Every notification is cleared on any wipe path.
 */
object Notifier {
    private const val CHANNEL_ID = "cm_activity"
    private const val ID_ACTIVITY = 8001
    private const val ID_MESSAGE = 8002

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

    private fun post(ctx: Context, id: Int, title: String, nickname: String?) {
        ensureChannel(ctx)
        // Show a nickname only if the user opted in; never any message content.
        val showName = AppSettings.showBuzzSenderName.value && !nickname.isNullOrBlank()
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setShowWhen(false)
        if (showName) builder.setContentText(nickname)
        runCatching { NotificationManagerCompat.from(ctx).notify(id, builder.build()) }
    }

    /** A buzz arrived. Generic "Activity"; nickname only if the setting is on. */
    fun activity(ctx: Context, nickname: String?) = post(ctx, ID_ACTIVITY, "Activity", nickname)

    /** A new message arrived. Generic "Notification"; nickname only if opted in. */
    fun message(ctx: Context, nickname: String?) = post(ctx, ID_MESSAGE, "Notification", nickname)

    fun clearAll(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx).cancelAll() }
    }
}

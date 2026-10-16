package xyz.moshushi.app.util

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import xyz.moshushi.app.App
import xyz.moshushi.app.MainActivity

fun pushNotification(ctx: Context, channel: String, title: String, text: String, id: Int) {
    try {
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, channel)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    } catch (_: Exception) {
    }
}

/** App 通知渠道 id（App.onCreate 里创建） */
object Channels {
    const val SUMMARY = App.CH_SUMMARY
    const val REMINDER = App.CH_REMINDER
}

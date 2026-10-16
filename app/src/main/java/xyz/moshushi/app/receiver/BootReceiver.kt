package xyz.moshushi.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import xyz.moshushi.app.AlarmScheduler

/** 开机后重新排期每日闹钟 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AlarmScheduler.scheduleAll(context)
        }
    }
}

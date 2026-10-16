package xyz.moshushi.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import xyz.moshushi.app.receiver.AlarmReceiver
import java.util.Calendar

/** 每天 22:30 提醒 / 23:05 总结。用精确闹钟（若已授权），否则用非精确兜底。 */
object AlarmScheduler {
    const val ACTION_SUMMARY = "xyz.moshushi.app.ACTION_SUMMARY"
    const val ACTION_REMINDER = "xyz.moshushi.app.ACTION_REMINDER"

    fun scheduleAll(ctx: Context) {
        scheduleDaily(ctx, 23, 5, ACTION_SUMMARY, 1001)
        scheduleDaily(ctx, 22, 30, ACTION_REMINDER, 1002)
    }

    private fun scheduleDaily(ctx: Context, h: Int, m: Int, action: String, reqCode: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            ctx, reqCode,
            Intent(ctx, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        try {
            if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }
    }

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
                (ctx.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true)
}

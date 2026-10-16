package xyz.moshushi.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import xyz.moshushi.app.util.LogFile

class App : Application() {
    companion object {
        const val CH_SUMMARY = "summary"
        const val CH_REMINDER = "reminder"
    }

    override fun onCreate() {
        // 日志与崩溃捕获必须最先装好，启动闪退也能留下堆栈
        LogFile.init(this)
        LogFile.installUncaughtHandler()
        LogFile.d("App", "onCreate start")
        super.onCreate()
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_SUMMARY, getString(R.string.ch_summary), NotificationManager.IMPORTANCE_DEFAULT)
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_REMINDER, getString(R.string.ch_reminder), NotificationManager.IMPORTANCE_DEFAULT)
            )
            LogFile.d("App", "notification channels ok")
            AlarmScheduler.scheduleAll(this)
            LogFile.d("App", "alarms scheduled")
        } catch (e: Exception) {
            LogFile.e("App", "onCreate failed", e)
            throw e
        }
        LogFile.d("App", "onCreate end")
    }
}

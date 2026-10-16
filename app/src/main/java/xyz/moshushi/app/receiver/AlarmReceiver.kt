package xyz.moshushi.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import xyz.moshushi.app.AlarmScheduler
import xyz.moshushi.app.Prefs
import xyz.moshushi.app.R
import xyz.moshushi.app.data.DailySummary
import xyz.moshushi.app.data.DbHelper
import xyz.moshushi.app.util.Channels
import xyz.moshushi.app.util.dayBounds
import xyz.moshushi.app.util.fmtDelta
import xyz.moshushi.app.util.fmtDuration
import xyz.moshushi.app.util.pushNotification
import xyz.moshushi.app.util.todayStr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 每天 22:30（提醒补数据）/ 23:05（生成日报）的闹钟接收器。触发后顺手排好明天的。 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        xyz.moshushi.app.util.LogFile.init(context)
        xyz.moshushi.app.util.LogFile.d("Alarm", "onReceive ${intent.action}")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    AlarmScheduler.ACTION_SUMMARY -> doSummary(context)
                    AlarmScheduler.ACTION_REMINDER -> doReminder(context)
                }
            } finally {
                pending.finish()
            }
            AlarmScheduler.scheduleAll(context)
        }
    }

    private suspend fun doSummary(ctx: Context) {
        val db = DbHelper.get(ctx)
        val date = todayStr()
        val (start, end) = dayBounds(date)
        val now = System.currentTimeMillis()
        val sessions = db.sessionsOf(date)
        val totalSec = sessions.sumOf { s ->
            val raw = if (s.closedAt != null) s.durationSec else maxOf(0L, (now - s.openedAt) / 1000)
            minOf(raw, 21600L) // 单会话 6 小时封顶，防止脏数据
        }
        val snaps = db.snapshotsBetween(start, end)
        val summary = DailySummary(
            date = date,
            opens = sessions.size,
            totalSec = totalSec,
            firstOpenAt = sessions.minOfOrNull { it.openedAt },
            lastCloseAt = sessions.mapNotNull { it.closedAt }.maxOrNull(),
            followersStart = snaps.firstOrNull()?.followers,
            followersEnd = snaps.lastOrNull()?.followers,
            postsStart = snaps.firstOrNull()?.posts,
            postsEnd = snaps.lastOrNull()?.posts
        )
        db.upsertSummary(summary)

        val parts = mutableListOf(ctx.getString(R.string.sum_opens, summary.opens, ctx.fmtDuration(summary.totalSec)))
        if (summary.followersStart != null && summary.followersEnd != null) {
            parts.add(ctx.getString(R.string.sum_followers, summary.followersStart!!, summary.followersEnd!!, fmtDelta(summary.followersEnd - summary.followersStart)))
        } else {
            parts.add(ctx.getString(R.string.sum_no_followers))
        }
        if (summary.postsStart != null && summary.postsEnd != null) {
            parts.add(ctx.getString(R.string.sum_posts, summary.postsStart!!, summary.postsEnd!!, fmtDelta(summary.postsEnd - summary.postsStart)))
        }
        pushNotification(ctx, Channels.SUMMARY, ctx.getString(R.string.summary_title, date), parts.joinToString("；"), 8001)
    }

    private suspend fun doReminder(ctx: Context) {
        val db = DbHelper.get(ctx)
        val (start, _) = dayBounds(todayStr())
        val snaps = db.snapshotsBetween(start, System.currentTimeMillis())
        if (snaps.isEmpty()) {
            pushNotification(
                ctx, Channels.REMINDER, ctx.getString(R.string.reminder_title),
                ctx.getString(R.string.reminder_text), 8002
            )
        }
    }
}

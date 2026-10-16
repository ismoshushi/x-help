package xyz.moshushi.app.util

import android.content.Context
import xyz.moshushi.app.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun todayStr(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

fun dayBounds(date: String): Pair<Long, Long> {
    val cal = Calendar.getInstance()
    val parts = date.split("-").map { it.toInt() }
    cal.set(parts[0], parts[1] - 1, parts[2], 0, 0, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val start = cal.timeInMillis
    cal.add(Calendar.DAY_OF_YEAR, 1)
    return start to cal.timeInMillis
}

/** 距离今天 h:m 还有多少毫秒（已过则算明天） */
fun delayTo(h: Int, m: Int): Long {
    val now = Calendar.getInstance()
    val t = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, h)
        set(Calendar.MINUTE, m)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (t.timeInMillis <= now.timeInMillis) t.add(Calendar.DAY_OF_YEAR, 1)
    return t.timeInMillis - now.timeInMillis
}

fun fmtTime(ms: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

fun fmtDateTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

/** 中英双语时长：走 Context 取文案 */
fun Context.fmtDuration(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return when {
        h > 0 -> getString(R.string.dur_h, h, m)
        m > 0 -> getString(R.string.dur_m, m)
        else -> getString(R.string.dur_s, totalSec)
    }
}

fun fmtDelta(v: Int?): String = when {
    v == null -> "—"
    v > 0 -> "+$v"
    else -> "$v"
}

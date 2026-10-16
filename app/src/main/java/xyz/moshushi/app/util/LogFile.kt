package xyz.moshushi.app.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地文件日志：崩溃排查用。
 * 写到应用外部文件目录（Android/data/xyz.moshushi.app/files/logs/），无需任何权限，
 * 用户可用文件管理器直接找到，也能在设置页一键分享。
 */
object LogFile {
    private const val MAX_BYTES = 512 * 1024L

    @Volatile
    private var dir: File? = null
    private val lock = Any()
    private val tsFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun init(ctx: Context) {
        if (dir != null) return
        val appCtx = ctx.applicationContext
        val d = appCtx.getExternalFilesDir("logs")
            ?: File(appCtx.filesDir, "logs")
        try {
            if (!d.exists()) d.mkdirs()
        } catch (_: Exception) {
        }
        dir = d
    }

    /** 日志文件（可能不存在） */
    fun file(): File? = dir?.let { File(it, "xtracker.log") }

    /** 给用户看的完整路径 */
    fun userVisiblePath(): String = file()?.absolutePath ?: ""

    fun hasLog(): Boolean = file()?.exists() == true

    fun d(tag: String, msg: String) = write("D", tag, msg, null)

    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        try {
            val f = file() ?: return
            synchronized(lock) {
                rotateIfNeeded(f)
                val sb = StringBuilder()
                sb.append(tsFmt.format(Date())).append(' ')
                    .append(level).append('/').append(tag).append(": ").append(msg).append('\n')
                if (t != null) sb.append(t.stackTraceToString()).append('\n')
                f.appendText(sb.toString(), Charsets.UTF_8)
            }
        } catch (_: Exception) {
            // 日志本身绝不能再抛异常
        }
    }

    private fun rotateIfNeeded(f: File) {
        try {
            if (f.exists() && f.length() > MAX_BYTES) {
                val bak = File(f.parent, "xtracker.log.1")
                if (bak.exists()) bak.delete()
                f.renameTo(bak)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 全局未捕获异常：先把堆栈写进日志文件（给闪退留证据），
     * 再交给系统默认处理器走正常崩溃流程。
     */
    fun installUncaughtHandler() {
        val def = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                e("FATAL", "uncaught in thread ${thread.name}", ex)
                Thread.sleep(300) // 给写盘一点时间
            } catch (_: Exception) {
            }
            def?.uncaughtException(thread, ex)
        }
    }
}

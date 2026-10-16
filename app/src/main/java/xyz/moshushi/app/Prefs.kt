package xyz.moshushi.app

import android.content.Context

/** 简单设置：用户名、目标包名、最近一次节点树导出路径 */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("xtracker", Context.MODE_PRIVATE)

    var handle: String
        get() = sp.getString("handle", "@idsahm") ?: "@idsahm"
        set(v) { sp.edit().putString("handle", v.trim()).apply() }

    var targetPackage: String
        get() = sp.getString("pkg", "com.twitter.android") ?: "com.twitter.android"
        set(v) { sp.edit().putString("pkg", v.trim()).apply() }

    var lastDumpPath: String?
        get() = sp.getString("dump", null)
        set(v) { sp.edit().putString("dump", v).apply() }

    /** 点选校准：粉丝数节点的指纹（类名|viewId|锚点文本） */
    var calibFollowers: String?
        get() = sp.getString("calib_followers", null)
        set(v) { sp.edit().putString("calib_followers", v).apply() }

    /** 点选校准：关注数节点的指纹 */
    var calibFollowing: String?
        get() = sp.getString("calib_following", null)
        set(v) { sp.edit().putString("calib_following", v).apply() }

    fun clearCalibration() {
        sp.edit().remove("calib_followers").remove("calib_following").apply()
    }

    /** 悬浮窗最近一次错误（null = 正常），显示在设置页 */
    var overlayError: String?
        get() = sp.getString("overlay_error", null)
        set(v) { sp.edit().putString("overlay_error", v).apply() }
}

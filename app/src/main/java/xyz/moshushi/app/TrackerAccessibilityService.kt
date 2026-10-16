package xyz.moshushi.app

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import xyz.moshushi.app.data.DbHelper
import xyz.moshushi.app.data.StatSnapshot
import xyz.moshushi.app.data.UsageSession
import xyz.moshushi.app.util.Channels
import xyz.moshushi.app.util.fmtDateTime
import xyz.moshushi.app.util.pushNotification
import xyz.moshushi.app.util.todayStr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 核心服务：
 * 1. 监听目标应用（默认 X / com.twitter.android）的前后台切换，记录打开/关闭与使用时长
 * 2. 当目标应用在前台且屏幕内容包含用户主页时，解析粉丝数/关注数/推文数并存快照
 * 3. 响应 DUMP_TREE 广播，导出当前屏幕节点树供调试校准
 *
 * 所有数据只写本机 SQLite，不申请联网权限。
 */
class TrackerAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_DUMP = "xyz.moshushi.app.DUMP_TREE"
        const val ACTION_DUMP_DONE = "xyz.moshushi.app.DUMP_DONE"
        const val ACTION_TEST_BALL = "xyz.moshushi.app.TEST_BALL"
        const val EXTRA_PATH = "path"
        private const val PARSE_THROTTLE_MS = 5000L
        private const val SNAPSHOT_MIN_INTERVAL_MS = 30 * 60 * 1000L
    }

    private lateinit var db: DbHelper
    internal lateinit var prefs: Prefs
    private lateinit var overlay: OverlayManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var xForeground = false
    private var currentSessionId = -1L
    private var lastParseAt = 0L
    private var lastRetryAt = 0L
    // 关闭防抖：短暂切走（如键盘、通知栏）10 秒内回来算同一次使用
    private var pendingCloseJob: Job? = null
    // 调试抓取模式：用户点了"导出节点树"后，等他打开 X 主页时自动抓取
    private var pendingDump = false
    private var pendingDumpAt = 0L

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // 锁屏视为本次使用结束：立即关闭，不防抖
            pendingCloseJob?.cancel()
            pendingCloseJob = null
            closeSession()
            xForeground = false
        }
    }

    private val dumpReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // 不立即抓：用户此时正在本 App 里，抓到的会是本 App 的界面。
            // 进入待命模式，等用户打开 X 个人主页后自动抓取（10 分钟内有效）。
            pendingDump = true
            pendingDumpAt = System.currentTimeMillis()
            pushNotification(
                this@TrackerAccessibilityService, Channels.REMINDER,
                getString(R.string.dump_ready_title), getString(R.string.dump_ready_text), 9002
            )
        }
    }

    private val testBallReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // 设置页里的"测试悬浮球"：直接尝试显示，不经过 X 前台判断
            overlay.testBall()
        }
    }

    private fun registerCompat(r: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(r, f)
        }
    }

    override fun onServiceConnected() {
        xyz.moshushi.app.util.LogFile.init(this)
        xyz.moshushi.app.util.LogFile.d("Svc", "onServiceConnected")
        db = DbHelper.get(this)
        prefs = Prefs(this)
        overlay = OverlayManager(this)
        registerCompat(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        registerCompat(dumpReceiver, IntentFilter(ACTION_DUMP))
        registerCompat(testBallReceiver, IntentFilter(ACTION_TEST_BALL))
        // 修复上次服务被杀/升级时没关闭的孤儿会话
        scope.launch { db.closeOrphanSessions(System.currentTimeMillis()) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowState(event)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (xForeground && event.packageName?.toString() == prefs.targetPackage) {
                    throttledParse()
                }
            }
        }
    }

    private fun onWindowState(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val target = prefs.targetPackage
        if (pkg == target) {
            if (pendingCloseJob != null) {
                // 10 秒内回来：视为同一次使用，取消关闭
                pendingCloseJob?.cancel()
                pendingCloseJob = null
                xForeground = true
            } else if (!xForeground) {
                xForeground = true
                scope.launch {
                    currentSessionId = db.insertSession(
                        UsageSession(
                            date = todayStr(),
                            openedAt = System.currentTimeMillis(),
                            closedAt = null,
                            durationSec = 0
                        )
                    )
                }
            }
            overlay.setXForeground(true)
            throttledParse()
        } else if (xForeground) {
            // 非目标窗口出现：不立即关闭，10 秒防抖（键盘/通知栏抖动会被合并）
            xForeground = false
            overlay.setXForeground(false)
            pendingCloseJob?.cancel()
            pendingCloseJob = scope.launch {
                delay(10000)
                pendingCloseJob = null
                closeSession()
            }
        }
    }

    private fun closeSession() {
        val id = currentSessionId
        currentSessionId = -1L
        if (id <= 0) return
        val now = System.currentTimeMillis()
        scope.launch {
            val s = db.sessionById(id) ?: return@launch
            val dur = maxOf(0L, (now - s.openedAt) / 1000)
            // 小于 5 秒的抖动会话直接丢弃，不入库
            if (dur < 5) db.deleteSession(id) else db.closeSession(id, now, dur)
        }
    }

    // ---------- 主页数据解析 ----------

    private fun throttledParse() {
        val now = System.currentTimeMillis()
        if (now - lastParseAt < PARSE_THROTTLE_MS) return
        lastParseAt = now
        val root = xRoot() ?: return
        scope.launch {
            val texts = mutableListOf<String>()
            collectTexts(root, texts, 0)
            val joined = texts.joinToString("\n")
            // 自己的主页："@handle" 或 "编辑个人资料"（仅自己主页有）出现即算
            val ownProfile = joined.contains(prefs.handle, ignoreCase = true) ||
                    joined.contains("编辑个人资料")
            if (!ownProfile) {
                // 树太小可能是 X 页面还没加载完：给一次延迟重试的机会
                if (texts.size < 25) scheduleParseRetry(now)
                return@launch
            }

            // 调试抓取模式：主页已出现，自动抓取节点树
            if (pendingDump) {
                if (now - pendingDumpAt > 10 * 60 * 1000) {
                    pendingDump = false // 超时过期
                } else {
                    pendingDump = false
                    dumpTree()
                }
            }

            val followers = findCalibrated(root, prefs.calibFollowers)
                ?: findStat(texts, listOf("关注者", "粉丝"))
            val following = findCalibrated(root, prefs.calibFollowing)
                ?: findStat(texts, listOf("正在关注"))
            val posts = findStat(texts, listOf("帖子", "推文", "个帖子"))
            if (followers == null && following == null && posts == null) {
                // 主页认出来了但数字还没渲染（X 懒加载）：4 秒后重试一次
                scheduleParseRetry(now)
                return@launch
            }

            val last = db.lastSnapshot()
            // X 是懒加载，有时某行数字还没渲染出来：用上一次快照的值补全空字段
            val merged = StatSnapshot(
                timestamp = now,
                followers = followers ?: last?.followers,
                following = following ?: last?.following,
                posts = posts ?: last?.posts,
                raw = joined.take(600)
            )
            val changed = last == null ||
                    last.followers != merged.followers ||
                    last.following != merged.following ||
                    last.posts != merged.posts
            val stale = last == null || now - last.timestamp > SNAPSHOT_MIN_INTERVAL_MS
            if (changed || stale) {
                db.insertSnapshot(merged)
            }
        }
    }

    /**
     * 解析失败/页面未加载完时，4 秒后重试一次（30 秒内最多一次），
     * 解决 X 懒加载导致第一次抓到的树是空的。
     */
    private fun scheduleParseRetry(now: Long) {
        if (now - lastRetryAt < 30000) return
        lastRetryAt = now
        scope.launch {
            delay(4000)
            if (xForeground) {
                lastParseAt = 0
                throttledParse()
            }
        }
    }

    private fun collectTexts(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 15 || out.size > 4000) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            collectTexts(node.getChild(i), out, depth + 1)
        }
    }

    /**
     * 在文本列表里找 "数字 + 标签"。
     * X 用 Compose 渲染主页，数字和标签是两个独立 TextView（如 "686" + "关注者"），
     * 不能用整段正则（会把"加入于 一月 2024"里的 2024 误吞成 2024497）。
     * 策略：定位标签文本，往前找最近的可解析数字；也兼容同一文本内的 "686 关注者"。
     */
    private fun findStat(texts: List<String>, labels: List<String>): Int? {
        for (i in texts.indices) {
            val t = texts[i].trim()
            val label = labels.firstOrNull { t == it || (t.length > it.length && t.endsWith(it)) }
                ?: continue
            // 情况1：数字和标签在同一文本里，如 "686 关注者"
            if (t.length > label.length) {
                parseNumber(t.dropLast(label.length).trim())?.let { return it }
            }
            // 情况2：数字在标签前面的独立文本里
            for (j in i - 1 downTo maxOf(0, i - 3)) {
                parseNumber(texts[j].trim())?.let { return it }
            }
        }
        return null
    }

    /**
     * 从字符串开头提取数字，兼容 "3,095 个帖子"、"686"、"8k"、"500万"。
     * 只取开头，避免把 "加入于 一月 2024" 这种整段吞进去。
     */
    private fun parseNumber(raw: String): Int? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val m = Regex("""^([\d\s,.，]+)\s*([万KkMm]?)""").find(s) ?: return null
        val num = m.groupValues[1].replace(",", "").replace("，", "").replace(" ", "").trim()
        if (num.isEmpty()) return null
        val mult = when (m.groupValues[2]) {
            "万" -> 10000.0
            "K", "k" -> 1000.0
            "M", "m" -> 1000000.0
            else -> 1.0
        }
        return num.toDoubleOrNull()?.let { (it * mult).toInt() }
    }

    /**
     * 取 X 应用的窗口根节点：遍历无障碍 windows，明确选择目标包名的窗口，
     * 而不是只依赖 rootInActiveWindow（点选/悬浮层自身可能抢占 active window）。
     * 找不到则回退到 rootInActiveWindow。
     */
    private fun xRoot(): AccessibilityNodeInfo? {
        try {
            for (w in windows) {
                val r = w.root ?: continue
                if (r.packageName?.toString() == prefs.targetPackage) return r
            }
        } catch (_: Exception) {
        }
        return rootInActiveWindow
    }

    // ---------- 悬浮窗：点选定位 ----------

    /** 悬浮球菜单：立即抓取当前 X 界面 */
    fun dumpNow() {
        scope.launch { dumpTree() }
    }

    /** 点选模式：在 (x, y) 处找到最深层的节点，弹出信息卡 */
    fun inspectAt(x: Float, y: Float) {
        val root = xRoot() ?: return
        val node = findDeepestAt(root, x.toInt(), y.toInt()) ?: return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        overlay.showInfoCard(
            NodeInfo(
                className = node.className?.toString() ?: "",
                text = node.text?.toString() ?: "",
                viewId = node.viewIdResourceName ?: "",
                bounds = bounds,
                fingerprint = fingerprintOf(node)
            )
        )
    }

    private fun findDeepestAt(node: AccessibilityNodeInfo?, x: Int, y: Int): AccessibilityNodeInfo? {
        if (node == null) return null
        val r = Rect()
        node.getBoundsInScreen(r)
        if (!r.contains(x, y)) return null
        for (i in 0 until node.childCount) {
            val f = findDeepestAt(node.getChild(i), x, y)
            if (f != null) return f
        }
        return node
    }

    /**
     * 节点指纹：类名|viewId|锚点兄弟文本。
     * 数字本身会变，所以用旁边的非数字兄弟文本（如"关注者"）做锚点。
     */
    private fun fingerprintOf(node: AccessibilityNodeInfo): String {
        var anchor = ""
        val parent = node.parent
        if (parent != null) {
            for (i in 0 until parent.childCount) {
                val t = parent.getChild(i)?.text?.toString()?.trim() ?: ""
                if (t.isNotEmpty() && parseNumber(t) == null) {
                    anchor = t
                    break
                }
            }
        }
        return "${node.className}|${node.viewIdResourceName}|$anchor"
    }

    /** 用户点选校准：role = followers / following */
    fun setCalibration(role: String, fingerprint: String) {
        if (role == "followers") prefs.calibFollowers = fingerprint
        else prefs.calibFollowing = fingerprint
    }

    /** 按校准指纹找数字节点，找不到返回 null（走通用逻辑） */
    private fun findCalibrated(root: AccessibilityNodeInfo?, fp: String?): Int? {
        if (root == null || fp.isNullOrEmpty()) return null
        val parts = fp.split("|")
        if (parts.size < 3) return null
        val (cls, viewId, anchor) = parts
        var result: Int? = null
        fun dfs(n: AccessibilityNodeInfo?) {
            if (n == null || result != null) return
            val nCls = n.className?.toString() ?: ""
            val nId = n.viewIdResourceName ?: "null"
            if (nCls == cls && nId == viewId && anchor.isNotEmpty()) {
                val p = n.parent
                var hasAnchor = false
                if (p != null) {
                    for (i in 0 until p.childCount) {
                        if (p.getChild(i)?.text?.toString()?.trim() == anchor) {
                            hasAnchor = true
                            break
                        }
                    }
                }
                if (hasAnchor) {
                    parseNumber(n.text?.toString()?.trim() ?: "")?.let {
                        result = it
                        return
                    }
                }
            }
            for (i in 0 until n.childCount) dfs(n.getChild(i))
        }
        dfs(root)
        return result
    }

    // ---------- 节点树导出（调试校准用） ----------

    private suspend fun dumpTree() {
        val root = xRoot() ?: return
        val sb = StringBuilder()
        sb.append("package=").append(root.packageName).append("\n")
        sb.append("dumped_at=").append(fmtDateTime(System.currentTimeMillis())).append("\n\n")
        dumpNode(root, sb, 0)
        val dir = getExternalFilesDir(null) ?: filesDir
        val f = File(dir, "node-dump-${System.currentTimeMillis()}.txt")
        f.writeText(sb.toString())
        prefs.lastDumpPath = f.absolutePath
        sendBroadcast(Intent(ACTION_DUMP_DONE).setPackage(packageName).putExtra(EXTRA_PATH, f.absolutePath))
        pushNotification(this, Channels.REMINDER, getString(R.string.dump_exported), f.absolutePath, 9001)
    }

    private fun dumpNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 12) return
        sb.append("  ".repeat(depth))
            .append(node.className)
            .append(" text=").append(node.text)
            .append(" desc=").append(node.contentDescription)
            .append(" id=").append(node.viewIdResourceName)
            .append("\n")
        for (i in 0 until node.childCount) {
            dumpNode(node.getChild(i), sb, depth + 1)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        pendingCloseJob?.cancel()
        pendingCloseJob = null
        closeSession()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenOffReceiver)
            unregisterReceiver(dumpReceiver)
            unregisterReceiver(testBallReceiver)
        } catch (_: Exception) {
        }
        pendingCloseJob?.cancel()
        pendingCloseJob = null
        try {
            overlay.onDestroy()
        } catch (_: Exception) {
        }
        scope.cancel()
        super.onDestroy()
    }
}

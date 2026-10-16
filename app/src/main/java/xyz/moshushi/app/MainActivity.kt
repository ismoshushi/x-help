package xyz.moshushi.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import xyz.moshushi.app.data.DbHelper
import xyz.moshushi.app.util.LogFile
import xyz.moshushi.app.util.dayBounds
import xyz.moshushi.app.util.fmtDateTime
import xyz.moshushi.app.util.fmtDelta
import xyz.moshushi.app.util.fmtDuration
import xyz.moshushi.app.util.fmtTime
import xyz.moshushi.app.util.todayStr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : Activity() {

    private lateinit var db: DbHelper
    private lateinit var prefs: Prefs
    private val scope: CoroutineScope = MainScope()

    private lateinit var tvServiceStatus: TextView
    private lateinit var btnEnableService: Button
    private lateinit var tvTodayBig: TextView
    private lateinit var tvTodayMeta: TextView
    private lateinit var hourBars: LinearLayout
    private lateinit var tvSessions: TextView
    private lateinit var tvFollowersNum: TextView
    private lateinit var tvFollowingNum: TextView
    private lateinit var tvPostsNum: TextView
    private lateinit var tvSnapTime: TextView
    private lateinit var tvDelta: TextView
    private lateinit var tvWeek: TextView
    private lateinit var etHandle: EditText
    private lateinit var etPackage: EditText
    private lateinit var btnSaveSettings: Button
    private lateinit var btnBattery: Button
    private lateinit var btnAlarm: Button
    private lateinit var tvAlarmStatus: TextView
    private lateinit var btnExport: Button
    private lateinit var btnDump: Button
    private lateinit var tvDumpPath: TextView
    private lateinit var btnShareDump: Button
    private lateinit var btnExportLog: Button
    private lateinit var btnResetDay: Button
    private lateinit var tvCalib: TextView
    private lateinit var btnClearCalib: Button
    private lateinit var btnAbout: Button
    private lateinit var tvVersion: TextView
    private lateinit var pageView: ScrollView
    private lateinit var pageSettings: ScrollView
    private lateinit var tabStats: LinearLayout
    private lateinit var tabSettings: LinearLayout
    private lateinit var tabStatsIcon: TextView
    private lateinit var tabStatsLabel: TextView
    private lateinit var tabSettingsIcon: TextView
    private lateinit var tabSettingsLabel: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var btnTestBall: Button
    private lateinit var btnOverlayPerm: Button

    /** 时长格式化走 Context 扩展（中英双语），类内统一用这个别名 */
    private fun fd(sec: Long): String = fmtDuration(sec)

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private val dumpDoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val path = i.getStringExtra(TrackerAccessibilityService.EXTRA_PATH)
            if (path != null) {
                tvDumpPath.text = getString(R.string.dump_exported_to, path)
                Toast.makeText(c, getString(R.string.dump_exported), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogFile.init(this)
        LogFile.d("Main", "onCreate start")
        setContentView(R.layout.activity_main)
        LogFile.d("Main", "setContentView ok")
        // 注意：必须在 setContentView 之后调用，insetsController 需要 DecorView 已安装，
        // 否则 PhoneWindow.getInsetsController() 内部 NPE（v1.4 闪退根因）
        setupEdgeToEdge()
        db = DbHelper.get(this)
        LogFile.d("Main", "db ok")
        prefs = Prefs(this)
        bindViews()
        LogFile.d("Main", "bindViews ok")

        etHandle.setText(prefs.handle)
        etPackage.setText(prefs.targetPackage)
        tvVersion.text = getString(R.string.about_version, appVersion())

        btnEnableService.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnSaveSettings.setOnClickListener {
            prefs.handle = etHandle.text.toString().ifBlank { "@idsahm" }
            prefs.targetPackage = etPackage.text.toString().ifBlank { "com.twitter.android" }
            Toast.makeText(this, getString(R.string.saved), Toast.LENGTH_SHORT).show()
        }
        btnBattery.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.battery_manual), Toast.LENGTH_LONG).show()
            }
        }
        btnAlarm.setOnClickListener {
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:$packageName")
                        )
                    )
                } else {
                    Toast.makeText(this, getString(R.string.alarm_not_needed), Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.alarm_manual), Toast.LENGTH_LONG).show()
            }
        }
        btnExport.setOnClickListener { scope.launch { exportCsv() } }
        btnDump.setOnClickListener {
            if (!isServiceEnabled()) {
                Toast.makeText(this, getString(R.string.service_not_on), Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            sendBroadcast(Intent(TrackerAccessibilityService.ACTION_DUMP).setPackage(packageName))
            Toast.makeText(this, getString(R.string.dump_ready), Toast.LENGTH_LONG).show()
        }
        btnShareDump.setOnClickListener { scope.launch { shareDump() } }
        btnExportLog.setOnClickListener { showLogDialog() }
        btnResetDay.setOnClickListener { confirmResetDay() }
        btnClearCalib.setOnClickListener {
            prefs.clearCalibration()
            Toast.makeText(this, getString(R.string.calib_cleared), Toast.LENGTH_SHORT).show()
            refresh()
        }
        btnAbout.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://x.com/idsahm")))
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.link_fail), Toast.LENGTH_SHORT).show()
            }
        }
        tabStats.setOnClickListener { switchTab(true) }
        tabSettings.setOnClickListener { switchTab(false) }
        btnTestBall.setOnClickListener {
            if (!isServiceEnabled()) {
                Toast.makeText(this, getString(R.string.service_not_on), Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            sendBroadcast(Intent(TrackerAccessibilityService.ACTION_TEST_BALL).setPackage(packageName))
            Toast.makeText(this, getString(R.string.testing_ball), Toast.LENGTH_SHORT).show()
        }
        btnOverlayPerm.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.overlay_manual), Toast.LENGTH_LONG).show()
            }
        }

        requestNotificationPermission()
        registerCompat(dumpDoneReceiver, IntentFilter(TrackerAccessibilityService.ACTION_DUMP_DONE))
        LogFile.d("Main", "onCreate end")
    }

    private fun appVersion(): String =
        try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            pi.versionName ?: ""
        } catch (_: Exception) {
            ""
        }

    /** 重置今日记录：二次确认后清除今天的会话、快照和日报 */
    private fun confirmResetDay() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_confirm_title)
            .setMessage(R.string.reset_confirm_msg)
            .setPositiveButton(R.string.btn_reset_day) { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) { db.clearDay(todayStr()) }
                    Toast.makeText(this@MainActivity, getString(R.string.reset_done), Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** 全面屏适配：内容绘制到系统栏下方，用 inset 避开状态栏和手势条 */
    private fun setupEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            try {
                window.insetsController?.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } catch (e: Exception) {
                // 某些 ROM 上 insetsController 仍可能取不到，只记日志不崩
                LogFile.e("Main", "insetsController failed", e)
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    private fun applyInsets() {
        val root = findViewById<LinearLayout>(R.id.rootLayout)
        val bottomNav = findViewById<LinearLayout>(R.id.bottomNav)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val statusTop: Int
            val navBottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val t = insets.getInsets(WindowInsets.Type.systemBars())
                statusTop = t.top
                navBottom = t.bottom
            } else {
                @Suppress("DEPRECATION")
                statusTop = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                navBottom = insets.systemWindowInsetBottom
            }
            root.setPadding(0, statusTop, 0, 0)
            bottomNav.setPadding(0, 0, 0, navBottom)
            insets
        }
        root.requestApplyInsets()
    }

    override fun onResume() {
        super.onResume()
        applyInsets()
        refresh()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(dumpDoneReceiver)
        } catch (_: Exception) {
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun registerCompat(r: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(r, f)
        }
    }

    private fun bindViews() {
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        btnEnableService = findViewById(R.id.btnEnableService)
        tvTodayBig = findViewById(R.id.tvTodayBig)
        tvTodayMeta = findViewById(R.id.tvTodayMeta)
        hourBars = findViewById(R.id.hourBars)
        tvSessions = findViewById(R.id.tvSessions)
        tvFollowersNum = findViewById(R.id.tvFollowersNum)
        tvFollowingNum = findViewById(R.id.tvFollowingNum)
        tvPostsNum = findViewById(R.id.tvPostsNum)
        tvSnapTime = findViewById(R.id.tvSnapTime)
        tvDelta = findViewById(R.id.tvDelta)
        tvWeek = findViewById(R.id.tvWeek)
        etHandle = findViewById(R.id.etHandle)
        etPackage = findViewById(R.id.etPackage)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)
        btnBattery = findViewById(R.id.btnBattery)
        btnAlarm = findViewById(R.id.btnAlarm)
        tvAlarmStatus = findViewById(R.id.tvAlarmStatus)
        btnExport = findViewById(R.id.btnExport)
        btnDump = findViewById(R.id.btnDump)
        tvDumpPath = findViewById(R.id.tvDumpPath)
        btnShareDump = findViewById(R.id.btnShareDump)
        btnExportLog = findViewById(R.id.btnExportLog)
        btnResetDay = findViewById(R.id.btnResetDay)
        tvCalib = findViewById(R.id.tvCalib)
        btnClearCalib = findViewById(R.id.btnClearCalib)
        btnAbout = findViewById(R.id.btnAbout)
        tvVersion = findViewById(R.id.tvVersion)
        pageView = findViewById(R.id.pageView)
        pageSettings = findViewById(R.id.pageSettings)
        tabStats = findViewById(R.id.tabStats)
        tabSettings = findViewById(R.id.tabSettings)
        tabStatsIcon = findViewById(R.id.tabStatsIcon)
        tabStatsLabel = findViewById(R.id.tabStatsLabel)
        tabSettingsIcon = findViewById(R.id.tabSettingsIcon)
        tabSettingsLabel = findViewById(R.id.tabSettingsLabel)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        btnTestBall = findViewById(R.id.btnTestBall)
        btnOverlayPerm = findViewById(R.id.btnOverlayPerm)
    }

    private fun refresh() {
        val enabled = isServiceEnabled()
        tvServiceStatus.text =
            if (enabled) getString(R.string.status_on)
            else getString(R.string.status_off)
        // 开启后就不需要这个按钮了，隐藏
        btnEnableService.visibility = if (enabled) View.GONE else View.VISIBLE
        tvAlarmStatus.text =
            if (AlarmScheduler.canExact(this)) getString(R.string.alarm_ok)
            else getString(R.string.alarm_missing)
        scope.launch {
            try {
                LogFile.d("Main", "refresh start")
            val date = todayStr()
            val now = System.currentTimeMillis()

            val sessions = withContext(Dispatchers.IO) { db.sessionsOf(date) }
            // 单会话 6 小时封顶，防止脏数据炸总数
            fun sessSec(s: xyz.moshushi.app.data.UsageSession): Long {
                val raw = if (s.closedAt != null) s.durationSec else maxOf(0L, (now - s.openedAt) / 1000)
                return minOf(raw, 21600L)
            }
            val totalSec = sessions.sumOf { sessSec(it) }
            val opens = sessions.size
            tvTodayBig.text = fd(totalSec)
            val avg = if (opens > 0) totalSec / opens else 0
            val ongoing = getString(R.string.ongoing)
            val first = sessions.minOfOrNull { it.openedAt }?.let { fmtTime(it) } ?: "—"
            val lastT = sessions.mapNotNull { it.closedAt }.maxOrNull()?.let { fmtTime(it) }
                ?: sessions.maxOfOrNull { it.openedAt }?.let { fmtTime(it) + "（$ongoing）" } ?: "—"
            tvTodayMeta.text = getString(R.string.today_meta, opens, fd(avg), first, lastT)
            renderHourBars(computeHourMinutes(sessions, now))
            tvSessions.text = sessions.takeLast(8).reversed().joinToString("\n") { s ->
                val dur = if (s.closedAt != null) s.durationSec else maxOf(0L, (now - s.openedAt) / 1000)
                val close = s.closedAt?.let { fmtTime(it) } ?: ongoing
                getString(R.string.session_row, fmtTime(s.openedAt), close, fd(dur))
            }.ifBlank { getString(R.string.no_sessions) }

            val (start, end) = dayBounds(date)
            val snaps = withContext(Dispatchers.IO) { db.snapshotsBetween(start, end) }
            val last = withContext(Dispatchers.IO) { db.lastSnapshot() }
            if (last != null) {
                tvFollowersNum.text = last.followers?.toString() ?: "—"
                tvFollowingNum.text = last.following?.toString() ?: "—"
                tvPostsNum.text = last.posts?.toString() ?: "—"
                tvSnapTime.text = getString(R.string.snap_updated, fmtDateTime(last.timestamp))
            } else {
                tvFollowersNum.text = "—"
                tvFollowingNum.text = "—"
                tvPostsNum.text = "—"
                tvSnapTime.text = getString(R.string.no_snapshot)
            }
            tvDelta.text = if (snaps.isNotEmpty()) {
                val f = snaps.first()
                val l = snaps.last()
                buildString {
                    if (f.posts != null && l.posts != null) {
                        // 删帖会导致差值为负，钳到 0，不展示负的"发布数"
                        val d = maxOf(0, l.posts - f.posts)
                        append(getString(R.string.today_posts, d))
                    }
                    if (f.followers != null && l.followers != null)
                        append(getString(R.string.today_followers, f.followers, l.followers, fmtDelta(l.followers - f.followers)))
                }.ifBlank { getString(R.string.snap_single) }
            } else getString(R.string.no_snapshot_today)

            val week = withContext(Dispatchers.IO) { db.recentSummaries(7) }
            tvWeek.text = week.joinToString("\n") { s ->
                buildString {
                    append(getString(R.string.week_row, s.date, s.opens, fd(s.totalSec)))
                    if (s.followersStart != null && s.followersEnd != null)
                        append(getString(R.string.week_followers, fmtDelta(s.followersEnd - s.followersStart)))
                    if (s.postsStart != null && s.postsEnd != null) {
                        val d = s.postsEnd - s.postsStart
                        if (d != 0) append(getString(R.string.week_posts, fmtDelta(d)))
                    }
                }
            }.ifBlank { getString(R.string.no_week) }

            val dump = prefs.lastDumpPath
            tvDumpPath.text = if (dump != null) getString(R.string.last_dump, dump) else ""
            tvCalib.text = buildString {
                append(getString(R.string.calib_label))
                append(getString(if (prefs.calibFollowers != null) R.string.calib_f_on else R.string.calib_f_off))
                append(getString(if (prefs.calibFollowing != null) R.string.calib_g_on else R.string.calib_g_off))
            }
            val overlayErr = prefs.overlayError
            tvOverlayStatus.text =
                if (overlayErr != null) getString(R.string.overlay_err, overlayErr)
                else getString(R.string.overlay_ok)
                LogFile.d("Main", "refresh end")
            } catch (e: Exception) {
                LogFile.e("Main", "refresh failed", e)
            }
        }
    }

    /** 底部导航切换（M3 Expressive：胶囊选中指示） */
    private fun switchTab(toStats: Boolean) {
        pageView.visibility = if (toStats) View.VISIBLE else View.GONE
        pageSettings.visibility = if (toStats) View.GONE else View.VISIBLE
        val blue = getColor(R.color.x_blue)
        val darkBlue = getColor(R.color.x_blue_dark)
        val gray = getColor(R.color.x_text2)
        tabStatsIcon.setTextColor(if (toStats) darkBlue else gray)
        tabStatsLabel.setTextColor(if (toStats) blue else gray)
        tabSettingsIcon.setTextColor(if (toStats) gray else darkBlue)
        tabSettingsLabel.setTextColor(if (toStats) gray else blue)
        tabStatsIcon.setBackgroundResource(if (toStats) R.drawable.tab_pill else 0)
        tabSettingsIcon.setBackgroundResource(if (toStats) 0 else R.drawable.tab_pill)
        tabStatsLabel.paint.isFakeBoldText = toStats
        tabSettingsLabel.paint.isFakeBoldText = !toStats
    }

    private fun isServiceEnabled(): Boolean {
        val enabled =
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
        return enabled.split(":").any {
            it.equals("$packageName/${TrackerAccessibilityService::class.java.name}", ignoreCase = true) ||
                    it.endsWith(".TrackerAccessibilityService")
        }
    }

    private suspend fun exportCsv() {
        try {
            val sb = StringBuilder()
            sb.append("日报\n日期,打开次数,总时长(秒),粉丝开始,粉丝结束,推文开始,推文结束\n")
            withContext(Dispatchers.IO) { db.recentSummaries(365) }.reversed().forEach { s ->
                sb.append("${s.date},${s.opens},${s.totalSec},${s.followersStart ?: ""},${s.followersEnd ?: ""},${s.postsStart ?: ""},${s.postsEnd ?: ""}\n")
            }
            sb.append("\n今日会话\n打开时间,关闭时间,时长\n")
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) { db.sessionsOf(todayStr()) }.forEach { s ->
                val dur = if (s.closedAt != null) s.durationSec else maxOf(0L, (now - s.openedAt) / 1000)
                sb.append("${fmtDateTime(s.openedAt)},${s.closedAt?.let { fmtDateTime(it) } ?: getString(R.string.ongoing)},${fd(dur)}\n")
            }
            val name = "xtracker-${todayStr()}.csv"
            val uri = insertDownload(name, "text/csv")
                ?: throw IllegalStateException(getString(R.string.file_create_fail))
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)?.use {
                    it.write(sb.toString().toByteArray(Charsets.UTF_8))
                }
            }
            Toast.makeText(this, getString(R.string.export_done, name), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.export_fail, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun insertDownload(name: String, mime: String): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/XTracker")
        }
        return contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }

    private suspend fun shareDump() {
        val path = prefs.lastDumpPath
        if (path == null) {
            Toast.makeText(this, getString(R.string.no_dump), Toast.LENGTH_SHORT).show()
            return
        }
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, getString(R.string.dump_gone), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = withContext(Dispatchers.IO) {
                val u = insertDownload(file.name, "text/plain")
                    ?: throw IllegalStateException(getString(R.string.share_unsupported))
                contentResolver.openOutputStream(u)?.use { it.write(file.readBytes()) }
                u
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.share_fail, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    /** 运行日志：弹窗显示路径，可复制路径或一键分享给开发者 */
    private fun showLogDialog() {
        LogFile.init(this)
        if (!LogFile.hasLog()) {
            Toast.makeText(this, getString(R.string.log_no_file), Toast.LENGTH_SHORT).show()
            return
        }
        val path = LogFile.userVisiblePath()
        AlertDialog.Builder(this)
            .setTitle(R.string.log_dialog_title)
            .setMessage(getString(R.string.log_path_label) + "\n" + path)
            .setPositiveButton(R.string.btn_share_log) { _, _ -> shareLog() }
            .setNeutralButton(R.string.btn_copy_path) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("log", path))
                Toast.makeText(this, getString(R.string.log_copied), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun shareLog() {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, LogShareProvider.URI)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.log_share_title)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.share_fail, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    /** 24 小时分布：把会话按小时切分，统计每分钟数（供柱状图渲染） */
    private fun computeHourMinutes(sessions: List<xyz.moshushi.app.data.UsageSession>, now: Long): IntArray {
        val hours = IntArray(24)
        for (s in sessions) {
            var t = s.openedAt
            val end = minOf(s.closedAt ?: now, now)
            var guard = 0
            while (t < end && guard++ < 48) {
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = t }
                val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
                cal.set(java.util.Calendar.MINUTE, 59)
                cal.set(java.util.Calendar.SECOND, 59)
                cal.set(java.util.Calendar.MILLISECOND, 999)
                val hourEnd = minOf(cal.timeInMillis, end)
                hours[h] += ((hourEnd - t) / 60000).toInt().coerceAtLeast(1)
                t = hourEnd + 1
            }
        }
        return hours
    }

    /** M3 Expressive 风格圆角柱状：主色 bars + 浅色 track，当前小时高亮 */
    private fun renderHourBars(hours: IntArray) {
        hourBars.removeAllViews()
        val max = (hours.maxOrNull() ?: 0).coerceAtLeast(1)
        val nowHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val blue = getColor(R.color.x_blue)
        val darkBlue = getColor(R.color.x_blue_dark)
        for (h in 0 until 24) {
            val frac = hours[h].toFloat() / max
            val heightDp = (6 + frac * 58).toInt()
            val isNow = h == nowHour
            val bar = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(6).toFloat()
                    setColor(if (isNow) darkBlue else blue)
                    alpha = if (isNow) 255 else 190
                }
            }
            val lp = LinearLayout.LayoutParams(0, dp(heightDp), 1f)
            if (h < 23) lp.marginEnd = dp(3)
            bar.layoutParams = lp
            hourBars.addView(bar)
        }
    }
}

package xyz.moshushi.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** 点选到的节点信息 */
data class NodeInfo(
    val className: String,
    val text: String,
    val viewId: String,
    val bounds: Rect,
    val fingerprint: String
)

/**
 * 悬浮窗管理器（v3.0）。
 * 优先用 TYPE_ACCESSIBILITY_OVERLAY（无障碍服务专用通道，无需额外权限）；
 * 若被手机系统拦截，兜底 TYPE_APPLICATION_OVERLAY（需"显示在其他应用上层"权限）。
 * 所有失败都会记到 prefs.overlayError，显示在设置页，不再静默吞掉。
 */
class OverlayManager(private val service: TrackerAccessibilityService) {

    private val wm: WindowManager = service.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var ball: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var ballSuppressed = false
    private var menuView: View? = null
    private var inspectRoot: View? = null
    private var infoCard: View? = null
    private var highlight: View? = null

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), service.resources.displayMetrics)
            .toInt()

    private fun overlayParams(w: Int, h: Int, flags: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )

    private fun toast(msg: String) =
        Toast.makeText(service, msg, Toast.LENGTH_SHORT).show()

    private val xText = Color.parseColor("#0F1419")
    private val xBlue = Color.parseColor("#1D9BF0")

    private fun xButton(label: String): Button =
        Button(service).apply {
            text = label
            setBackgroundResource(R.drawable.btn_outline)
            setTextColor(xBlue)
        }

    // ---------- 悬浮球 ----------

    fun setXForeground(fg: Boolean) {
        mainHandler.post {
            if (fg) {
                if (!ballSuppressed) showBall()
            } else {
                ballSuppressed = false
                hideEverything()
            }
        }
    }

    /** 设置页"测试悬浮球"：直接尝试显示，并上报结果 */
    fun testBall() {
        mainHandler.post {
            ballSuppressed = false
            showBall()
            if (ball != null) {
                toast(service.getString(R.string.ball_shown))
            } else {
                toast(service.getString(R.string.ball_fail, service.prefs.overlayError ?: ""))
            }
        }
    }

    private fun showBall() {
        if (ball != null) return
        val tv = TextView(service).apply {
            text = "🔍"
            textSize = 22f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.ball_bg)
        }
        val p = overlayParams(dp(56), dp(56), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(24)
            y = dp(320)
        }
        tv.setOnTouchListener(BallTouch())
        ball = tv
        ballParams = p
        // 先走无障碍专用通道（无需额外权限）；失败则兜底普通悬浮窗
        if (!tryAdd(tv, service.getString(R.string.channel_a11y), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)) {
            if (!tryAdd(tv, service.getString(R.string.channel_overlay), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)) {
                ball = null
                ballParams = null
            }
        }
    }

    private fun tryAdd(v: View, name: String, type: Int): Boolean {
        return try {
            val wlp = WindowManager.LayoutParams(
                dp(56), dp(56), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(24)
                y = dp(320)
            }
            wm.addView(v, wlp)
            service.prefs.overlayError =
                if (type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) null
                else service.getString(R.string.overlay_fallback)
            true
        } catch (e: Exception) {
            try {
                wm.removeView(v)
            } catch (_: Exception) {
            }
            val hint =
                if (type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY &&
                    !Settings.canDrawOverlays(service)
                ) service.getString(R.string.overlay_perm_hint) else ""
            service.prefs.overlayError = service.getString(R.string.overlay_add_fail, name, hint, e.message ?: "")
            false
        }
    }

    private fun hideBall() {
        removeView(ball)
        ball = null
        ballParams = null
    }

    private inner class BallTouch : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = ballParams?.x ?: 0
                    startY = ballParams?.y ?: 0
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    val slop = dp(10)
                    if (dx * dx + dy * dy > slop * slop) moved = true
                    if (moved) {
                        ballParams?.let {
                            it.x = startX + dx
                            it.y = startY + dy
                            try {
                                wm.updateViewLayout(ball, it)
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleMenu()
                }
            }
            return true
        }
    }

    // ---------- 菜单 ----------

    private fun toggleMenu() {
        if (menuView != null) {
            hideMenu()
            return
        }
        val layout = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        fun btn(label: String, onClick: () -> Unit) {
            xButton(label).apply {
                setOnClickListener { onClick() }
                layout.addView(this)
            }
        }
        btn(service.getString(R.string.menu_dump)) {
            hideMenu()
            service.dumpNow()
            toast(service.getString(R.string.dumping))
        }
        btn(service.getString(R.string.menu_inspect)) {
            hideMenu()
            enterInspect()
        }
        btn(service.getString(R.string.menu_hide)) {
            ballSuppressed = true
            hideEverything()
            toast(service.getString(R.string.ball_hidden))
        }
        val p = overlayParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (ballParams?.x ?: dp(24)) + dp(64)
            y = ballParams?.y ?: dp(320)
        }
        menuView = layout
        try {
            wm.addView(layout, p)
        } catch (_: Exception) {
            menuView = null
        }
    }

    private fun hideMenu() {
        removeView(menuView)
        menuView = null
    }

    // ---------- 点选模式 ----------

    fun enterInspect() {
        if (inspectRoot != null) return
        hideBall() // 点选时先收起球，避免挡住
        val root = FrameLayout(service)
        val touch = View(service).apply {
            setBackgroundColor(Color.parseColor("#22000000"))
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_DOWN) {
                    service.inspectAt(e.rawX, e.rawY)
                    true
                } else {
                    false
                }
            }
        }
        root.addView(
            touch,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val exit = xButton(service.getString(R.string.inspect_exit)).apply {
            setOnClickListener { exitInspect() }
        }
        root.addView(
            exit,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(90)
                marginEnd = dp(16)
            }
        )
        val p = overlayParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        inspectRoot = root
        try {
            wm.addView(root, p)
        } catch (_: Exception) {
            inspectRoot = null
            return
        }
        toast(service.getString(R.string.inspect_hint))
    }

    fun exitInspect() {
        removeView(inspectRoot)
        inspectRoot = null
        hideInfoCard()
        showBall()
    }

    // ---------- 信息卡 ----------

    fun showInfoCard(info: NodeInfo) {
        hideInfoCard()
        val layout = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        fun row(label: String, value: String) {
            TextView(service).apply {
                text = "$label：$value"
                textSize = 13f
                setTextColor(xText)
                layout.addView(this)
            }
        }
        row(service.getString(R.string.info_type), info.className)
        row(service.getString(R.string.info_text), info.text.ifEmpty { service.getString(R.string.empty) }.take(60))
        row(service.getString(R.string.info_id), info.viewId.ifEmpty { service.getString(R.string.none) }.take(60))
        row(service.getString(R.string.info_bounds), info.bounds.flattenToString())
        fun btn(label: String, onClick: () -> Unit) {
            xButton(label).apply {
                setOnClickListener { onClick() }
                layout.addView(this)
            }
        }
        btn(service.getString(R.string.info_copy)) {
            copyToClipboard(info.text)
            toast(service.getString(R.string.copied))
        }
        btn(service.getString(R.string.info_highlight)) {
            hideInfoCard()
            flashHighlight(info.bounds)
        }
        btn(service.getString(R.string.info_calib_f)) {
            service.setCalibration("followers", info.fingerprint)
            toast(service.getString(R.string.calib_f_done))
            hideInfoCard()
        }
        btn(service.getString(R.string.info_calib_g)) {
            service.setCalibration("following", info.fingerprint)
            toast(service.getString(R.string.calib_g_done))
            hideInfoCard()
        }
        btn(service.getString(R.string.close)) { hideInfoCard() }
        val p = overlayParams(
            dp(320),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        ).apply { gravity = Gravity.CENTER }
        infoCard = layout
        try {
            wm.addView(layout, p)
        } catch (_: Exception) {
            infoCard = null
        }
    }

    private fun hideInfoCard() {
        removeView(infoCard)
        infoCard = null
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = service.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("node", text))
        } catch (_: Exception) {
        }
    }

    // ---------- 高亮 ----------

    private fun flashHighlight(r: Rect) {
        val v = object : View(service) {
            private val paint = Paint().apply {
                color = Color.RED
                style = Paint.Style.STROKE
                strokeWidth = 8f
            }
            private val rf = RectF(r)
            override fun onDraw(c: Canvas) {
                super.onDraw(c)
                c.drawRect(rf, paint)
            }
        }
        val p = overlayParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )
        highlight = v
        try {
            wm.addView(v, p)
        } catch (_: Exception) {
            highlight = null
            return
        }
        mainHandler.postDelayed({
            removeView(highlight)
            highlight = null
        }, 2500)
    }

    // ---------- 清理 ----------

    private fun removeView(v: View?) {
        if (v == null) return
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    fun hideEverything() {
        mainHandler.post {
            removeView(menuView); menuView = null
            removeView(inspectRoot); inspectRoot = null
            removeView(infoCard); infoCard = null
            removeView(highlight); highlight = null
            hideBall()
        }
    }

    fun onDestroy() {
        hideEverything()
    }
}

package com.macropad.next

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.hypot

private const val I_REC = 0
private const val I_PLUS = 1
private const val I_FOLDER = 2
private const val I_DOC = 3
private const val I_PLAY = 4
private const val I_BACK = 5
private const val I_CLOSE = 6

/** Biểu tượng vẽ bằng nét đơn giản, không cần file ảnh */
private class Ico(ctx: Context, private val kind: Int, private val tint: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = minOf(w, h)
        val cx = w / 2
        val cy = h / 2
        val sw = s * 0.07f
        p.color = tint
        p.strokeWidth = sw
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.style = Paint.Style.STROKE
        val r = s / 2 - sw * 1.5f
        when (kind) {
            I_REC -> {
                c.drawCircle(cx, cy, r, p)
                p.style = Paint.Style.FILL
                c.drawCircle(cx, cy, r * 0.55f, p)
            }
            I_PLUS -> {
                c.drawCircle(cx, cy, r, p)
                c.drawLine(cx - r * 0.5f, cy, cx + r * 0.5f, cy, p)
                c.drawLine(cx, cy - r * 0.5f, cx, cy + r * 0.5f, p)
            }
            I_FOLDER -> {
                val l = cx - r * 0.85f
                val rt = cx + r * 0.85f
                val t = cy - r * 0.6f
                val b = cy + r * 0.65f
                c.drawRoundRect(RectF(l, t + r * 0.25f, rt, b), sw * 1.5f, sw * 1.5f, p)
                val path = Path()
                path.moveTo(l, t + r * 0.25f)
                path.lineTo(l, t)
                path.lineTo(cx - r * 0.1f, t)
                path.lineTo(cx + r * 0.2f, t + r * 0.25f)
                c.drawPath(path, p)
            }
            I_DOC -> {
                c.drawRoundRect(RectF(cx - r * 0.6f, cy - r * 0.85f, cx + r * 0.3f, cy + r * 0.85f), sw * 1.5f, sw * 1.5f, p)
                val ccx = cx + r * 0.35f
                val ccy = cy + r * 0.45f
                val cr = r * 0.4f
                c.drawCircle(ccx, ccy, cr, p)
                c.drawLine(ccx, ccy, ccx, ccy - cr * 0.6f, p)
                c.drawLine(ccx, ccy, ccx + cr * 0.5f, ccy, p)
            }
            I_PLAY -> {
                c.drawCircle(cx, cy, r, p)
                p.style = Paint.Style.FILL
                val path = Path()
                path.moveTo(cx - r * 0.2f, cy - r * 0.42f)
                path.lineTo(cx - r * 0.2f, cy + r * 0.42f)
                path.lineTo(cx + r * 0.48f, cy)
                path.close()
                c.drawPath(path, p)
            }
            I_BACK -> {
                c.drawLine(cx - r * 0.7f, cy, cx + r * 0.7f, cy, p)
                c.drawLine(cx - r * 0.7f, cy, cx - r * 0.2f, cy - r * 0.5f, p)
                c.drawLine(cx - r * 0.7f, cy, cx - r * 0.2f, cy + r * 0.5f, p)
            }
            I_CLOSE -> {
                c.drawLine(cx - r * 0.5f, cy - r * 0.5f, cx + r * 0.5f, cy + r * 0.5f, p)
                c.drawLine(cx - r * 0.5f, cy + r * 0.5f, cx + r * 0.5f, cy - r * 0.5f, p)
            }
        }
    }
}

/**
 * Bong bóng nổi nhỏ (5 mm, màu kín đáo). Thả gần cạnh nào thì tự cắt nửa và dính vào cạnh đó.
 * Bấm bong bóng: hiện bảng menu bên trái (dài bằng cạnh ngắn của điện thoại), bo 4 góc.
 * Giữ lâu bong bóng: tắt.
 */
class BubbleService : Service() {

    companion object {
        const val ACTION_STOP = "macropad.bubble.stop"
        const val CH = "bubble"
        const val NID = 31
    }

    private val cBg = 0xF2101820.toInt()
    private val cCard = 0xFF1B2733.toInt()
    private val cLine = 0x2EFFFFFF
    private val cText = 0xFFEAF2F8.toInt()
    private val cDim = 0xFF9FB0C0.toInt()
    private val cIcon = 0xFFB8D4D0.toInt()
    private val cAccent = 0xFF2DD4BF.toInt()

    private lateinit var wm: WindowManager
    private val h = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var bubble: View? = null
    private var dot: GradientDrawable? = null
    private lateinit var blp: WindowManager.LayoutParams
    private var win = 0
    private var panel: View? = null
    private var host: FrameLayout? = null
    private var titleTv: TextView? = null
    private var bw = 0
    private var bh = 0
    private var attached = false
    private var dockAnim: ValueAnimator? = null
    private var curPkg: String? = null
    private var lastEventTime = 0L
    private val poll = object : Runnable {
        override fun run() { checkForeground(); h.postDelayed(this, 800) }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "Bong bóng", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 2, Intent(this, BubbleService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("MacroPad 2 đang chạy")
            .setContentText("Giữ lâu vào bong bóng hoặc bấm Tắt để dừng.")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Tắt", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NID, n)

        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        if (bubble == null) addBubble()
        curPkg = null
        lastEventTime = System.currentTimeMillis()
        setShown(true)
        h.removeCallbacks(poll)
        h.postDelayed(poll, 800)
        reconnect()
        return START_NOT_STICKY
    }

    // ---------- kết nối ----------
    private fun reconnect() {
        val paired = getSharedPreferences("s", 0).getBoolean("paired", false)
        if (!paired) return
        worker.execute {
            val ok = Adb.ensureConnected(this)
            h.post { dot?.setColor(dotColor(ok)) }
        }
    }

    private fun dotColor(connected: Boolean) = if (connected) 0x8C6B8A99.toInt() else 0x8C9A8A6B.toInt()

    // ---------- bong bóng ----------
    private fun mm(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, v, resources.displayMetrics).toInt()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun screen(): Pair<Int, Int> {
        val b = wm.currentWindowMetrics.bounds
        return b.width() to b.height()
    }

    private fun addBubble() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        win = mm(7f)
        val circle = mm(5f)

        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(dotColor(Adb.connected))
        d.setStroke(dp(1), 0x40FFFFFF)
        dot = d
        val v = View(this)
        v.background = InsetDrawable(d, (win - circle) / 2)

        blp = WindowManager.LayoutParams(
            win, win,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        blp.gravity = Gravity.TOP or Gravity.START
        placeDocked()

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longFired = false
        val longRun = Runnable { longFired = true; UiLog.add("Đóng bong bóng"); stopSelf() }

        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dockAnim?.cancel()
                    v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).start()
                    downX = e.rawX; downY = e.rawY
                    startX = blp.x; startY = blp.y
                    moved = false; longFired = false
                    h.postDelayed(longRun, 800)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > slop) { moved = true; h.removeCallbacks(longRun) }
                    if (moved) {
                        val (w, hh) = screen()
                        val half = win / 2
                        blp.x = (startX + dx.toInt()).coerceIn(-half, w - half)
                        blp.y = (startY + dy.toInt()).coerceIn(-half, hh - half)
                        wm.updateViewLayout(v, blp)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    h.removeCallbacks(longRun)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (moved) snap()
                    else if (!longFired && e.actionMasked == MotionEvent.ACTION_UP) onTap()
                }
            }
            true
        }
        bubble = v
    }

    /** Đặt bong bóng dính nửa vào cạnh đã lưu (cạnh + vị trí dọc theo cạnh) */
    private fun placeDocked() {
        val (w, hh) = screen()
        val p = getSharedPreferences("bubble", 0)
        val edge = p.getInt("edge", 1)
        val f = p.getFloat("frac", 0.3f)
        val half = win / 2
        when (edge) {
            0 -> { blp.x = -half; blp.y = (f * (hh - win)).toInt() }
            1 -> { blp.x = w - half; blp.y = (f * (hh - win)).toInt() }
            2 -> { blp.y = -half; blp.x = (f * (w - win)).toInt() }
            else -> { blp.y = hh - half; blp.x = (f * (w - win)).toInt() }
        }
    }

    /** Thả tay: chọn cạnh gần nhất rồi trượt êm vào dính ở đó */
    private fun snap() {
        val (w, hh) = screen()
        val half = win / 2
        val cx = blp.x + half
        val cy = blp.y + half
        val d = intArrayOf(cx, w - cx, cy, hh - cy)
        var edge = 0
        for (i in 1..3) if (d[i] < d[edge]) edge = i
        val frac = if (edge < 2) (cy - half).toFloat() / (hh - win).coerceAtLeast(1)
        else (cx - half).toFloat() / (w - win).coerceAtLeast(1)
        getSharedPreferences("bubble", 0).edit()
            .putInt("edge", edge).putFloat("frac", frac.coerceIn(0f, 1f)).apply()

        val fromX = blp.x
        val fromY = blp.y
        placeDocked()
        val tx = blp.x
        val ty = blp.y
        blp.x = fromX
        blp.y = fromY
        val an = ValueAnimator.ofFloat(0f, 1f)
        an.duration = 260
        an.interpolator = OvershootInterpolator(0.9f)
        an.addUpdateListener { a ->
            val t = a.animatedValue as Float
            blp.x = (fromX + (tx - fromX) * t).toInt()
            blp.y = (fromY + (ty - fromY) * t).toInt()
            pushBubble()
        }
        an.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                blp.x = tx
                blp.y = ty
                pushBubble()
            }
        })
        dockAnim = an
        an.start()
    }

    private fun pushBubble() {
        val v = bubble ?: return
        if (attached) try { wm.updateViewLayout(v, blp) } catch (_: Exception) { }
    }

    /** Hiện/ẩn bong bóng có hiệu ứng mờ + phóng nhỏ */
    private fun setShown(show: Boolean) {
        val v = bubble ?: return
        if (show && !attached) {
            v.animate().cancel()
            if (!v.isAttachedToWindow) {
                v.alpha = 0f
                v.scaleX = 0.6f
                v.scaleY = 0.6f
                try { wm.addView(v, blp) } catch (e: Exception) { return }
            }
            attached = true
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180)
                .setInterpolator(DecelerateInterpolator()).start()
        } else if (!show && attached) {
            attached = false
            hidePanel()
            v.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(150)
                .setInterpolator(AccelerateInterpolator()).withEndAction {
                    if (!attached) try { wm.removeView(v) } catch (_: Exception) { }
                }.start()
        }
    }

    // ---------- chỉ hiện khi đang ở trong game đã thêm ----------
    private fun usageOk(): Boolean {
        val ao = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        return ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    private fun checkForeground() {
        if (!usageOk()) { setShown(true); return }
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val ev = usm.queryEvents(lastEventTime, now)
        val e = UsageEvents.Event()
        var latest = curPkg
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) latest = e.packageName
        }
        lastEventTime = now - 2000
        curPkg = latest
        val games = getSharedPreferences("games", 0).getStringSet("list", emptySet()) ?: emptySet()
        setShown(latest == null || latest in games)
    }

    private fun onTap() {
        if (panel == null) {
            if (!Adb.connected) reconnect()
            showPanel(0)
        } else hidePanel()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (bubble == null) return
        placeDocked()
        pushBubble()
        if (panel != null) {
            val pg = panelPage
            h.post { showPanel(pg, false) }
        }
    }

    // ---------- bảng menu ----------
    private var panelPage = 0

    private fun hidePanel(animate: Boolean = true) {
        val p = panel ?: return
        panel = null
        host = null
        titleTv = null
        if (!animate) {
            try { wm.removeView(p) } catch (_: Exception) { }
            return
        }
        p.animate().alpha(0f).translationX(-dp(40).toFloat()).setDuration(200)
            .setInterpolator(AccelerateInterpolator(1.4f)).withEndAction {
                try { wm.removeView(p) } catch (_: Exception) { }
            }.start()
    }

    private fun titleOf(pg: Int) = if (pg == 0) "Menu" else "Macro"

    private fun pageView(pg: Int): View = if (pg == 0) menuBody(bw, bh) else macroBody(bw, bh)

    private fun showPanel(pg: Int, animate: Boolean = true) {
        hidePanel(false)
        panelPage = pg
        val (w, hh) = screen()
        val ph = minOf(w, hh) - dp(16)
        val pw = dp(208).coerceAtMost(w)
        val pad = dp(10)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)
        val bg = GradientDrawable()
        bg.cornerRadius = dp(18).toFloat()
        bg.setColor(cBg)
        bg.setStroke(dp(1), cLine)
        root.background = bg

        bw = pw - pad * 2
        bh = ph - pad * 2 - dp(36)
        root.addView(header(pg), LinearLayout.LayoutParams(-1, dp(36)))
        val hostV = FrameLayout(this)
        hostV.addView(pageView(pg))
        root.addView(hostV, LinearLayout.LayoutParams(bw, bh))
        host = hostV

        val lp = WindowManager.LayoutParams(
            pw, ph,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(8)
        lp.y = (hh - ph) / 2
        if (animate) {
            root.alpha = 0f
            root.translationX = -dp(40).toFloat()
        }
        wm.addView(root, lp)
        panel = root
        if (animate) {
            root.animate().alpha(1f).translationX(0f).setDuration(260)
                .setInterpolator(DecelerateInterpolator(1.8f)).start()
        }
    }

    /** Đổi trang: trang mới trượt vào kèm mờ dần, trang cũ lùi nhẹ và mờ đi (quay lại thì ngược lại) */
    private fun goPage(pg: Int) {
        val hostV = host ?: return
        if (pg == panelPage) return
        val forward = pg > panelPage
        val old = hostV.getChildAt(hostV.childCount - 1)
        val nv = pageView(pg)
        val dist = dp(28).toFloat()
        hostV.addView(nv)
        nv.alpha = 0f
        nv.translationX = if (forward) dist else -dist
        nv.animate().alpha(1f).translationX(0f).setDuration(240)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
        old.animate().alpha(0f).translationX(if (forward) -dist else dist).setDuration(200)
            .setInterpolator(AccelerateInterpolator(1.2f)).withEndAction { hostV.removeView(old) }.start()
        panelPage = pg
        titleTv?.let { t ->
            t.animate().alpha(0f).setDuration(90).withEndAction {
                t.text = titleOf(pg)
                t.animate().alpha(1f).setDuration(120).start()
            }.start()
        }
    }

    private fun pressFx(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
    }

    private fun header(pg: Int): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val back = Ico(this, I_BACK, cText)
        back.setOnClickListener { if (panelPage == 1) goPage(0) else hidePanel() }
        row.addView(back, LinearLayout.LayoutParams(dp(30), dp(30)))

        val t = TextView(this)
        t.text = titleOf(pg)
        titleTv = t
        t.setTextColor(cText)
        t.textSize = 14f
        t.setTypeface(null, Typeface.BOLD)
        t.gravity = Gravity.CENTER
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))

        val x = Ico(this, I_CLOSE, cDim)
        x.setOnClickListener { hidePanel() }
        row.addView(x, LinearLayout.LayoutParams(dp(30), dp(30)))
        return row
    }

    /** Bảng 1: lưới 2 cột x 4 hàng nút tròn, nút đầu là "Macro", các ô còn lại để trống mờ */
    private fun menuBody(w: Int, h: Int): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val rowH = h / 4
        val d = (minOf(w / 2, rowH) - dp(10)).coerceAtMost(dp(60))
        for (r in 0 until 4) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (c in 0 until 2) {
                val cell = LinearLayout(this)
                cell.gravity = Gravity.CENTER
                val first = r == 0 && c == 0
                cell.addView(circle(if (first) "Macro" else null, d) { goPage(1) }, LinearLayout.LayoutParams(d, d))
                row.addView(cell, LinearLayout.LayoutParams(w / 2, rowH))
            }
            col.addView(row, LinearLayout.LayoutParams(w, rowH))
        }
        return col
    }

    private fun circle(text: String?, d: Int, click: () -> Unit): TextView {
        val tv = TextView(this)
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        if (text != null) { g.setColor(cCard); g.setStroke(dp(1), 0x55FFFFFF) }
        else { g.setColor(0x00000000); g.setStroke(dp(1), 0x1AFFFFFF) }
        tv.background = g
        tv.gravity = Gravity.CENTER
        tv.setTextColor(cText)
        tv.textSize = 11f
        if (text != null) {
            tv.text = text
            tv.setOnClickListener { click() }
            pressFx(tv)
        }
        return tv
    }

    /** Bảng 2: Ghi Macro, Tạo Macro, Macro đã ghi, Macro đã tạo, và nút dài Kích hoạt */
    private fun macroBody(w: Int, h: Int): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val rowH = h / 3
        val half = w / 2

        val r1 = LinearLayout(this)
        r1.addView(card(I_REC, "Ghi Macro", cIcon), cardLp(half, rowH))
        r1.addView(card(I_PLUS, "Tạo Macro", cIcon), cardLp(half, rowH))
        col.addView(r1, LinearLayout.LayoutParams(w, rowH))

        val r2 = LinearLayout(this)
        r2.addView(card(I_FOLDER, "Macro đã ghi", cIcon), cardLp(half, rowH))
        r2.addView(card(I_DOC, "Macro đã tạo", cIcon), cardLp(half, rowH))
        col.addView(r2, LinearLayout.LayoutParams(w, rowH))

        val r3 = LinearLayout(this)
        r3.addView(card(I_PLAY, "Kích hoạt", cAccent), cardLp(w, rowH))
        col.addView(r3, LinearLayout.LayoutParams(w, rowH))
        return col
    }

    private fun cardLp(w: Int, h: Int) = LinearLayout.LayoutParams(w - dp(8), h - dp(8)).apply {
        setMargins(dp(4), dp(4), dp(4), dp(4))
    }

    private fun card(kind: Int, label: String, tint: Int): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER
        val g = GradientDrawable()
        g.cornerRadius = dp(14).toFloat()
        g.setColor(cCard)
        g.setStroke(dp(1), cLine)
        c.background = g
        c.addView(Ico(this, kind, tint), LinearLayout.LayoutParams(dp(28), dp(28)))
        val t = TextView(this)
        t.text = label
        t.setTextColor(cText)
        t.textSize = 11f
        t.maxLines = 1
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(4), 0, 0)
        c.addView(t, LinearLayout.LayoutParams(-2, -2))
        c.setOnClickListener {
            Toast.makeText(applicationContext, "$label: sẽ làm ở bước sau", Toast.LENGTH_SHORT).show()
        }
        pressFx(c)
        return c
    }

    override fun onDestroy() {
        h.removeCallbacks(poll)
        dockAnim?.cancel()
        hidePanel(false)
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        bubble = null
        worker.shutdownNow()
        super.onDestroy()
    }
}

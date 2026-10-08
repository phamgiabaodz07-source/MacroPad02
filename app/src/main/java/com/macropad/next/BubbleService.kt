package com.macropad.next

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.hypot

/** Bong bóng nổi nhỏ (đường kính 5 mm), kéo đi đâu thì nằm yên ở đó. Giữ lâu để tắt. */
class BubbleService : Service() {

    companion object {
        const val ACTION_STOP = "macropad.bubble.stop"
        const val CH = "bubble"
        const val NID = 31
    }

    private lateinit var wm: WindowManager
    private val h = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var view: View? = null
    private var bg: GradientDrawable? = null
    private lateinit var lp: WindowManager.LayoutParams
    private var size = 0

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
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Tắt", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NID, n)

        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        if (view == null) addBubble()
        reconnect()
        return START_NOT_STICKY
    }

    private fun reconnect() {
        val paired = getSharedPreferences("s", 0).getBoolean("paired", false)
        if (!paired) return
        worker.execute {
            val ok = Adb.ensureConnected(this)
            h.post { paint(ok) }
        }
    }

    private fun paint(connected: Boolean) {
        bg?.setColor(if (connected) 0xCC2DD4BF.toInt() else 0xCCFFB020.toInt())
    }

    private fun addBubble() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, 5f, resources.displayMetrics).toInt()

        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(if (Adb.connected) 0xCC2DD4BF.toInt() else 0xCCFFB020.toInt())
        d.setStroke(2, 0xFFFFFFFF.toInt())
        bg = d
        val v = View(this)
        v.background = d

        lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        val p = getSharedPreferences("bubble", 0)
        lp.x = p.getInt("x", 40)
        lp.y = p.getInt("y", 300)
        clamp()

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
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false; longFired = false
                    h.postDelayed(longRun, 800)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > slop) { moved = true; h.removeCallbacks(longRun) }
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        clamp()
                        wm.updateViewLayout(v, lp)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    h.removeCallbacks(longRun)
                    if (moved) {
                        p.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
                    } else if (!longFired && e.actionMasked == MotionEvent.ACTION_UP) {
                        onTap()
                    }
                }
            }
            true
        }
        view = v
        wm.addView(v, lp)
    }

    private fun clamp() {
        val b = wm.currentWindowMetrics.bounds
        lp.x = lp.x.coerceIn(0, (b.width() - size).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (b.height() - size).coerceAtLeast(0))
    }

    private fun onTap() {
        if (Adb.connected) {
            Toast.makeText(applicationContext, "MacroPad 2: đã kết nối ✓", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(applicationContext, "MacroPad 2: chưa kết nối, đang thử lại...", Toast.LENGTH_SHORT).show()
            reconnect()
        }
    }

    override fun onDestroy() {
        view?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        view = null
        worker.shutdownNow()
        super.onDestroy()
    }
}

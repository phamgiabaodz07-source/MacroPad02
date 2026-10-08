package com.macropad.next

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Màn ghép đôi gỡ lỗi không dây + nhật ký */
class PairActivity : Activity() {

    private val cBg = 0xFF0E141B.toInt()
    private val cCard = 0xFF18222D.toInt()
    private val cAccent = 0xFF2DD4BF.toInt()
    private val cText = 0xFFEAF2F8.toInt()
    private val cDim = 0xFF9FB0C0.toInt()
    private val cOk = 0xFF3DDC97.toInt()
    private val cBad = 0xFFFF6B6B.toInt()

    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(cBg)
        root.setPadding(dp(18), dp(44), dp(18), dp(16))

        root.addView(txt("Ghép đôi gỡ lỗi không dây", 24f, cText, true))
        status = txt("", 14f, cBad, true)
        root.addView(status)
        root.addView(txt(
            "1. Bấm 'Bắt đầu ghép đôi', app mở Tuỳ chọn nhà phát triển.\n" +
            "2. Vào Gỡ lỗi không dây, chọn 'Ghép đôi thiết bị bằng mã ghép đôi'.\n" +
            "3. Vuốt thông báo xuống, bấm 'Nhập mã', gõ 6 số hiện trên màn hình.",
            13f, cDim, false))

        root.addView(button("Bắt đầu ghép đôi", true) {
            startForegroundService(Intent(this, PairService::class.java).setAction(PairService.ACTION_START))
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(button("Kết nối lại", false) {
            startForegroundService(Intent(this, PairService::class.java).setAction(PairService.ACTION_CONNECT))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        row.addView(button("Thử lệnh (id)", false) { testShell() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        row.addView(button("Dừng", false) {
            startService(Intent(this, PairService::class.java).setAction(PairService.ACTION_STOP))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        log = TextView(this)
        log.textSize = 11f
        log.setTextColor(cDim)
        log.typeface = Typeface.MONOSPACE
        log.setPadding(dp(10), dp(10), dp(10), dp(10))
        val bgd = GradientDrawable(); bgd.cornerRadius = dp(12).toFloat(); bgd.setColor(cCard)
        scroll = ScrollView(this)
        scroll.background = bgd
        scroll.addView(log)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })

        setContentView(root)
        log.text = UiLog.lines.joinToString("\n") + (if (UiLog.lines.isEmpty()) "" else "\n")
    }

    override fun onResume() {
        super.onResume()
        refresh()
        UiLog.listener = { line ->
            log.append(line + "\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
            refresh()
        }
    }

    override fun onPause() { super.onPause(); UiLog.listener = null }

    private fun refresh() {
        val paired = getSharedPreferences("s", 0).getBoolean("paired", false)
        val txt = (if (paired) "Đã ghép đôi ✓" else "Chưa ghép đôi") + "   |   " +
            (if (Adb.connected) "Đã kết nối ✓" else "Chưa kết nối")
        status.text = txt
        status.setTextColor(if (paired && Adb.connected) cOk else cBad)
    }

    private fun testShell() {
        Thread {
            try {
                if (!Adb.ensureConnected(this)) return@Thread
                UiLog.add("Chạy 'id': " + Adb.shell(this, "id").trim())
            } catch (e: Throwable) {
                UiLog.add("Lỗi chạy lệnh: ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun txt(t: String, sz: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = t; textSize = sz; setTextColor(color); setPadding(0, dp(4), 0, dp(4))
        if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun button(t: String, primary: Boolean, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 14f
        setOnClickListener { f() }
        val g = GradientDrawable(); g.cornerRadius = dp(14).toFloat()
        g.setColor(if (primary) cAccent else 0xFF2A3644.toInt())
        background = g
        setTextColor(if (primary) 0xFF06201C.toInt() else cText)
    }
}

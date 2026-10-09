package com.macropad.next

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Bước 1: màn hình xin 3 quyền. Chưa có bong bóng hay macro. */
class MainActivity : Activity() {

    private val cBg = 0xFF0E141B.toInt()
    private val cCard = 0xFF18222D.toInt()
    private val cAccent = 0xFF2DD4BF.toInt()
    private val cOk = 0xFF3DDC97.toInt()
    private val cBad = 0xFFFF6B6B.toInt()
    private val cText = 0xFFEAF2F8.toInt()
    private val cDim = 0xFF9FB0C0.toInt()

    private lateinit var list: LinearLayout
    private lateinit var next: Button

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(cBg)
        root.setPadding(dp(18), dp(44), dp(18), dp(20))
        root.addView(txt("MacroPad 2", 28f, cText, true))
        root.addView(txt("Bước 1: cấp quyền cho app", 15f, cDim, false))

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        val sv = ScrollView(this)
        sv.addView(list)
        val lp = LinearLayout.LayoutParams(-1, 0, 1f)
        lp.topMargin = dp(16)
        root.addView(sv, lp)

        next = button("Tiếp tục") {
            AlertDialog.Builder(this)
                .setTitle("Đã xong bước 1")
                .setMessage("Cả 3 quyền đã sẵn sàng. Bước kế tiếp là ghép đôi gỡ lỗi không dây để app có quyền phát cảm ứng. Hãy báo lại để làm bước đó.")
                .setPositiveButton("OK", null).show()
        }
        root.addView(next, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); render() }

    // ---------- trạng thái quyền ----------
    private fun overlayOk() = Settings.canDrawOverlays(this)
    private fun notifOk() = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
    private fun batteryOk() = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    private fun devOn() = try {
        Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    } catch (e: Exception) { false }
    private fun wifiDebugOn() = try {
        Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) == 1
    } catch (e: Exception) { false }
    private fun manual() = getSharedPreferences("s", 0).getBoolean("manual", false)
    private fun usageOk() = (getSystemService(APP_OPS_SERVICE) as AppOpsManager).unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    private fun pairingDone() = getSharedPreferences("s", 0).getBoolean("paired", false)

    // ---------- vẽ màn hình ----------
    private fun render() {
        list.removeAllViews()

        val o = overlayOk()
        card("1. Hiện trên ứng dụng khác",
            "Để bong bóng và nút macro nổi lên trên game.",
            o, emptyList(), if (o) null else "Cấp quyền", null) {
            safeStart(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                Intent(Settings.ACTION_SETTINGS))
        }

        val n = notifOk(); val b = batteryOk()
        card("2. Hoạt động nền",
            "Để app không bị hệ thống tắt khi đang chơi, và hiện được thông báo để nhập mã ghép đôi.",
            n && b, listOf("Cho phép thông báo" to n, "Không hạn chế pin" to b),
            if (n && b) null else if (!n) "Cho phép thông báo" else "Tắt hạn chế pin", null) {
            if (!n) askNotif() else askBattery()
        }

        val dev = devOn(); val wd = wifiDebugOn(); val man = manual()
        val done3 = man || (dev && wd)
        val desc3 = when {
            done3 -> "Gỡ lỗi không dây đã bật. Việc ghép đôi tự động sẽ làm ở bước kế tiếp."
            !dev -> "Bật Tuỳ chọn nhà phát triển: vào Thông tin điện thoại, bấm 7 lần vào Số bản dựng."
            else -> "Trong Tuỳ chọn nhà phát triển, bật Gỡ lỗi không dây (cần đang kết nối Wi-Fi)."
        }
        card("3. Gỡ lỗi không dây",
            desc3, done3,
            listOf("Tuỳ chọn nhà phát triển" to (dev || man), "Gỡ lỗi không dây" to (wd || man)),
            if (done3) null else if (!dev) "Mở thông tin điện thoại" else "Mở tuỳ chọn nhà phát triển",
            if (done3) null else "Mình đã bật rồi") {
            if (!dev) safeStart(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), Intent(Settings.ACTION_SETTINGS))
            else safeStart(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        }

        val paired = pairingDone()
        card("4. Ghép đôi thiết bị",
            if (paired) "Đã ghép đôi. Có thể kết nối lại bất cứ lúc nào." else "Ghép đôi bằng mã 6 số để app có quyền phát cảm ứng.",
            paired,
            listOf("Kết nối ADB" to Adb.connected),
            if (paired) "Mở màn ghép đôi" else "Bắt đầu ghép đôi",
            null) {
            startActivity(Intent(this, PairActivity::class.java))
        }

        val u = usageOk()
        card("5. Truy cập dữ liệu sử dụng",
            "Để bong bóng chỉ hiện khi bạn đang ở trong game đã thêm, thoát game thì tự ẩn.",
            u, emptyList(), if (u) null else "Cấp quyền", null) {
            safeStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")),
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        val all = o && n && b && done3 && paired && u
        next.isEnabled = all
        styleButton(next, all)
        if (all) next.setOnClickListener { startActivity(Intent(this, LibraryActivity::class.java)); finish() }
    }

    private fun card(
        title: String, desc: String, done: Boolean, lines: List<Pair<String, Boolean>>,
        action: String?, manualLabel: String?, onAction: () -> Unit
    ) {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(16), dp(14), dp(16), dp(14))
        val bgd = GradientDrawable()
        bgd.cornerRadius = dp(18).toFloat(); bgd.setColor(cCard)
        c.background = bgd

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.addView(txt(title, 16f, cText, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(txt(if (done) "✓ Xong" else "Chưa xong", 13f, if (done) cOk else cBad, true))
        c.addView(head)
        c.addView(txt(desc, 13f, cDim, false))
        lines.forEach { (t, ok) ->
            c.addView(txt((if (ok) "✓ " else "✗ ") + t, 13f, if (ok) cOk else cBad, false))
        }
        if (action != null) {
            val bt = button(action) { onAction() }
            val lp = LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(10)
            c.addView(bt, lp)
        }
        if (manualLabel != null) {
            val tv = txt(manualLabel, 13f, cAccent, false)
            tv.setPadding(0, dp(12), 0, dp(2))
            tv.setOnClickListener {
                getSharedPreferences("s", 0).edit().putBoolean("manual", true).apply(); render()
            }
            c.addView(tv)
        }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(12)
        list.addView(c, lp)
    }

    // ---------- xin quyền ----------
    private fun askNotif() {
        val p = getSharedPreferences("s", 0)
        if (Build.VERSION.SDK_INT >= 33 && !p.getBoolean("asked", false)) {
            p.edit().putBoolean("asked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else {
            safeStart(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun askBattery() {
        safeStart(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun safeStart(i: Intent, fallback: Intent) {
        try { startActivity(i) } catch (e: Exception) {
            try { startActivity(fallback) } catch (_: Exception) {}
        }
    }

    // ---------- tiện ích giao diện ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun txt(t: String, sz: Float, color: Int, bold: Boolean) = TextView(this).also {
        it.text = t; it.textSize = sz; it.setTextColor(color)
        it.setPadding(0, dp(3), 0, dp(3))
        if (bold) it.setTypeface(null, Typeface.BOLD)
    }

    private fun button(t: String, f: () -> Unit) = Button(this).also {
        it.text = t; it.isAllCaps = false; it.textSize = 15f
        it.setOnClickListener { _ -> f() }
        styleButton(it, true)
    }

    private fun styleButton(b: Button, on: Boolean) {
        val g = GradientDrawable()
        g.cornerRadius = dp(14).toFloat()
        g.setColor(if (on) cAccent else 0xFF2A3644.toInt())
        b.background = g
        b.setTextColor(if (on) 0xFF06201C.toInt() else cDim)
    }
}

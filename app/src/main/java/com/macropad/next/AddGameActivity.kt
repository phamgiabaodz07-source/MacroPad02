package com.macropad.next

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.widget.Button
import android.widget.CheckBox
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Thêm game: lưới app đã cài, chọn rồi Áp dụng */
class AddGameActivity : Activity() {

    private val cBg = 0xFF0E141B.toInt()
    private val cCard = 0xFF18222D.toInt()
    private val cAccent = 0xFF2DD4BF.toInt()
    private val cText = 0xFFEAF2F8.toInt()
    private val cDim = 0xFF9FB0C0.toInt()
    private val pm by lazy { packageManager }
    private val pref by lazy { getSharedPreferences("games", 0) }

    private val apps = mutableListOf<Pair<String, String>>() // (pkg, name)
    private val checked = mutableMapOf<String, Boolean>()
    private lateinit var grid: GridLayout

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(cBg)
        root.setPadding(dp(18), dp(44), dp(18), dp(20))

        root.addView(txt("Thêm trò chơi", 28f, cText, true))
        root.addView(txt("Nhấn vào trò chơi bạn muốn thêm, sau đó nhấn Áp dụng để xác nhận.", 13f, cDim, false)
            .apply { setPadding(0, dp(8), 0, dp(16)) })

        val sv = ScrollView(this)
        grid = GridLayout(this)
        grid.columnCount = 3
        sv.addView(grid)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        loadApps()
        val saved: Set<String> = pref.getStringSet("list", emptySet()) ?: emptySet()
        apps.forEach { (pkg, _) -> checked[pkg] = pkg in saved }
        apps.forEach { (pkg, name) ->
            val item = itemView(pkg, name, checked[pkg] ?: false) {
                checked[pkg] = !(checked[pkg] ?: false)
                renderGrid(grid)
            }
            grid.addView(item, GridLayout.LayoutParams().apply {
                width = dp(100); height = dp(130); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(8), dp(8), dp(8), dp(8))
            })
        }

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.addView(button("Quay lại") { finish() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        bottom.addView(button("Áp dụng") { apply() }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bottom, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        setContentView(root)
    }

    private fun loadApps() {
        pm.getInstalledApplications(PackageManager.GET_META_DATA).forEach { app ->
            if (app.packageName != packageName && (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
                val name = (pm.getApplicationLabel(app) ?: app.packageName).toString()
                apps.add(app.packageName to name)
            }
        }
        apps.sortBy { it.second }
    }

    private fun itemView(pkg: String, name: String, checked: Boolean, onCheck: () -> Unit): LinearLayout {
        val item = LinearLayout(this)
        item.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable(); bg.cornerRadius = dp(12).toFloat()
        bg.setColor(cCard); item.background = bg
        item.setPadding(dp(8), dp(8), dp(8), dp(8))

        val icon = android.widget.ImageView(this)
        try { icon.setImageDrawable(pm.getApplicationIcon(pkg)) } catch (e: Exception) {}
        item.addView(icon, LinearLayout.LayoutParams(dp(60), dp(60)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL })

        val cb = CheckBox(this)
        cb.isChecked = checked
        cb.setOnCheckedChangeListener { _, _ -> onCheck() }
        item.addView(cb, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); gravity = android.view.Gravity.CENTER_HORIZONTAL })

        val tv = txt(name, 10f, cDim, false)
        tv.maxLines = 2; tv.ellipsize = TextUtils.TruncateAt.END
        item.addView(tv, LinearLayout.LayoutParams(dp(80), -2).apply { topMargin = dp(4) })

        item.setOnClickListener { cb.toggle() }
        return item
    }

    private fun renderGrid(g: GridLayout) {
        var idx = 0
        for (i in 0 until g.childCount) {
            val v = g.getChildAt(i)
            if (v is LinearLayout) {
                apps.getOrNull(idx)?.first?.let { pkg ->
                    val bg = GradientDrawable(); bg.cornerRadius = dp(12).toFloat()
                    bg.setColor(if (checked[pkg] == true) cAccent else cCard); v.background = bg
                }
                idx++
            }
        }
    }

    private fun apply() {
        pref.edit().putStringSet("list", checked.filter { it.value }.keys).apply()
        finish()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun txt(t: String, sz: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = t; textSize = sz; setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun button(t: String, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 14f
        setOnClickListener { f() }
        val g = GradientDrawable(); g.cornerRadius = dp(14).toFloat(); g.setColor(cAccent)
        background = g; setTextColor(0xFF06201C.toInt())
    }
}

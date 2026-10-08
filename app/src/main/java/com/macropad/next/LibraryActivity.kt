package com.macropad.next

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Thư viện game: dãy thẻ ngang, nút Thêm game, nút Vào game (kèm bong bóng nổi) */
class LibraryActivity : Activity() {

    private val cBg = 0xFF0E141B.toInt()
    private val cCard = 0xFF18222D.toInt()
    private val cAccent = 0xFF2DD4BF.toInt()
    private val cText = 0xFFEAF2F8.toInt()
    private val cDim = 0xFF9FB0C0.toInt()
    private val cDark = 0xFF06201C.toInt()
    private val pm by lazy { packageManager }
    private val pref by lazy { getSharedPreferences("games", 0) }

    private lateinit var cardBox: LinearLayout
    private lateinit var cardName: TextView
    private lateinit var playBtn: Button
    private var games: List<String> = emptyList()
    private var selectedIdx = 0

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(cBg)
        root.setPadding(dp(18), dp(44), dp(18), dp(20))

        root.addView(txt("Thư viện game", 28f, cText, true))
        root.addView(button("+ Thêm game") {
            startActivity(Intent(this, AddGameActivity::class.java))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        // Dãy thẻ nằm giữa màn hình theo chiều dọc
        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        cardBox = LinearLayout(this)
        cardBox.orientation = LinearLayout.HORIZONTAL
        scroll.addView(cardBox)
        val mid = LinearLayout(this)
        mid.gravity = Gravity.CENTER_VERTICAL
        mid.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        root.addView(mid, LinearLayout.LayoutParams(-1, 0, 1f))

        cardName = txt("", 20f, cText, true)
        cardName.gravity = Gravity.CENTER_HORIZONTAL
        root.addView(cardName, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        playBtn = button("Vào game") { playGame() }
        root.addView(playBtn, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
    }

    override fun onResume() { super.onResume(); renderCards() }

    private fun label(pkg: String): String? = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { null }

    private fun renderCards() {
        val saved: Set<String> = pref.getStringSet("list", emptySet()) ?: emptySet()
        games = saved.filter { label(it) != null }.sortedBy { label(it) }
        cardBox.removeAllViews()
        if (games.isEmpty()) {
            cardName.text = "Chưa có game, bấm + Thêm game"
            playBtn.isEnabled = false
            return
        }
        games.forEachIndexed { i, pkg ->
            val icon = pm.getApplicationIcon(pkg)
            cardBox.addView(card(i, label(pkg) ?: pkg, icon),
                LinearLayout.LayoutParams(dp(140), dp(180)).apply { marginEnd = dp(12) })
        }
        if (selectedIdx >= games.size) selectedIdx = 0
        selectCard(selectedIdx)
    }

    private fun card(idx: Int, name: String, icon: Drawable): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.setPadding(dp(8), dp(14), dp(8), dp(8))
        c.isClickable = true
        c.setOnClickListener { selectCard(idx) }

        val iv = ImageView(this)
        iv.setImageDrawable(icon)
        c.addView(iv, LinearLayout.LayoutParams(dp(84), dp(84)).apply { bottomMargin = dp(10) })

        val tv = txt(name, 13f, cDim, false)
        tv.gravity = Gravity.CENTER_HORIZONTAL
        tv.maxLines = 2
        tv.ellipsize = TextUtils.TruncateAt.END
        c.addView(tv, LinearLayout.LayoutParams(-1, -2))
        return c
    }

    private fun selectCard(idx: Int) {
        selectedIdx = idx
        cardName.text = games.getOrNull(idx)?.let { label(it) } ?: ""
        playBtn.isEnabled = true
        for (i in 0 until cardBox.childCount) {
            val v = cardBox.getChildAt(i) as LinearLayout
            val sel = i == idx
            val g = GradientDrawable()
            g.cornerRadius = dp(16).toFloat()
            g.setColor(if (sel) cAccent else cCard)
            v.background = g
            (v.getChildAt(1) as TextView).setTextColor(if (sel) cDark else cDim)
        }
    }

    private fun playGame() {
        val pkg = games.getOrNull(selectedIdx) ?: return
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return
        if (Settings.canDrawOverlays(this)) {
            try { startForegroundService(Intent(this, BubbleService::class.java)) } catch (e: Exception) { }
        }
        startActivity(launch)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun txt(t: String, sz: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = t; textSize = sz; setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun button(t: String, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 15f
        setOnClickListener { f() }
        val g = GradientDrawable(); g.cornerRadius = dp(14).toFloat(); g.setColor(cAccent)
        background = g; setTextColor(cDark)
    }
}

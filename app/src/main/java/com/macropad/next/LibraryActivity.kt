package com.macropad.next

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Thư viện game: dãy thẻ ngang, nút Thêm game, nút Vào game */
class LibraryActivity : Activity() {

    private val cBg = 0xFF0E141B.toInt()
    private val cCard = 0xFF18222D.toInt()
    private val cAccent = 0xFF2DD4BF.toInt()
    private val cText = 0xFFEAF2F8.toInt()
    private val cDim = 0xFF9FB0C0.toInt()
    private val pm by lazy { packageManager }
    private val pref by lazy { getSharedPreferences("games", 0) }

    private lateinit var cardScroll: HorizontalScrollView
    private lateinit var cardBox: LinearLayout
    private lateinit var cardName: TextView
    private lateinit var playBtn: Button
    private var games: MutableList<String> = mutableListOf()
    private var selectedIdx = 0

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        games = (pref.getStringSet("list", emptySet()) ?: emptySet<String>()).toMutableList()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(cBg)
        root.setPadding(dp(18), dp(44), dp(18), dp(20))

        root.addView(txt("Thư viện game", 28f, cText, true))

        val addBtn = button("+ Thêm game") {
            startActivity(Intent(this, AddGameActivity::class.java))
        }
        root.addView(addBtn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        cardScroll = HorizontalScrollView(this)
        cardScroll.isHorizontalScrollBarEnabled = false
        cardBox = LinearLayout(this)
        cardBox.orientation = LinearLayout.HORIZONTAL
        cardScroll.addView(cardBox)
        root.addView(cardScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16) })

        cardName = txt("", 18f, cText, true)
        cardName.setPadding(dp(16), dp(8), dp(16), dp(8))
        root.addView(cardName, LinearLayout.LayoutParams(-1, -2))

        playBtn = button("Vào game") { playGame() }
        root.addView(playBtn, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
        renderCards()
    }

    override fun onResume() { super.onResume(); renderCards() }

    private fun renderCards() {
        games = (pref.getStringSet("list", emptySet()) ?: emptySet<String>()).toMutableList()
        cardBox.removeAllViews()
        if (games.isEmpty()) {
            cardName.text = "Chưa có game"
            playBtn.isEnabled = false
            return
        }
        selectedIdx = 0
        games.forEachIndexed { i, pkg ->
            val app = try { pm.getApplicationInfo(pkg, 0) } catch (e: Exception) { null } ?: return@forEachIndexed
            val card = card(i, pm.getApplicationLabel(app).toString(), app.loadIcon(pm))
            cardBox.addView(card, LinearLayout.LayoutParams(dp(120), dp(160)).apply { marginEnd = dp(12) })
        }
        selectCard(0)
    }

    private fun card(idx: Int, name: String, icon: android.graphics.drawable.Drawable): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.isClickable = true
        c.isFocusable = true
        c.tag = idx
        c.setOnClickListener { selectCard(idx) }

        val iv = ImageView(this)
        iv.setImageDrawable(icon)
        val lp = LinearLayout.LayoutParams(dp(80), dp(80))
        lp.topMargin = dp(8); lp.bottomMargin = dp(8)
        c.addView(iv, lp)

        val tv = txt(name, 11f, cDim, false)
        tv.maxLines = 2
        tv.ellipsize = android.text.TextUtils.TruncateAt.END
        tv.setPadding(dp(4), 0, dp(4), 0)
        c.addView(tv, LinearLayout.LayoutParams(-1, -2))
        return c
    }

    private fun selectCard(idx: Int) {
        selectedIdx = idx
        games.getOrNull(idx)?.let { pkg ->
            val app = try { pm.getApplicationInfo(pkg, 0) } catch (e: Exception) { null }
            cardName.text = app?.let { pm.getApplicationLabel(it).toString() } ?: "?"
        }
        playBtn.isEnabled = true
        for (i in 0 until cardBox.childCount) {
            val v = cardBox.getChildAt(i)
            val bgd = GradientDrawable()
            bgd.cornerRadius = dp(12).toFloat()
            bgd.setColor(if (i == idx) cAccent else cCard)
            v.background = bgd
        }
    }

    private fun playGame() {
        games.getOrNull(selectedIdx)?.let { pkg ->
            try { startActivity(packageManager.getLaunchIntentForPackage(pkg)) }
            catch (e: Exception) {}
        }
    }

    // ---------- tiện ích ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun txt(t: String, sz: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = t; textSize = sz; setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun button(t: String, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 15f
        setOnClickListener { f() }
        val g = GradientDrawable(); g.cornerRadius = dp(14).toFloat(); g.setColor(cAccent)
        background = g; setTextColor(0xFF06201C.toInt())
    }
}

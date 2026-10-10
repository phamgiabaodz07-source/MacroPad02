package com.macropad.next

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Một bước đang chỉnh (có thể thay đổi). type: 0 Chạm, 1 Vuốt, 2 Chạm liên tục */
private class ES(
    var type: Int, var finger: Int, var start: Long, var dur: Long,
    var x1: Int, var y1: Int, var x2: Int, var y2: Int,
    var path: List<IntArray>, var pathDur: Long, var hold: Int, var gap: Int
)

/** Dòng thời gian: mỗi ngón tay một dòng, kéo khối để dời, kéo hai đầu để đổi độ dài */
private class TimelineView(ctx: Context, private val steps: MutableList<ES>) : View(ctx) {
    var lanes = 1
    var ppm = 0.1f
    var selected: ES? = null
    var onSelect: (ES?) -> Unit = {}
    var onChanged: () -> Unit = {}
    var minW = 0

    private val d = ctx.resources.displayMetrics.density
    val rulerH = (28 * d).toInt()
    val laneH = (44 * d).toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var mode = 0
    private var target: ES? = null
    private var downX = 0f
    private var oStart = 0L
    private var oDur = 0L

    fun colorOf(t: Int): Int = when (t) {
        0 -> 0xFF5B7FD1.toInt()
        2 -> 0xFFD58A4B.toInt()
        else -> 0xFF4FB286.toInt()
    }

    fun nameOf(t: Int): String = when (t) {
        0 -> "Chạm"
        2 -> "Chạm liên tục"
        else -> "Vuốt"
    }

    private fun snap(v: Long): Long = (v / 10) * 10

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var end = 0L
        for (s in steps) end = max(end, s.start + s.dur)
        val w = max(((end + 2000) * ppm).toInt(), minW)
        setMeasuredDimension(w, rulerH + lanes * laneH)
    }

    private fun blockRect(s: ES): RectF {
        val x1 = s.start * ppm
        val x2 = max((s.start + s.dur) * ppm, x1 + 14 * d)
        val top = rulerH + s.finger * laneH + 4 * d
        return RectF(x1, top, x2, top + laneH - 8 * d)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        p.style = Paint.Style.FILL
        for (i in 0 until lanes) {
            p.color = if (i % 2 == 0) 0x14FFFFFF else 0x0AFFFFFF
            c.drawRect(0f, rulerH + i * laneH.toFloat(), w, rulerH + (i + 1) * laneH.toFloat(), p)
        }
        val cands = longArrayOf(50, 100, 200, 500, 1000, 2000, 5000, 10000, 30000)
        var major = cands[cands.size - 1]
        for (cd in cands) {
            if (cd * ppm >= 56 * d) { major = cd; break }
        }
        p.strokeWidth = 1f
        p.textSize = 10 * d
        var t = 0L
        while (t * ppm <= w) {
            val x = t * ppm
            p.color = 0x66FFFFFF
            c.drawLine(x, rulerH * 0.55f, x, rulerH.toFloat(), p)
            p.color = 0xFF9FB0C0.toInt()
            c.drawText(String.format(Locale.US, "%.2f", t / 1000.0), x + 3 * d, rulerH * 0.5f, p)
            t += major
        }
        for (s in steps) {
            val r = blockRect(s)
            p.style = Paint.Style.FILL
            p.color = colorOf(s.type)
            c.drawRoundRect(r, 8 * d, 8 * d, p)
            if (s === selected) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2 * d
                p.color = 0xFFFFFFFF.toInt()
                c.drawRoundRect(r, 8 * d, 8 * d, p)
                p.style = Paint.Style.FILL
                c.drawCircle(r.left, r.centerY(), 5 * d, p)
                c.drawCircle(r.right, r.centerY(), 5 * d, p)
            }
            p.style = Paint.Style.FILL
            p.color = 0xFFFFFFFF.toInt()
            p.textSize = 10 * d
            c.save()
            c.clipRect(r)
            c.drawText(nameOf(s.type), r.left + 6 * d, r.centerY() + 4 * d, p)
            c.restore()
        }
    }

    private fun hit(x: Float, y: Float): Pair<ES, Int>? {
        val sel = selected
        val slop = 14 * d
        if (sel != null) {
            val r = blockRect(sel)
            if (y >= r.top - slop && y <= r.bottom + slop) {
                if (abs(x - r.left) <= slop) return Pair(sel, 2)
                if (abs(x - r.right) <= slop) return Pair(sel, 3)
            }
        }
        for (i in steps.indices.reversed()) {
            val s = steps[i]
            val r = blockRect(s)
            if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return Pair(s, 1)
        }
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val h = hit(e.x, e.y) ?: return false
                parent.requestDisallowInterceptTouchEvent(true)
                val s = h.first
                mode = h.second
                target = s
                downX = e.x
                oStart = s.start
                oDur = s.dur
                if (selected !== s) {
                    selected = s
                    onSelect(s)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val s = target ?: return true
                val dMs = ((e.x - downX) / ppm).toLong()
                when (mode) {
                    1 -> {
                        s.start = snap(max(0L, oStart + dMs))
                        s.finger = ((e.y - rulerH) / laneH).toInt().coerceIn(0, lanes - 1)
                    }
                    2 -> {
                        val ns = snap(min(max(0L, oStart + dMs), oStart + oDur - 20))
                        s.dur = oDur + (oStart - ns)
                        s.start = ns
                    }
                    3 -> s.dur = max(20L, snap(oDur + dMs))
                }
                requestLayout()
                invalidate()
                onChanged()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                target = null
                mode = 0
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}

/** Lớp phủ để chạm chọn vị trí trên màn hình game */
private class CrossView(ctx: Context) : View(ctx) {
    var onPicked: (Int, Int) -> Unit = { _, _ -> }
    private var px = -1f
    private var py = -1f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = ctx.resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        if (px < 0) return
        p.color = 0xFF2DD4BF.toInt()
        p.strokeWidth = 2 * d
        p.style = Paint.Style.STROKE
        c.drawLine(px, 0f, px, height.toFloat(), p)
        c.drawLine(0f, py, width.toFloat(), py, p)
        c.drawCircle(px, py, 14 * d, p)
        p.style = Paint.Style.FILL
        p.textSize = 12 * d
        c.drawText("${px.toInt()}, ${py.toInt()}", min(px + 18 * d, width - 100 * d), max(py - 18 * d, 20 * d), p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { px = e.x; py = e.y; invalidate() }
            MotionEvent.ACTION_UP -> { px = e.x; py = e.y; invalidate(); onPicked(e.x.toInt(), e.y.toInt()) }
        }
        return true
    }
}

/** Trình chỉnh macro toàn màn hình: dòng thời gian, thông số từng khối, thêm/sao chép/xóa, lưu */
class MacroEditor(
    private val ctx: Context,
    private val wm: WindowManager,
    private val macro: Macro,
    private val scrW: Int,
    private val scrH: Int,
    private val onClose: () -> Unit,
    private val onSaved: () -> Unit
) {
    private val cBg = 0xFA0E141B.toInt()
    private val cCard = 0xFF18222D.toInt()
    private val cChip = 0xFF243241.toInt()
    private val cText = 0xFFEAF2F8.toInt()
    private val cDim = 0xFF9FB0C0.toInt()
    private val cAccent = 0xFF2DD4BF.toInt()
    private val cWarn = 0xFFE0796B.toInt()

    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val steps = ArrayList<ES>()
    private var lanes = 1
    private var dirty = false
    private var shown = false
    private lateinit var root: FrameLayout
    private lateinit var timeline: TimelineView
    private lateinit var labels: LinearLayout
    private lateinit var params: LinearLayout
    private lateinit var lp: WindowManager.LayoutParams
    private var pickView: View? = null

    fun show() {
        for (s in macro.steps) {
            steps.add(ES(s.type, s.finger, s.startMs, s.durMs, s.x1, s.y1, s.x2, s.y2, s.path, s.durMs, s.hold, s.gap))
        }
        lanes = max(1, (steps.maxOfOrNull { it.finger } ?: 0) + 1)
        build()
        lp = WindowManager.LayoutParams(
            -1, -1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        root.alpha = 0f
        wm.addView(root, lp)
        shown = true
        root.animate().alpha(1f).setDuration(200).start()
    }

    fun close() {
        if (!shown) return
        shown = false
        pickView?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        pickView = null
        try { wm.removeView(root) } catch (_: Exception) { }
        onClose()
    }

    // ---------- giao diện ----------
    private fun lpw(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private fun text(t: String, size: Float, color: Int, bold: Boolean): TextView {
        val tv = TextView(ctx)
        tv.text = t
        tv.textSize = size
        tv.setTextColor(color)
        if (bold) tv.setTypeface(null, Typeface.BOLD)
        return tv
    }

    private fun chip(t: String, color: Int = cText, bg: Int = cChip, onClick: () -> Unit): TextView {
        val tv = text(t, 12f, color, false)
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(12), dp(8), dp(12), dp(8))
        val g = GradientDrawable()
        g.cornerRadius = dp(10).toFloat()
        g.setColor(bg)
        tv.background = g
        tv.setOnClickListener { onClick() }
        return tv
    }

    private fun build() {
        root = FrameLayout(ctx)
        root.setBackgroundColor(cBg)
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(28), dp(12), dp(28), dp(10))

        val top = LinearLayout(ctx)
        top.gravity = Gravity.CENTER_VERTICAL
        val title = text("Chỉnh macro: ${macro.name}", 14f, cText, true)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        top.addView(title, lpw(0, -2, 1f))
        top.addView(chip("−") { zoom(1f / 1.4f) }, lpw(-2, -2).apply { marginEnd = dp(6) })
        top.addView(text("Thu phóng", 11f, cDim, false))
        top.addView(chip("+") { zoom(1.4f) }, lpw(-2, -2).apply { marginStart = dp(6) })
        col.addView(top, lpw(-1, -2))

        val mid = LinearLayout(ctx)
        mid.orientation = LinearLayout.HORIZONTAL
        mid.addView(sidebar(), lpw(dp(104), -1))
        mid.addView(center(), lpw(0, -1, 1f).apply { marginStart = dp(8); marginEnd = dp(8) })
        mid.addView(paramsScroll(), lpw(dp(230), -1))
        col.addView(mid, lpw(-1, 0, 1f).apply { topMargin = dp(8); bottomMargin = dp(8) })

        col.addView(bottomBar(), lpw(-1, -2))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        rebuildParams()
    }

    private fun sidebar(): LinearLayout {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.addView(text("Thêm bước", 11f, cDim, false))
        val items = listOf(Pair("Chạm", 0), Pair("Chạm liên tục", 2), Pair("Vuốt", 1))
        for ((name, type) in items) {
            val color = when (type) { 0 -> 0xFF5B7FD1.toInt(); 2 -> 0xFFD58A4B.toInt(); else -> 0xFF4FB286.toInt() }
            box.addView(chip(name, 0xFFFFFFFF.toInt(), color) { addStep(type) },
                lpw(-1, -2).apply { topMargin = dp(8) })
        }
        box.addView(text("Bước mới nằm ở dòng ngón tay đang chọn.", 10f, cDim, false),
            lpw(-1, -2).apply { topMargin = dp(10) })
        return box
    }

    private fun center(): ScrollView {
        val sv = ScrollView(ctx)
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        labels = LinearLayout(ctx)
        labels.orientation = LinearLayout.VERTICAL
        row.addView(labels, lpw(dp(78), -2))

        val hsv = HorizontalScrollView(ctx)
        hsv.isHorizontalScrollBarEnabled = false
        timeline = TimelineView(ctx, steps)
        timeline.lanes = lanes
        timeline.ppm = d * 0.1f
        timeline.onSelect = { rebuildParams() }
        timeline.onChanged = { dirty = true; rebuildParams() }
        hsv.addView(timeline)
        row.addView(hsv, lpw(0, -2, 1f))
        sv.addView(row)
        hsv.post {
            timeline.minW = hsv.width
            timeline.requestLayout()
        }
        refreshLabels()
        return sv
    }

    private fun refreshLabels() {
        labels.removeAllViews()
        labels.addView(View(ctx), lpw(-1, timeline.rulerH))
        for (i in 0 until lanes) {
            val t = text("Ngón tay ${i + 1}", 11f, cDim, false)
            t.gravity = Gravity.CENTER_VERTICAL
            labels.addView(t, lpw(-1, timeline.laneH))
        }
    }

    private fun paramsScroll(): ScrollView {
        val sv = ScrollView(ctx)
        params = LinearLayout(ctx)
        params.orientation = LinearLayout.VERTICAL
        params.setPadding(dp(10), dp(10), dp(10), dp(10))
        val g = GradientDrawable()
        g.cornerRadius = dp(14).toFloat()
        g.setColor(cCard)
        sv.background = g
        sv.addView(params)
        return sv
    }

    private fun bottomBar(): LinearLayout {
        val bar = LinearLayout(ctx)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.addView(chip("Thêm ngón tay") { addLane() }, lpw(-2, -2).apply { marginEnd = dp(8) })
        bar.addView(chip("Sao chép") { copyStep() }, lpw(-2, -2).apply { marginEnd = dp(8) })
        bar.addView(chip("Xóa") { deleteStep() }, lpw(-2, -2))
        bar.addView(View(ctx), lpw(0, 1, 1f))
        bar.addView(chip("Lưu", 0xFF06201C.toInt(), cAccent) { save() }, lpw(-2, -2).apply { marginEnd = dp(8) })
        val back = chip("Quay lại") { }
        back.setOnClickListener {
            if (!dirty || back.tag == "ok") close()
            else {
                back.tag = "ok"
                back.text = "Bỏ thay đổi?"
                back.setTextColor(cWarn)
                back.postDelayed({
                    back.tag = null
                    back.text = "Quay lại"
                    back.setTextColor(cText)
                }, 3000)
            }
        }
        bar.addView(back, lpw(-2, -2))
        return bar
    }

    // ---------- thông số khối đang chọn ----------
    private fun rebuildParams() {
        params.removeAllViews()
        val s = timeline.selected
        if (s == null) {
            params.addView(text("Chọn một khối trên dòng thời gian để chỉnh, hoặc thêm bước ở cột trái.", 12f, cDim, false))
            return
        }
        params.addView(text("${timeline.nameOf(s.type)} · Ngón tay ${s.finger + 1}", 14f, cText, true))
        if (s.type == 1) {
            params.addView(posRow("Từ", "(${s.x1}, ${s.y1})") {
                pickPoint { x, y -> s.x1 = x; s.y1 = y; s.path = emptyList() }
            })
            params.addView(posRow("Đến", "(${s.x2}, ${s.y2})") {
                pickPoint { x, y -> s.x2 = x; s.y2 = y; s.path = emptyList() }
            })
        } else {
            params.addView(posRow("Vị trí", "(${s.x1}, ${s.y1})") {
                pickPoint { x, y -> s.x1 = x; s.y1 = y; s.x2 = x; s.y2 = y }
            })
        }
        params.addView(stepper("Tổng thời lượng (ms)", s.dur.toInt(), 20, max(2000, s.dur.toInt() * 2), 60000, 10) { v ->
            s.dur = v.toLong()
            changed()
        })
        if (s.type == 2) {
            params.addView(stepper("Thời lượng chạm (ms)", s.hold, 10, 500, 5000, 5) { v -> s.hold = v; changed() })
            params.addView(stepper("Khoảng cách hai lần chạm (ms)", s.gap, 1, 500, 5000, 5) { v -> s.gap = v; changed() })
        }
    }

    private fun changed() {
        dirty = true
        timeline.requestLayout()
        timeline.invalidate()
    }

    private fun posRow(label: String, value: String, onPick: () -> Unit): LinearLayout {
        val row = LinearLayout(ctx)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(8), 0, 0)
        row.addView(text("$label: $value", 12f, cText, false), lpw(0, -2, 1f))
        row.addView(chip("Chọn") { onPick() })
        return row
    }

    private fun stepper(
        label: String, value: Int, min: Int, softMax: Int, hardMax: Int, step: Int, onChange: (Int) -> Unit
    ): LinearLayout {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(10), 0, 0)
        var cur = value
        val head = LinearLayout(ctx)
        head.addView(text(label, 11f, cDim, false), lpw(0, -2, 1f))
        val tv = text("$cur", 12f, cText, true)
        head.addView(tv)
        box.addView(head)

        val seek = SeekBar(ctx)
        seek.max = (softMax - min) / step
        seek.progress = (cur.coerceIn(min, softMax) - min) / step
        fun setVal(v: Int) {
            cur = v.coerceIn(min, hardMax)
            tv.text = "$cur"
            seek.progress = (cur.coerceIn(min, softMax) - min) / step
            onChange(cur)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    cur = min + p * step
                    tv.text = "$cur"
                    onChange(cur)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { }
            override fun onStopTrackingTouch(sb: SeekBar?) { }
        })
        val row = LinearLayout(ctx)
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(chip("−") { setVal(cur - step) })
        row.addView(seek, lpw(0, -2, 1f))
        row.addView(chip("+") { setVal(cur + step) })
        box.addView(row)
        return box
    }

    // ---------- chọn vị trí trên màn hình ----------
    private fun pickPoint(apply: (Int, Int) -> Unit) {
        try { wm.removeView(root) } catch (_: Exception) { }
        val ov = FrameLayout(ctx)
        ov.setBackgroundColor(0x33000000)
        val cross = CrossView(ctx)
        ov.addView(cross, FrameLayout.LayoutParams(-1, -1))
        val bar = LinearLayout(ctx)
        bar.gravity = Gravity.CENTER_VERTICAL
        val g = GradientDrawable()
        g.cornerRadius = dp(14).toFloat()
        g.setColor(0xF2101820.toInt())
        bar.background = g
        bar.setPadding(dp(14), dp(8), dp(8), dp(8))
        bar.addView(text("Chạm vào vị trí trên màn hình", 13f, cText, true), lpw(0, -2, 1f))
        bar.addView(chip("Hủy") { endPick() })
        ov.addView(bar, FrameLayout.LayoutParams(dp(320), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(12) })
        cross.onPicked = { x, y ->
            endPick()
            apply(x * macro.sw / max(scrW, 1), y * macro.sh / max(scrH, 1))
            dirty = true
            rebuildParams()
            timeline.invalidate()
        }
        val lp2 = WindowManager.LayoutParams(
            -1, -1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp2.gravity = Gravity.TOP or Gravity.START
        wm.addView(ov, lp2)
        pickView = ov
    }

    private fun endPick() {
        pickView?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        pickView = null
        if (shown) try { wm.addView(root, lp) } catch (_: Exception) { }
    }

    // ---------- thao tác ----------
    private fun zoom(f: Float) {
        timeline.ppm = (timeline.ppm * f).coerceIn(0.02f * d, 1.5f * d)
        timeline.requestLayout()
        timeline.invalidate()
    }

    private fun addLane() {
        if (lanes >= 10) return
        lanes++
        timeline.lanes = lanes
        refreshLabels()
        dirty = true
        timeline.requestLayout()
        timeline.invalidate()
    }

    private fun addStep(type: Int) {
        val lane = timeline.selected?.finger ?: 0
        var start = 0L
        var any = false
        for (s in steps) if (s.finger == lane) { any = true; start = max(start, s.start + s.dur + 100) }
        if (!any) start = 0L
        val cx = macro.sw / 2
        val cy = macro.sh / 2
        val s = when (type) {
            0 -> ES(0, lane, start, 100, cx, cy, cx, cy, emptyList(), 0, 40, 40)
            2 -> ES(2, lane, start, 1000, cx, cy, cx, cy, emptyList(), 0, 40, 40)
            else -> ES(1, lane, start, 400, (macro.sw * 0.4f).toInt(), cy, (macro.sw * 0.6f).toInt(), cy, emptyList(), 0, 40, 40)
        }
        steps.add(s)
        timeline.selected = s
        dirty = true
        rebuildParams()
        timeline.requestLayout()
        timeline.invalidate()
    }

    private fun copyStep() {
        val s = timeline.selected ?: return
        val n = ES(s.type, s.finger, s.start + s.dur + 50, s.dur, s.x1, s.y1, s.x2, s.y2, s.path, s.pathDur, s.hold, s.gap)
        steps.add(n)
        timeline.selected = n
        dirty = true
        rebuildParams()
        timeline.requestLayout()
        timeline.invalidate()
    }

    private fun deleteStep() {
        val s = timeline.selected ?: return
        steps.remove(s)
        timeline.selected = null
        dirty = true
        rebuildParams()
        timeline.requestLayout()
        timeline.invalidate()
    }

    private fun save() {
        val out = steps.sortedWith(compareBy({ it.start }, { it.finger })).map { s ->
            var path = s.path
            if (s.type == 1 && path.size >= 2 && s.pathDur > 0 && s.pathDur != s.dur) {
                path = path.map { intArrayOf((it[0] * s.dur / s.pathDur).toInt(), it[1], it[2]) }
            }
            Step(s.type, s.finger, s.start, s.dur, s.x1, s.y1, s.x2, s.y2,
                if (s.type == 1) path else emptyList(), s.hold, s.gap)
        }
        macro.steps = out
        MacroStore.update(ctx, macro)
        onSaved()
        close()
    }
}

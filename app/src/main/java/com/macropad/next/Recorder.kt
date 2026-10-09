package com.macropad.next

import android.content.Context
import android.view.Surface
import java.io.Closeable
import kotlin.math.hypot

/**
 * Ghi chạm của người dùng bằng cách đọc sự kiện cảm ứng thô qua ADB (lệnh getevent).
 * Không chặn game: game vẫn nhận chạm bình thường.
 */
class TouchRecorder(
    private val ctx: Context,
    val screenW: Int,
    val screenH: Int,
    private val rotation: Int,
    private val excluded: () -> List<IntArray>,
    private val onFirstTouch: () -> Unit,
    private val onError: (String) -> Unit
) {
    private class Contact(val slot: Int, val t0: Long) {
        var t1 = t0
        val pts = ArrayList<IntArray>()
    }

    private val reEv = Regex("""EV_(\w+)\s+(\w+)\s+([0-9a-fA-F]+)""")
    private val reT = Regex("""\[\s*(\d+)\.(\d+)\]""")

    private var handle: Closeable? = null
    @Volatile private var running = false
    private var dev = ""
    private var maxX = 1
    private var maxY = 1
    private val rawX = IntArray(32)
    private val rawY = IntArray(32)
    private val hasX = BooleanArray(32)
    private val hasY = BooleanArray(32)
    private val trackId = IntArray(32) { -1 }
    private val dirty = BooleanArray(32)
    private val ignored = BooleanArray(32)
    private val cur = arrayOfNulls<Contact>(32)
    private val done = ArrayList<Contact>()
    private var slot = 0
    private var lastT = 0L
    private var started = false
    private var baseT = 0L

    /** Chạy ở luồng nền. Trả về true nếu bắt đầu nghe thành công. */
    fun start(): Boolean {
        try {
            if (!findDevice()) {
                onError("Không tìm thấy màn hình cảm ứng (xem nhật ký)")
                return false
            }
            running = true
            handle = Adb.stream(ctx, "getevent -lt $dev", { line -> parse(line) }, {
                if (running) onError("Mất kết nối ADB khi đang ghi")
            })
            return true
        } catch (e: Throwable) {
            UiLog.add("Lỗi bắt đầu ghi: ${e.javaClass.simpleName}: ${e.message}")
            onError("Không bắt đầu ghi được (xem nhật ký)")
            return false
        }
    }

    /** Dừng ghi và trả về các bước đã ghi. Chạy ở luồng nền. */
    @Synchronized
    fun stop(): List<Step> {
        running = false
        try { handle?.close() } catch (_: Exception) { }
        handle = null
        for (s in 0 until 32) {
            val c = cur[s]
            if (c != null) {
                c.t1 = maxOf(lastT, c.t0)
                done.add(c)
                cur[s] = null
            }
        }
        return buildSteps()
    }

    private fun findDevice(): Boolean {
        val p = ctx.getSharedPreferences("touch", 0)
        val cached = p.getString("dev", null)
        if (cached != null && p.getInt("mx", 0) > 0 && p.getInt("my", 0) > 0) {
            dev = cached
            maxX = p.getInt("mx", 1)
            maxY = p.getInt("my", 1)
            return true
        }
        val out = Adb.shell(ctx, "getevent -pl")
        var path = ""
        var mx = 0
        var my = 0
        var bestPath = ""
        var bestX = 0
        var bestY = 0
        fun flush() {
            if (path.isNotEmpty() && mx > 0 && my > 0 && my > bestY) {
                bestPath = path; bestX = mx; bestY = my
            }
        }
        val reMax = Regex("""max\s+(\d+)""")
        for (line in out.lines()) {
            val d = Regex("""add device \d+:\s*(\S+)""").find(line)
            if (d != null) {
                flush()
                path = d.groupValues[1]; mx = 0; my = 0
            } else if (line.contains("ABS_MT_POSITION_X")) {
                mx = reMax.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            } else if (line.contains("ABS_MT_POSITION_Y")) {
                my = reMax.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
        }
        flush()
        if (bestPath.isEmpty()) {
            UiLog.add("getevent -pl không thấy thiết bị có ABS_MT_POSITION_X/Y")
            return false
        }
        dev = bestPath
        maxX = bestX
        maxY = bestY
        p.edit().putString("dev", dev).putInt("mx", maxX).putInt("my", maxY).apply()
        UiLog.add("Màn hình cảm ứng: $dev (tối đa $maxX x $maxY)")
        return true
    }

    @Synchronized
    private fun parse(line: String) {
        if (!running) return
        val m = reEv.find(line) ?: return
        val tm = reT.find(line)
        val t = if (tm != null) {
            tm.groupValues[1].toLong() * 1000L + tm.groupValues[2].padEnd(6, '0').take(6).toLong() / 1000L
        } else System.currentTimeMillis()
        val type = m.groupValues[1]
        val code = m.groupValues[2]
        val v = m.groupValues[3].toLong(16)
        if (type == "ABS") {
            when (code) {
                "ABS_MT_SLOT" -> slot = v.toInt().coerceIn(0, 31)
                "ABS_MT_TRACKING_ID" -> {
                    trackId[slot] = if (v == 0xFFFFFFFFL) -1 else v.toInt()
                    dirty[slot] = true
                }
                "ABS_MT_POSITION_X" -> { rawX[slot] = v.toInt(); hasX[slot] = true; dirty[slot] = true }
                "ABS_MT_POSITION_Y" -> { rawY[slot] = v.toInt(); hasY[slot] = true; dirty[slot] = true }
            }
        } else if (type == "SYN" && code == "SYN_REPORT") {
            sync(t)
        }
    }

    private fun sync(t: Long) {
        lastT = t
        for (s in 0 until 32) {
            if (!dirty[s]) continue
            dirty[s] = false
            val c = cur[s]
            if (trackId[s] >= 0) {
                if (!hasX[s] || !hasY[s]) { dirty[s] = true; continue }
                val (px, py) = toScreen(rawX[s], rawY[s])
                if (c == null) {
                    if (!ignored[s]) {
                        if (inExcluded(px, py)) {
                            ignored[s] = true
                        } else {
                            val nc = Contact(s, t)
                            nc.pts.add(intArrayOf(0, px, py))
                            cur[s] = nc
                            if (!started) { started = true; baseT = t; onFirstTouch() }
                        }
                    }
                } else {
                    c.t1 = t
                    val last = c.pts.last()
                    if (hypot((px - last[1]).toDouble(), (py - last[2]).toDouble()) >= 4.0) {
                        c.pts.add(intArrayOf((t - c.t0).toInt(), px, py))
                    }
                }
            } else {
                if (c != null) {
                    c.t1 = t
                    val last = c.pts.last()
                    val (px, py) = toScreen(rawX[s], rawY[s])
                    if (last[1] != px || last[2] != py) c.pts.add(intArrayOf((t - c.t0).toInt(), px, py))
                    done.add(c)
                    cur[s] = null
                }
                ignored[s] = false
                hasX[s] = false
                hasY[s] = false
            }
        }
    }

    private fun inExcluded(x: Int, y: Int): Boolean {
        for (r in excluded()) if (x >= r[0] && y >= r[1] && x <= r[2] && y <= r[3]) return true
        return false
    }

    /** Đổi toạ độ thô của tấm cảm ứng (luôn theo chiều dọc tự nhiên) sang pixel theo hướng màn hình hiện tại */
    private fun toScreen(rx: Int, ry: Int): Pair<Int, Int> {
        val nw = minOf(screenW, screenH).toFloat()
        val nh = maxOf(screenW, screenH).toFloat()
        val nx = rx / maxX.toFloat() * nw
        val ny = ry / maxY.toFloat() * nh
        return when (rotation) {
            Surface.ROTATION_90 -> Pair(ny.toInt(), (nw - nx).toInt())
            Surface.ROTATION_180 -> Pair((nw - nx).toInt(), (nh - ny).toInt())
            Surface.ROTATION_270 -> Pair((nh - ny).toInt(), nx.toInt())
            else -> Pair(nx.toInt(), ny.toInt())
        }
    }

    private fun buildSteps(): List<Step> {
        val thr = ctx.resources.displayMetrics.density * 10.0
        val lanes = LinkedHashMap<Int, Int>()
        val steps = ArrayList<Step>()
        for (c in done.sortedBy { it.t0 }) {
            val lane = lanes.getOrPut(c.slot) { lanes.size }
            val first = c.pts.first()
            val last = c.pts.last()
            var maxD = 0.0
            for (p in c.pts) maxD = maxOf(maxD, hypot((p[1] - first[1]).toDouble(), (p[2] - first[2]).toDouble()))
            val dur = maxOf(c.t1 - c.t0, 30L)
            val start = c.t0 - baseT
            if (maxD < thr) {
                steps.add(Step(0, lane, start, dur, first[1], first[2], first[1], first[2]))
            } else {
                steps.add(Step(1, lane, start, dur, first[1], first[2], last[1], last[2], c.pts))
            }
        }
        return steps
    }
}

package com.macropad.next

import android.content.Context
import android.os.SystemClock

/** Phát lại macro: bật máy chủ chạm (InputServer) qua ADB rồi gửi lệnh chạm đúng thời điểm */
object Player {
    @Volatile var playing = false
        private set
    @Volatile private var cancel = false
    @Volatile private var ready = false
    @Volatile private var alive = false
    private var server: Proc? = null

    private class Ev(val t: Long, val order: Int, val line: String)

    fun stop() { cancel = true }

    fun stopServer() {
        try { server?.close() } catch (_: Exception) { }
        server = null
        ready = false
        alive = false
    }

    /** Chạy ở luồng nền */
    private fun ensureServer(ctx: Context): Boolean {
        if (server != null && ready && alive) return true
        stopServer()
        if (!Adb.ensureConnected(ctx)) return false
        val apk = ctx.applicationInfo.sourceDir
        val cmd = "CLASSPATH=\"$apk\" app_process /system/bin com.macropad.next.InputServer"
        alive = true
        try {
            server = Adb.proc(ctx, cmd, { line ->
                if (line.trim() == "READY") ready = true else UiLog.add("[máy chủ] $line")
            }, { alive = false; ready = false })
        } catch (e: Throwable) {
            UiLog.add("Lỗi mở máy chủ chạm: ${e.javaClass.simpleName}: ${e.message}")
            alive = false
            return false
        }
        val end = System.currentTimeMillis() + 7000
        while (!ready && alive && System.currentTimeMillis() < end) Thread.sleep(100)
        if (!ready) {
            UiLog.add("Máy chủ chạm không khởi động được (xem các dòng [máy chủ])")
            stopServer()
            return false
        }
        UiLog.add("Máy chủ chạm đã sẵn sàng")
        return true
    }

    private fun send(line: String) {
        try {
            val o = server?.out ?: return
            o.write((line + "\n").toByteArray())
            o.flush()
        } catch (e: Exception) {
            UiLog.add("Lỗi gửi lệnh chạm: ${e.javaClass.simpleName}")
            alive = false
        }
    }

    /** Phát một lượt từ đầu tới cuối. onState nhận thông báo kết quả (gọi ở luồng nền). */
    fun play(ctx: Context, m: Macro, screenW: Int, screenH: Int, onState: (String) -> Unit) {
        if (playing) return
        playing = true
        cancel = false
        Thread {
            var msg = "Phát xong"
            try {
                if (!ensureServer(ctx)) {
                    msg = "Không bật được máy chủ chạm (xem nhật ký)"
                } else {
                    send("S " + ctx.getSharedPreferences("play", 0).getInt("mode", 0))
                    val evs = build(m, screenW, screenH)
                    val t0 = SystemClock.uptimeMillis()
                    for (e in evs) {
                        var wait = t0 + e.t - SystemClock.uptimeMillis()
                        while (wait > 0 && !cancel) {
                            Thread.sleep(minOf(wait, 40L))
                            wait = t0 + e.t - SystemClock.uptimeMillis()
                        }
                        if (cancel) { msg = "Đã dừng"; break }
                        send(e.line)
                    }
                }
            } catch (e: Throwable) {
                msg = "Lỗi phát: ${e.javaClass.simpleName}"
                UiLog.add("Lỗi phát: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                send("R")
                playing = false
                onState(msg)
            }
        }.start()
    }

    private fun build(m: Macro, sw: Int, sh: Int): List<Ev> {
        val fx = sw.toFloat() / m.sw
        val fy = sh.toFloat() / m.sh
        val out = ArrayList<Ev>()
        for (s in m.steps) {
            val id = 9 - minOf(s.finger, 9)
            val st = s.startMs
            val end = s.startMs + s.durMs
            when (s.type) {
                1 -> {
                    val pts = ArrayList<IntArray>()
                    if (s.path.size >= 2) {
                        for (p in s.path) pts.add(intArrayOf(p[0], (p[1] * fx).toInt(), (p[2] * fy).toInt()))
                    } else {
                        val n = maxOf(2, (s.durMs / 16).toInt())
                        for (i in 0..n) {
                            val k = i.toFloat() / n
                            pts.add(intArrayOf(
                                (s.durMs * k).toInt(),
                                ((s.x1 + (s.x2 - s.x1) * k) * fx).toInt(),
                                ((s.y1 + (s.y2 - s.y1) * k) * fy).toInt()
                            ))
                        }
                    }
                    out.add(Ev(st, 2, "D $id ${pts[0][1]} ${pts[0][2]}"))
                    for (i in 1 until pts.size) out.add(Ev(st + pts[i][0], 1, "M $id ${pts[i][1]} ${pts[i][2]}"))
                    out.add(Ev(end, 0, "U $id"))
                }
                2 -> {
                    var t = st
                    val x = (s.x1 * fx).toInt()
                    val y = (s.y1 * fy).toInt()
                    while (t < end) {
                        val up = minOf(t + maxOf(s.hold, 10), end)
                        out.add(Ev(t, 2, "D $id $x $y"))
                        out.add(Ev(up, 0, "U $id"))
                        t += maxOf(s.hold, 10) + maxOf(s.gap, 1)
                    }
                }
                else -> {
                    val x = (s.x1 * fx).toInt()
                    val y = (s.y1 * fy).toInt()
                    out.add(Ev(st, 2, "D $id $x $y"))
                    out.add(Ev(end, 0, "U $id"))
                }
            }
        }
        return out.sortedWith(compareBy({ it.t }, { it.order }))
    }
}

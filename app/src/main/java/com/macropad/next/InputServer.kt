package com.macropad.next

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

/**
 * Tiến trình nhỏ chạy bằng quyền shell (qua app_process, cách làm giống scrcpy).
 * Nhận lệnh chạm từ stdin và đưa cho hệ thống như ngón tay thật:
 *   D id x y  (chạm xuống)   M id x y  (di chuyển)   U id  (nhấc lên)   R (nhả tất cả)
 */
object InputServer {
    private var im: Any? = null
    private var inject: Method? = null
    private var threeArgs = false
    private var setDisplay: Method? = null
    private val ids = ArrayList<Int>()
    private val xs = HashMap<Int, Float>()
    private val ys = HashMap<Int, Float>()
    private var downTime = 0L
    private var errCount = 0

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            init()
            println("READY")
            System.out.flush()
        } catch (e: Throwable) {
            println("LỖI khởi tạo: ${e.javaClass.simpleName}: ${e.message}")
            e.cause?.let { println("  nguyên nhân: $it") }
            System.out.flush()
            return
        }
        try {
            val r = BufferedReader(InputStreamReader(System.`in`))
            while (true) {
                val line = r.readLine() ?: break
                handle(line)
            }
        } catch (e: Throwable) {
            println("LỖI chạy: ${e.javaClass.simpleName}: ${e.message}")
        }
        releaseAll()
    }

    private fun init() {
        val cls = try {
            Class.forName("android.hardware.input.InputManagerGlobal")
        } catch (e: ClassNotFoundException) {
            Class.forName("android.hardware.input.InputManager")
        }
        val gi = cls.getDeclaredMethod("getInstance")
        gi.isAccessible = true
        val inst = gi.invoke(null) ?: throw IllegalStateException("không lấy được InputManager")
        im = inst
        val intT = java.lang.Integer.TYPE
        inject = try {
            inst.javaClass.getMethod("injectInputEvent", InputEvent::class.java, intT)
        } catch (e: NoSuchMethodException) {
            threeArgs = true
            inst.javaClass.getMethod("injectInputEvent", InputEvent::class.java, intT, intT)
        }
        setDisplay = try {
            InputEvent::class.java.getMethod("setDisplayId", intT)
        } catch (e: Throwable) { null }
    }

    private fun handle(line: String) {
        val p = line.trim().split(' ')
        when (p[0]) {
            "D" -> if (p.size >= 4) down(p[1].toInt(), p[2].toFloat(), p[3].toFloat())
            "M" -> if (p.size >= 4) move(p[1].toInt(), p[2].toFloat(), p[3].toFloat())
            "U" -> if (p.size >= 2) up(p[1].toInt())
            "R" -> releaseAll()
        }
    }

    private fun down(id: Int, x: Float, y: Float) {
        if (ids.contains(id)) { move(id, x, y); return }
        if (ids.isEmpty()) downTime = SystemClock.uptimeMillis()
        ids.add(id)
        xs[id] = x
        ys[id] = y
        val idx = ids.size - 1
        val action = if (ids.size == 1) MotionEvent.ACTION_DOWN
        else MotionEvent.ACTION_POINTER_DOWN or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        send(action)
    }

    private fun move(id: Int, x: Float, y: Float) {
        if (!ids.contains(id)) return
        xs[id] = x
        ys[id] = y
        send(MotionEvent.ACTION_MOVE)
    }

    private fun up(id: Int) {
        val idx = ids.indexOf(id)
        if (idx < 0) return
        val action = if (ids.size == 1) MotionEvent.ACTION_UP
        else MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        send(action)
        ids.removeAt(idx)
        xs.remove(id)
        ys.remove(id)
    }

    private fun releaseAll() {
        while (ids.isNotEmpty()) up(ids[ids.size - 1])
    }

    private fun send(action: Int) {
        val n = ids.size
        val props = Array(n) { i ->
            val pp = MotionEvent.PointerProperties()
            pp.id = ids[i]
            pp.toolType = MotionEvent.TOOL_TYPE_FINGER
            pp
        }
        val coords = Array(n) { i ->
            val pc = MotionEvent.PointerCoords()
            pc.x = xs[ids[i]] ?: 0f
            pc.y = ys[ids[i]] ?: 0f
            pc.pressure = 1f
            pc.size = 1f
            pc
        }
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(
            downTime, now, action, n, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try { setDisplay?.invoke(ev, 0) } catch (_: Throwable) { }
        try {
            if (threeArgs) inject!!.invoke(im, ev, 0, -1) else inject!!.invoke(im, ev, 0)
        } catch (e: Throwable) {
            if (errCount++ < 5) println("LỖI chạm: ${e.cause ?: e}")
        }
        ev.recycle()
    }
}

package com.macropad.next

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Nhật ký hiển thị ngay trong app để chụp màn hình khi có lỗi */
object UiLog {
    private val h = Handler(Looper.getMainLooper())
    val lines = ArrayList<String>()
    var listener: ((String) -> Unit)? = null

    fun add(s: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + s
        h.post {
            lines.add(line)
            if (lines.size > 200) lines.removeAt(0)
            listener?.invoke(line)
        }
    }
}

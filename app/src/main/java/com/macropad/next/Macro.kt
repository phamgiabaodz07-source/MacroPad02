package com.macropad.next

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Một bước của macro. type: 0 = Chạm, 1 = Vuốt, 2 = Chạm liên tục */
class Step(
    val type: Int,
    val finger: Int,
    val startMs: Long,
    val durMs: Long,
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val path: List<IntArray> = emptyList()   // mỗi điểm: [thời gian ms, x, y]
)

class Macro(
    val id: String,
    var name: String,
    val pkg: String,
    val sw: Int,
    val sh: Int,
    val steps: List<Step>,
    val createdAt: Long
) {
    val totalMs: Long get() = steps.maxOfOrNull { it.startMs + it.durMs } ?: 0L
}

/** Lưu macro theo từng game (khoá = tên gói của game) */
object MacroStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("macros", 0)

    fun list(ctx: Context, pkg: String): List<Macro> {
        val out = ArrayList<Macro>()
        try {
            val arr = JSONArray(prefs(ctx).getString(pkg, "[]") ?: "[]")
            for (i in 0 until arr.length()) out.add(fromJson(arr.getJSONObject(i)))
        } catch (_: Exception) { }
        return out.sortedByDescending { it.createdAt }
    }

    fun add(ctx: Context, m: Macro) {
        val all = list(ctx, m.pkg).toMutableList()
        all.add(m)
        save(ctx, m.pkg, all)
    }

    fun delete(ctx: Context, pkg: String, id: String) {
        save(ctx, pkg, list(ctx, pkg).filter { it.id != id })
    }

    fun rename(ctx: Context, pkg: String, id: String, name: String) {
        val all = list(ctx, pkg)
        all.forEach { if (it.id == id) it.name = name }
        save(ctx, pkg, all)
    }

    private fun save(ctx: Context, pkg: String, all: List<Macro>) {
        val arr = JSONArray()
        all.forEach { arr.put(toJson(it)) }
        prefs(ctx).edit().putString(pkg, arr.toString()).apply()
    }

    private fun toJson(m: Macro): JSONObject {
        val o = JSONObject()
        o.put("id", m.id)
        o.put("name", m.name)
        o.put("pkg", m.pkg)
        o.put("sw", m.sw)
        o.put("sh", m.sh)
        o.put("at", m.createdAt)
        val steps = JSONArray()
        m.steps.forEach { s ->
            val so = JSONObject()
            so.put("type", s.type)
            so.put("f", s.finger)
            so.put("t", s.startMs)
            so.put("d", s.durMs)
            so.put("x1", s.x1)
            so.put("y1", s.y1)
            so.put("x2", s.x2)
            so.put("y2", s.y2)
            if (s.path.isNotEmpty()) {
                val pa = JSONArray()
                s.path.forEach { pt -> pa.put(JSONArray().put(pt[0]).put(pt[1]).put(pt[2])) }
                so.put("p", pa)
            }
            steps.put(so)
        }
        o.put("steps", steps)
        return o
    }

    private fun fromJson(o: JSONObject): Macro {
        val steps = ArrayList<Step>()
        val arr = o.getJSONArray("steps")
        for (i in 0 until arr.length()) {
            val so = arr.getJSONObject(i)
            val path = ArrayList<IntArray>()
            val pa = so.optJSONArray("p")
            if (pa != null) {
                for (j in 0 until pa.length()) {
                    val pt = pa.getJSONArray(j)
                    path.add(intArrayOf(pt.getInt(0), pt.getInt(1), pt.getInt(2)))
                }
            }
            steps.add(Step(
                so.getInt("type"), so.getInt("f"), so.getLong("t"), so.getLong("d"),
                so.getInt("x1"), so.getInt("y1"), so.getInt("x2"), so.getInt("y2"), path
            ))
        }
        return Macro(
            o.getString("id"), o.getString("name"), o.getString("pkg"),
            o.getInt("sw"), o.getInt("sh"), steps, o.optLong("at", 0L)
        )
    }
}

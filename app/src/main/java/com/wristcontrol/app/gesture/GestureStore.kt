package com.wristcontrol.app.gesture

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 手势模板的本地存储。
 *
 * 存手机端而不是手环端：手环存储空间小、重装 RPK 会丢；
 * 手机端重装也只是覆盖安装，数据还在。启动时把模板同步给手环。
 */
class GestureStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("wristcontrol_gestures", Context.MODE_PRIVATE)

    fun load(): List<Gesture> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Gesture(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    path = GestureCodec.decodePath(o.optString("path")),
                    action = o.optString("action"),
                    arg = o.optString("arg"),
                )
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun save(list: List<Gesture>) {
        val arr = JSONArray()
        for (g in list) {
            arr.put(JSONObject().apply {
                put("id", g.id)
                put("name", g.name)
                put("path", GestureCodec.encodePath(g.path))
                put("action", g.action)
                put("arg", g.arg)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun add(g: Gesture): List<Gesture> {
        val list = load().filter { it.id != g.id } + g
        save(list)
        return list
    }

    fun remove(id: String): List<Gesture> {
        val list = load().filter { it.id != id }
        save(list)
        return list
    }

    private companion object {
        const val KEY = "gestures"
    }
}

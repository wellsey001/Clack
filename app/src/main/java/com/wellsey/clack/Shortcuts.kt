package com.wellsey.clack

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Text shortcuts: type the trigger ("@@") and the suggestion bar offers the full text. */
class Shortcuts private constructor(context: Context) {

    data class Shortcut(val trigger: String, val text: String)

    private val sp = context.applicationContext.getSharedPreferences("clack_shortcuts", Context.MODE_PRIVATE)
    private var cache: List<Shortcut>? = null

    @Synchronized
    fun all(): List<Shortcut> {
        cache?.let { return it }
        val list = ArrayList<Shortcut>()
        try {
            val arr = JSONArray(sp.getString("items", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Shortcut(o.getString("trigger"), o.getString("text")))
            }
        } catch (_: Exception) {
        }
        cache = list
        return list
    }

    /** The shortcut whose trigger is exactly [typed] (ignoring case), if any. */
    fun match(typed: String): Shortcut? {
        if (typed.isEmpty()) return null
        return all().firstOrNull { it.trigger.equals(typed, ignoreCase = true) }
    }

    @Synchronized
    fun put(trigger: String, text: String) {
        val list = all().filterNot { it.trigger.equals(trigger, ignoreCase = true) } + Shortcut(trigger, text)
        save(list)
    }

    @Synchronized
    fun remove(trigger: String) {
        save(all().filterNot { it.trigger.equals(trigger, ignoreCase = true) })
    }

    private fun save(list: List<Shortcut>) {
        val arr = JSONArray()
        list.sortedBy { it.trigger.lowercase() }.forEach { arr.put(JSONObject().put("trigger", it.trigger).put("text", it.text)) }
        sp.edit().putString("items", arr.toString()).apply()
        cache = list.sortedBy { it.trigger.lowercase() }
    }

    companion object {
        @Volatile private var instance: Shortcuts? = null
        fun get(context: Context): Shortcuts =
            instance ?: synchronized(this) { instance ?: Shortcuts(context).also { instance = it } }
    }
}

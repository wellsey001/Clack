package com.wellsey.clack

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Recently copied text, kept on the phone. Items clear after an hour unless pinned.
 * Clips marked sensitive by the app that copied them (passwords, on Android 13+)
 * are never stored.
 */
class ClipHistory private constructor(context: Context) {

    class Item(val text: String, val time: Long, var pinned: Boolean)

    private val file = File(context.applicationContext.filesDir, "clipboard.json")
    private val items = ArrayList<Item>()

    init {
        try {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    items.add(Item(o.getString("t"), o.getLong("time"), o.optBoolean("pin")))
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Pinned first, then newest first; expired unpinned items dropped. */
    @Synchronized
    fun list(): List<Item> {
        prune()
        return items.sortedWith(compareByDescending<Item> { it.pinned }.thenByDescending { it.time })
    }

    @Synchronized
    fun add(clip: ClipData?, context: Context) {
        if (clip == null || clip.itemCount == 0) return
        if (isSensitive(clip.description)) return
        val text = try {
            clip.getItemAt(0).coerceToText(context)?.toString()
        } catch (_: Exception) {
            null
        }
        if (text.isNullOrBlank() || text.length > MAX_LENGTH) return
        val existing = items.firstOrNull { it.text == text }
        if (existing != null) items.remove(existing)
        items.add(Item(text, System.currentTimeMillis(), existing?.pinned ?: false))
        prune()
        save()
    }

    @Synchronized
    fun togglePin(item: Item) {
        items.firstOrNull { it.text == item.text }?.let { it.pinned = !it.pinned }
        save()
    }

    @Synchronized
    fun delete(item: Item) {
        items.removeAll { it.text == item.text }
        save()
    }

    /** Clears everything that isn't pinned. */
    @Synchronized
    fun clearUnpinned() {
        items.removeAll { !it.pinned }
        save()
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        items.removeAll { !it.pinned && it.time < cutoff }
        // keep the newest unpinned items only
        val unpinned = items.filter { !it.pinned }.sortedByDescending { it.time }
        if (unpinned.size > MAX_ITEMS) items.removeAll(unpinned.drop(MAX_ITEMS).toSet())
    }

    private fun save() {
        try {
            val arr = JSONArray()
            for (it in items) arr.put(JSONObject().put("t", it.text).put("time", it.time).put("pin", it.pinned))
            file.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    private fun isSensitive(d: ClipDescription?): Boolean {
        val extras = d?.extras ?: return false
        if (Build.VERSION.SDK_INT >= 33 && extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)) return true
        // Older convention used by some password managers
        return extras.getBoolean("android.content.extra.IS_SENSITIVE")
    }

    companion object {
        private const val KEEP_MS = 60 * 60 * 1000L
        private const val MAX_ITEMS = 20
        private const val MAX_LENGTH = 20_000

        @Volatile private var instance: ClipHistory? = null
        fun get(context: Context): ClipHistory =
            instance ?: synchronized(this) { instance ?: ClipHistory(context).also { instance = it } }
    }
}

package com.hwezah.grabber

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Clip(val id: Long, val text: String, val time: Long, val pinned: Boolean = false)

/** Clipboard history: everything Grabber has caught, newest first, pinned ones on top. */
object Clips {
    private lateinit var file: File
    private lateinit var ctx: Context
    private val _items = MutableStateFlow<List<Clip>>(emptyList())
    val items: StateFlow<List<Clip>> = _items.asStateFlow()

    fun init(context: Context) {
        if (::file.isInitialized) return
        ctx = context.applicationContext
        file = File(ctx.filesDir, "clips.json")
        _items.value = runCatching {
            val a = JSONArray(file.readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map { Clip(it.getLong("id"), it.getString("text"), it.getLong("time"), it.optBoolean("pinned")) }
        }.getOrDefault(emptyList())
    }

    /** Adds [text], or moves it to the top if it's already there. */
    fun add(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        _items.update { list ->
            val old = list.firstOrNull { it.text == t }
            listOf(Clip(old?.id ?: System.currentTimeMillis(), t, System.currentTimeMillis(), old?.pinned ?: false)) + list.filterNot { it.text == t }
        }
        save()
    }

    fun pin(id: Long) {
        _items.update { list -> list.map { if (it.id == id) it.copy(pinned = !it.pinned) else it } }
        save()
    }

    fun delete(id: Long) {
        _items.update { list -> list.filterNot { it.id == id } }
        save()
    }

    /** Undo a delete: puts [clip] back as it was. */
    fun restore(clip: Clip) {
        _items.update { list -> if (list.any { it.id == clip.id }) list else (list + clip).sortedByDescending { it.time } }
        save()
    }

    fun clearUnpinned() {
        _items.update { list -> list.filter { it.pinned } }
        save()
    }

    private fun save() = synchronized(this) {
        val a = JSONArray()
        (_items.value.filter { it.pinned } + _items.value.filterNot { it.pinned }.take(Prefs.historyLimit)).forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("time", it.time).put("pinned", it.pinned)) }
        val tmp = File(file.parentFile, "clips.json.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file)
    }
}

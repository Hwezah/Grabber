package com.hwezah.grabber

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A page you bookmarked or visited. */
data class Place(val url: String, val title: String, val time: Long)

/** The browser's bookmarks and history, kept on this phone. History keeps the last 500 pages. */
object Places {
    val bookmarks = mutableStateListOf<Place>()
    val history = mutableStateListOf<Place>()
    private var dir: File? = null

    fun init(context: Context) {
        if (dir != null) return
        dir = context.applicationContext.filesDir
        bookmarks.addAll(load("bookmarks.json"))
        history.addAll(load("history.json"))
    }

    private fun load(name: String): List<Place> = runCatching {
        val a = JSONArray(File(dir, name).readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { Place(it.getString("url"), it.optString("title"), it.optLong("time")) }
    }.getOrDefault(emptyList())

    private fun save(name: String, list: List<Place>) {
        val d = dir ?: return
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("url", it.url).put("title", it.title).put("time", it.time)) }
        runCatching { File(d, name).writeText(a.toString()) }
    }

    fun isBookmarked(url: String) = bookmarks.any { it.url == url }

    /** Bookmarks [url], or removes the bookmark if it's there. */
    fun toggleBookmark(url: String, title: String) {
        if (url.isBlank()) return
        if (!bookmarks.removeAll { it.url == url }) bookmarks.add(0, Place(url, title.ifBlank { url }, System.currentTimeMillis()))
        save("bookmarks.json", bookmarks.toList())
    }

    fun removeBookmark(p: Place) {
        bookmarks.remove(p)
        save("bookmarks.json", bookmarks.toList())
    }

    /** A page finished loading: it goes to the top of history. */
    fun visited(url: String, title: String) {
        if (url.isBlank() || url == "about:blank") return
        history.removeAll { it.url == url }
        history.add(0, Place(url, title.ifBlank { url }, System.currentTimeMillis()))
        while (history.size > 500) history.removeAt(history.lastIndex)
        save("history.json", history.toList())
    }

    fun removeVisit(p: Place) {
        history.remove(p)
        save("history.json", history.toList())
    }

    fun clearHistory() {
        history.clear()
        save("history.json", emptyList())
    }
}

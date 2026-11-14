package com.hwezah.grabber

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Noticing new clipboard content, without popping up twice for the same thing. */
object Capture {
    /** Text the in-app sheet should offer (set when Grabber opens with a new link copied). */
    var pending by mutableStateOf<String?>(null)

    private fun prefs(c: Context) = c.getSharedPreferences("capture", Context.MODE_PRIVATE)

    /** The clipboard's text if it's new since the last look, else null. Call while Grabber has focus. */
    fun fresh(context: Context): String? {
        val text = readClipboard(context).trim()
        if (text.isEmpty()) return null
        val hash = text.hashCode()
        if (prefs(context).getInt("last", 0) == hash) return null
        prefs(context).edit().putInt("last", hash).apply()
        return text
    }

    /** The label the copying app gave the clip, when it's a real title rather than a generic word. */
    fun label(context: Context): String? {
        val l = runCatching {
            context.getSystemService(android.content.ClipboardManager::class.java)?.primaryClipDescription?.label?.toString()
        }.getOrNull()?.trim() ?: return null
        val generic = setOf("text", "label", "link", "url", "uri", "copied", "copy", "clipboard", "plain text", "simple text", "grabber", "magnet")
        return l.takeIf { it.length in 3..150 && it.lowercase() !in generic && !it.contains("://") }
    }

    /** Words copied along with a single link ("Title https://…"), as a name for it. */
    fun nameAround(text: String): String? {
        var rest = text
        LinkFinder.find(text).forEach { rest = rest.replace(it.text, " ") }
        rest = rest.replace(Regex("""\s+"""), " ").trim().trim('-', '|', ':', '·', '—').trim()
        return rest.takeIf { it.length in 3..150 }
    }

    /** Remember [text] as seen, so it doesn't pop up again. */
    fun seen(context: Context, text: String) = prefs(context).edit().putInt("last", text.trim().hashCode()).apply()

    /** Whether the user has switched on Grabber's copy watcher in Accessibility settings. */
    fun watcherOn(context: Context): Boolean {
        val on = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return on.split(':').any { it.startsWith(context.packageName + "/") }
    }
}

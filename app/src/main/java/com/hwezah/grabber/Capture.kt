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
    /** The title that came with [pending] (the copying app's label), used as the file name. */
    var pendingTitle by mutableStateOf<String?>(null)

    /** Headers a download needs (the browser's page as Referer, its cookies). */
    var pendingHeaders by mutableStateOf<Map<String, String>>(emptyMap())

    /** Offers [text] in the in-app sheet, with its [title] and [headers] if known. */
    /** The sheet open now was opened by Grabber's browser: its downloads go under Browser/. */
    var fromBrowser = false

    fun offer(text: String, title: String?, headers: Map<String, String> = emptyMap(), browser: Boolean = false) {
        // Looking for a fresh link to a stopped download: a match carries it on instead of starting another.
        if (browser && Rescue.target != null && Rescue.offer(text.trim(), headers) { show(text, title, headers, browser) }) return
        show(text, title, headers, browser)
    }

    private fun show(text: String, title: String?, headers: Map<String, String>, browser: Boolean) {
        fromBrowser = browser
        pendingTitle = title
        pendingHeaders = headers
        pending = text
    }

    private fun prefs(c: Context) = c.getSharedPreferences("capture", Context.MODE_PRIVATE)

    /**
     * The clipboard's text if it's a new copy, else null. Call while Grabber has focus. New means: copied after the
     * last copy Grabber handled (Android stamps each copy with its time), not one of the last 50 handled, and, when
     * [maxAgeMs] is set, copied within that long ago, so an old copy never pops up when Grabber opens.
     */
    fun fresh(context: Context, maxAgeMs: Long = 0): String? {
        val text = readClipboard(context).trim()
        if (text.isEmpty()) return null
        val stamp = runCatching {
            context.getSystemService(android.content.ClipboardManager::class.java)?.primaryClipDescription?.timestamp
        }.getOrNull() ?: 0L
        val p = prefs(context)
        val handled = p.getString("handled", "").orEmpty().split(',').filter { it.isNotEmpty() }
        val hash = text.hashCode().toString()
        val old = hash in handled ||
            stamp > 0 && stamp <= p.getLong("stamp", 0) ||
            stamp > 0 && maxAgeMs > 0 && System.currentTimeMillis() - stamp > maxAgeMs
        remember(context, text, stamp)
        return if (old) null else text
    }

    private fun remember(context: Context, text: String, stamp: Long = 0) {
        val p = prefs(context)
        val handled = p.getString("handled", "").orEmpty().split(',').filter { it.isNotEmpty() }
        val hash = text.trim().hashCode().toString()
        val next = (listOf(hash) + handled.filter { it != hash }).take(50)
        p.edit().putString("handled", next.joinToString(",")).apply {
            if (stamp > p.getLong("stamp", 0)) putLong("stamp", stamp)
        }.apply()
    }

    /** Remember [text] as seen, so it doesn't pop up again. */
    fun seen(context: Context, text: String) = remember(context, text)

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


    /** Whether the user has switched on Grabber's copy watcher in Accessibility settings. */
    fun watcherOn(context: Context): Boolean {
        val on = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return on.split(':').any { it.startsWith(context.packageName + "/") }
    }
}

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

    /** Remember [text] as seen, so it doesn't pop up again. */
    fun seen(context: Context, text: String) = prefs(context).edit().putInt("last", text.trim().hashCode()).apply()

    /** Whether the user has switched on Grabber's copy watcher in Accessibility settings. */
    fun watcherOn(context: Context): Boolean {
        val on = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return on.split(':').any { it.startsWith(context.packageName + "/") }
    }
}

package com.hwezah.grabber

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hwezah.grabber.design.Ink

/**
 * The popup over other apps: a see-through screen holding just the capture sheet.
 * Opened by the copy watcher, the Quick Settings tile, Share → Grabber and the select-text menu.
 * Android only lets an app read the clipboard while it has focus, which this screen briefly takes.
 */
class CaptureActivity : ComponentActivity() {
    private var text by mutableStateOf<String?>(null)
    /** Files shared to us (a WhatsApp status, photos, videos). */
    private var files by mutableStateOf<List<android.net.Uri>>(emptyList())
    /** What the sending app told us about the link: a title, and headers it needs (referer, cookies…). */
    private var title by mutableStateOf<String?>(null)
    private var headers by mutableStateOf<Map<String, String>>(emptyMap())
    private var looked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Downloads.init(this)
        Clips.init(this)
        Ink.use(Prefs.dark)
        streams(intent).takeIf { it.isNotEmpty() }?.let { files = it } ?: given(intent)?.let { show(it) }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Ink.red)) {
                if (files.isNotEmpty()) SaveFilesSheet(files) { finish() }
                else text?.let { CaptureSheet(it, title = title, headers = headers) { finish() } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        looked = false
        streams(intent).takeIf { it.isNotEmpty() }?.let { files = it } ?: given(intent)?.let { show(it) }
    }

    /** Text handed to us directly: shared, or selected with "Grabber" in the text menu. */
    private fun given(i: Intent): String? = when (i.action) {
        Intent.ACTION_VIEW -> i.dataString?.also { extras(i) }
        // The subject names what was shared: a page's title, or "The Penguin · season 1" for a batch of links.
        Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)?.also { title = i.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { s -> s.isNotBlank() } }
        Intent.ACTION_PROCESS_TEXT -> i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun streams(i: Intent): List<android.net.Uri> = when (i.action) {
        Intent.ACTION_SEND -> listOfNotNull(i.getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri)
        Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM).orEmpty()
        else -> emptyList()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || looked || text != null || files.isNotEmpty()) return
        looked = true
        val fromTile = intent.getBooleanExtra(FROM_TILE, false)
        // The tile always shows what's there; the watcher only what's new.
        val clip = if (fromTile) readClipboard(this).trim().also { Capture.seen(this, it) }.ifEmpty { null } else Capture.fresh(this)
        if (clip == null) {
            if (fromTile) Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (LinkFinder.find(clip).isEmpty() && !fromTile) {
            // Plain text: kept quietly, no popup over what you're doing.
            if (Prefs.savePlainText) {
                Clips.add(clip, Capture.label(this))
                Toast.makeText(this, "Saved to Grabber", Toast.LENGTH_SHORT).show()
            }
            finish()
            return
        }
        if (title == null) title = Capture.label(this)
        show(clip)
    }

    /** WatchHub, CloudStream and others send "title", "headers" (key, value, key, value…) and "referer". */
    private fun extras(i: Intent) {
        title = i.getStringExtra("title") ?: i.getStringExtra(Intent.EXTRA_TITLE)
        val h = LinkedHashMap<String, String>()
        i.getStringArrayExtra("headers")?.toList()?.chunked(2)?.forEach { if (it.size == 2) h[it[0]] = it[1] }
        i.getBundleExtra("android.media.intent.extra.HTTP_HEADERS")?.let { b -> b.keySet().forEach { k -> b.getString(k)?.let { h[k] = it } } }
        i.getStringExtra("referer")?.let { h["Referer"] = it }
        headers = h
    }

    private fun show(t: String) {
        Clips.add(t, title)
        Capture.seen(this, t)
        text = t
    }

    companion object {
        const val FROM_TILE = "fromTile"
    }
}

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
    private var looked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Downloads.init(this)
        Clips.init(this)
        Ink.use(Prefs.dark)
        given(intent)?.let { show(it) }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Ink.red)) {
                text?.let { CaptureSheet(it) { finish() } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        looked = false
        given(intent)?.let { show(it) }
    }

    /** Text handed to us directly: shared, or selected with "Grabber" in the text menu. */
    private fun given(i: Intent): String? = when (i.action) {
        Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_PROCESS_TEXT -> i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        else -> null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || looked || text != null) return
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
            Clips.add(clip)
            Toast.makeText(this, "Saved to Grabber", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        show(clip)
    }

    private fun show(t: String) {
        Clips.add(t)
        Capture.seen(this, t)
        text = t
    }

    companion object {
        const val FROM_TILE = "fromTile"
    }
}

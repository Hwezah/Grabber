package com.hwezah.grabber

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.hwezah.grabber.design.Ink

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Updater.app = applicationContext
        // Looks for a newer build every 5 minutes while Grabber is on screen.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { kotlinx.coroutines.delay(5 * 60_000L); Updater.check() }
            }
        }
        intent?.getStringExtra(OPEN_URL)?.let { Browser.open(it) }
        Downloads.init(this)
        Clips.init(this)
        Places.init(this)
        // A VPN left on comes back on.
        Vpn.init(this)
        Ink.use(Prefs.dark)
        // Downloads left waiting when the app was closed carry on.
        Downloads.kick()
        // Accidental "Video (1)" folders folded back; old top-level Movies, Shows and Other moved in;
        // finished videos still loose in Video/ sorted into Shows, Movies and YouTube, every start.
        kotlin.concurrent.thread(name = "grabber-shelve") { Folders.housekeep(applicationContext) }
        setContent {
            // Status and navigation bar icons follow the theme: light icons on the dark page, dark on paper.
            LaunchedEffect(Prefs.dark) {
                Ink.use(Prefs.dark)
                val bar = if (Prefs.dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(bar, bar)
            }
            App()
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(OPEN_URL)?.let { Browser.open(it) }
    }

    companion object {
        /** A page to open in Grabber's browser (a download taken from another browser). */
        const val OPEN_URL = "openUrl"
    }

    override fun onResume() {
        super.onResume()
        Updater.check()
    }

    // Opening Grabber with a new link on the clipboard offers it, as 1DM does.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        // On opening: only a copy from the last 10 minutes, never an old one.
        val text = Capture.fresh(this, maxAgeMs = 10 * 60_000L) ?: return
        val links = LinkFinder.find(text).isNotEmpty()
        val label = Capture.label(this)
        if (links || Prefs.savePlainText) Clips.add(text, label)
        if (links && Prefs.offerOnOpen) Capture.offer(text, label)
    }
}

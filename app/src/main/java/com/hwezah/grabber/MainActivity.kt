package com.hwezah.grabber

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import com.hwezah.grabber.design.Ink

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Downloads.init(this)
        Clips.init(this)
        Places.init(this)
        Ink.use(Prefs.dark)
        // Downloads left waiting when the app was closed carry on.
        Downloads.kick()
        // Accidental "Other (1)" folders folded back into "Other".
        kotlin.concurrent.thread(name = "grabber-merge") { runCatching { Folders.mergeDuplicates(applicationContext) } }
        // Episodes saved before shows had folders: sorted once, in the background.
        val sp = getSharedPreferences("grabber", MODE_PRIVATE)
        if (!sp.getBoolean("shelved2", false)) kotlin.concurrent.thread(name = "grabber-shelve") {
            runCatching { Folders.shelveAll(applicationContext) }.onSuccess { sp.edit().putBoolean("shelved2", true).apply() }
        }
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

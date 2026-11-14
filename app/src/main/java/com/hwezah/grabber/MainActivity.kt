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
        Ink.use(Prefs.dark)
        // Downloads left waiting when the app was closed carry on.
        Downloads.kick()
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
        val text = Capture.fresh(this) ?: return
        val links = LinkFinder.find(text).isNotEmpty()
        if (links || Prefs.savePlainText) Clips.add(text)
        if (links && Prefs.offerOnOpen) Capture.pending = text
    }
}

package com.hwezah.grabber

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as L
import com.hwezah.grabber.design.Ink
import com.hwezah.grabber.design.Lc
import com.hwezah.grabber.design.Tap
import com.hwezah.grabber.design.Type
import com.hwezah.grabber.design.frost
import com.hwezah.grabber.design.grain

enum class Tab(val label: String, @DrawableRes val icon: Int) {
    Downloads("Downloads", L.drawable.lucide_ic_download),
    Clipboard("Clipboard", L.drawable.lucide_ic_clipboard_list),
    Settings("Settings", L.drawable.lucide_ic_settings),
}

@Composable
fun App() {
    val scheme = if (Prefs.dark) darkColorScheme(primary = Ink.red, background = Ink.bg, surface = Ink.surface)
    else lightColorScheme(primary = Ink.red, background = Ink.bg, surface = Ink.surface)
    MaterialTheme(colorScheme = scheme) {
        var tab by rememberSaveable { mutableStateOf(Tab.Downloads) }
        Box(Modifier.fillMaxSize().background(Ink.bg)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                        when (t) {
                            Tab.Downloads -> DownloadsPage()
                            Tab.Clipboard -> ClipboardPage()
                            Tab.Settings -> SettingsPage()
                        }
                    }
                }
                if (!Prefs.pillBar) TabBar(tab) { tab = it }
            }
            if (Prefs.pillBar) PillBar(tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
            UpdateBar(Modifier.align(Alignment.TopCenter))
            Capture.pending?.let { CaptureSheet(it) { Capture.pending = null } }
        }
    }
}

/** The tabs along the bottom: the one you are on white with a red tick. */
@Composable
private fun TabBar(tab: Tab, onTab: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Ink.bg).navigationBarsPadding().height(58.dp)) {
        Tab.entries.forEach { t ->
            val on = t == tab
            Tap({ onTab(t) }, Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(0.dp)) {
                Box {
                    if (on) Box(
                        Modifier.align(Alignment.TopCenter).width(22.dp).height(3.dp)
                            .clip(RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp)).background(Ink.red),
                    )
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Lc(t.icon, tint = if (on) Ink.text else Ink.faint, size = 22.dp)
                        Text(
                            t.label, Modifier.padding(top = 3.dp),
                            style = Type.meta.copy(fontSize = 11.sp, color = if (on) Ink.text else Ink.faint, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                        )
                    }
                }
            }
        }
    }
}

/** The tabs as a floating pill: icons only, the chosen one in a lighter capsule. */
@Composable
private fun PillBar(tab: Tab, onTab: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.navigationBarsPadding().padding(bottom = 12.dp).frost(RoundedCornerShape(50), Ink.bg).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { t ->
            val on = t == tab
            Tap({ onTab(t) }, shape = RoundedCornerShape(50)) {
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(if (on) Ink.text.copy(alpha = 0.16f) else Color.Transparent)
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Lc(t.icon, tint = if (on) Ink.text else Ink.muted, size = 24.dp) }
            }
        }
    }
}

/** Room under a page's last row for the floating pill bar. */
val BottomRoom get() = if (Prefs.pillBar) 96.dp else 16.dp

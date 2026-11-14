package com.hwezah.grabber

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as L
import com.hwezah.grabber.design.BottomSheet
import com.hwezah.grabber.design.Button
import com.hwezah.grabber.design.CARD
import com.hwezah.grabber.design.Chip
import com.hwezah.grabber.design.EDGE
import com.hwezah.grabber.design.Heading
import com.hwezah.grabber.design.Ink
import com.hwezah.grabber.design.Lc
import com.hwezah.grabber.design.Note
import com.hwezah.grabber.design.Overline
import com.hwezah.grabber.design.SpreadOut
import com.hwezah.grabber.design.Toggle
import com.hwezah.grabber.design.Type
import com.hwezah.grabber.design.glass

@Composable
fun ClipboardPage() {
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Heading("Clipboard")
        val shape = RoundedCornerShape(50)
        Row(
            Modifier.padding(horizontal = EDGE).fillMaxWidth().height(46.dp).glass(shape).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Lc(L.drawable.lucide_ic_search, tint = Ink.faint, size = 18.dp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search what you copied", style = Type.label.copy(color = Ink.faint))
                BasicTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = Type.label, cursorBrush = SolidColor(Ink.red))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = EDGE * 3), contentAlignment = Alignment.Center) {
            Note("Everything Grabber catches from your clipboard will be kept here: pin, copy again, share or delete.")
        }
        Spacer(Modifier.height(BottomRoom))
    }
}

@Composable
fun SettingsPage() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Heading("Settings")
        Column(Modifier.padding(horizontal = EDGE)) {
            Overline("Downloads")
            Group {
                FolderRow()
                Text("Connections per file", Modifier.padding(top = 10.dp), style = Type.label.copy(fontWeight = FontWeight.SemiBold))
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = SpreadOut) {
                    listOf(8, 16, 32, 48, 64).forEach { n -> Chip("$n", Prefs.connections == n, { Prefs.useConnections(n) }) }
                }
                Text("At the same time", Modifier.padding(top = 6.dp), style = Type.label.copy(fontWeight = FontWeight.SemiBold))
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = SpreadOut) {
                    (1..5).forEach { n -> Chip("$n", Prefs.parallel == n, { Prefs.useParallel(n) }) }
                }
                Text("Speed limit", Modifier.padding(top = 6.dp), style = Type.label.copy(fontWeight = FontWeight.SemiBold))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), horizontalArrangement = SpreadOut) {
                    listOf(0L to "Off", 1L to "1 MB/s", 5L to "5 MB/s", 10L to "10 MB/s").forEach { (mb, label) ->
                        Chip(label, Prefs.speedLimit == mb * 1024 * 1024, { Prefs.useSpeedLimit(mb * 1024 * 1024) })
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Overline("Look")
            Group {
                SettingRow("Dark theme", "Near-black page; off for warm paper") { Toggle(Prefs.dark, Prefs::useDark) }
                SettingRow("Floating tab bar", "Glass pill over the page; off for a bar along the bottom") { Toggle(Prefs.pillBar, Prefs::usePillBar) }
            }
            Spacer(Modifier.height(20.dp))
            Overline("Accents")
            Group {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = SpreadOut) {
                    Swatch("Main", Ink.red)
                    Swatch("Downloads", Ink.green)
                    Swatch("Torrents", Ink.orange)
                }
            }
            Spacer(Modifier.height(20.dp))
            Overline("About")
            Group {
                SettingRow("Grabber ${BuildConfig.VERSION_NAME}", "Builds come from GitHub; each one installs over the last.") {}
            }
            Spacer(Modifier.height(BottomRoom))
        }
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp)).padding(horizontal = CARD, vertical = 6.dp)) { content() }
}

@Composable
private fun SettingRow(title: String, hint: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = Type.label.copy(fontWeight = FontWeight.SemiBold))
            Text(hint, style = Type.meta)
        }
        trailing()
    }
}

@Composable
private fun Swatch(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = Type.meta.copy(color = Ink.text))
    }
}

/** The clipboard's text. Android only allows this while Grabber is on screen. */
fun readClipboard(context: android.content.Context): String {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java) ?: return ""
    val clip = cm.primaryClip ?: return ""
    if (clip.itemCount == 0) return ""
    return clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
}

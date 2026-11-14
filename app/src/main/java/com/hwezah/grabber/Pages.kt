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

private val CATEGORIES = listOf("All", "Video", "Audio", "Docs", "Archives", "Torrents", "Other")

@Composable
fun DownloadsPage() {
    var filter by rememberSaveable { mutableStateOf("All") }
    var adding by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Heading("Downloads") { Button("Add", { adding = true }, icon = L.drawable.lucide_ic_plus, accent = Ink.red, compact = true) }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = EDGE), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CATEGORIES.forEach { c -> Chip(c, c == filter, { filter = c }) }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = EDGE * 3), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(72.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
                        Lc(L.drawable.lucide_ic_download, tint = Ink.green, size = 30.dp)
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Nothing downloading yet", style = Type.title)
                    Spacer(Modifier.height(6.dp))
                    Note("Copy a link anywhere, or tap Add and paste one. Downloads arrive in the next build.")
                }
            }
            Spacer(Modifier.height(BottomRoom))
        }
        if (adding) AddSheet { adding = false }
    }
}

/** Paste text with one or many links; shows what Grabber found in it. */
@Composable
private fun AddSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(readClipboard(context)) }
    val found = remember(text) { LinkFinder.find(text) }
    BottomSheet("Add links", onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            val shape = RoundedCornerShape(14.dp)
            Box(Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(shape).background(Ink.text.copy(alpha = 0.07f)).border(1.dp, Ink.stroke, shape).padding(12.dp)) {
                if (text.isEmpty()) Text("Paste links, a magnet, or any text with links in it", style = Type.label.copy(color = Ink.faint))
                BasicTextField(text, { text = it }, Modifier.fillMaxWidth(), textStyle = Type.label, cursorBrush = SolidColor(Ink.red))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = SpreadOut) {
                Button("Paste", { text = readClipboard(context) }, icon = L.drawable.lucide_ic_clipboard_paste, compact = true)
                Button("Clear", { text = "" }, icon = L.drawable.lucide_ic_x, compact = true)
            }
            Spacer(Modifier.height(14.dp))
            Overline(if (found.isEmpty()) "No links found" else "${found.size} found")
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                found.forEach { f ->
                    val magnet = f is Found.Magnet
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Lc(if (magnet) L.drawable.lucide_ic_magnet else L.drawable.lucide_ic_link, tint = if (magnet) Ink.orange else Ink.green, size = 18.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(f.text, Modifier.weight(1f), style = Type.meta.copy(color = Ink.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

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
            Overline("Look")
            Group {
                SettingRow("Dark theme", "Near-black page; off for warm paper") { Toggle(Prefs.dark, Prefs::setDark) }
                SettingRow("Floating tab bar", "Glass pill over the page; off for a bar along the bottom") { Toggle(Prefs.pillBar, Prefs::setPillBar) }
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

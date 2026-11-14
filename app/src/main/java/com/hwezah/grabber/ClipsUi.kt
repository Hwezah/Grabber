package com.hwezah.grabber

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.composables.icons.lucide.R as L
import com.hwezah.grabber.design.BottomSheet
import com.hwezah.grabber.design.Button
import com.hwezah.grabber.design.EDGE
import com.hwezah.grabber.design.Heading
import com.hwezah.grabber.design.Ink
import com.hwezah.grabber.design.Lc
import com.hwezah.grabber.design.Note
import com.hwezah.grabber.design.SpreadOut
import com.hwezah.grabber.design.Tap
import com.hwezah.grabber.design.Type
import com.hwezah.grabber.design.frost

@Composable
fun ClipboardPage() {
    val context = LocalContext.current
    val clips by Clips.items.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var picked by remember { mutableStateOf<Clip?>(null) }
    /** Typed or pasted text: kept, and its links offered. */
    fun take(text: String) {
        if (text.isBlank()) return
        Clips.add(text)
        Capture.seen(context, text)
        if (LinkFinder.find(text).isNotEmpty()) Capture.pending = text
        query = ""
    }
    val shown = clips.filter { query.isBlank() || it.text.contains(query, ignoreCase = true) }
        .sortedWith(compareByDescending<Clip> { it.pinned }.thenByDescending { it.time })
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Heading("Clipboard") {
                Button("Paste", { take(readClipboard(context)) }, icon = L.drawable.lucide_ic_clipboard_paste, accent = Ink.red, compact = true)
            }
            Field(query, { query = it }, "Search, or paste a link and press Go", Modifier.padding(horizontal = EDGE), onGo = { take(query) })
            if (shown.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = EDGE * 3), contentAlignment = Alignment.Center) {
                    Note(if (clips.isEmpty()) "What you copy shows up here once auto-catch is on (Settings), or when you open Grabber or tap Paste." else "Nothing matches.")
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = EDGE, end = EDGE, top = 12.dp, bottom = BottomRoom),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id }) { clip ->
                        val shape = RoundedCornerShape(16.dp)
                        Tap({ picked = clip }, Modifier.fillMaxWidth(), shape) {
                            Row(Modifier.frost(shape).padding(12.dp), verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    Text(clip.text, style = Type.label, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    Text(DateUtils.getRelativeTimeSpanString(clip.time).toString(), Modifier.padding(top = 4.dp), style = Type.meta.copy(color = Ink.faint))
                                }
                                if (clip.pinned) Lc(L.drawable.lucide_ic_pin, Modifier.padding(start = 8.dp), tint = Ink.red, size = 16.dp)
                                // A link in it: download straight from the row.
                                if (LinkFinder.find(clip.text).any { it is Found.Web }) {
                                    Spacer(Modifier.width(8.dp))
                                    Button("", { Capture.pending = clip.text }, icon = L.drawable.lucide_ic_download, accent = Ink.green, compact = true)
                                }
                            }
                        }
                    }
                }
            }
        }
        picked?.let { ClipSheet(it) { picked = null } }
    }
}

@Composable
private fun ClipSheet(clip: Clip, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val links = remember(clip) { LinkFinder.find(clip.text).any { it is Found.Web } }
    BottomSheet("Clip", onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(clip.text, style = Type.body.copy(color = Ink.text), maxLines = 8, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = SpreadOut) {
                Button("Copy", {
                    context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Grabber", clip.text))
                    Capture.seen(context, clip.text)
                    onDismiss()
                }, icon = L.drawable.lucide_ic_copy, compact = true)
                Button("Share", {
                    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, clip.text), null)) }
                }, icon = L.drawable.lucide_ic_share_2, compact = true)
                Button(if (clip.pinned) "Unpin" else "Pin", { Clips.pin(clip.id); onDismiss() }, icon = L.drawable.lucide_ic_pin, compact = true)
                Button("", { Clips.delete(clip.id); onDismiss() }, icon = L.drawable.lucide_ic_trash_2, compact = true)
            }
            if (links) {
                Spacer(Modifier.height(10.dp))
                Button("Download links", { onDismiss(); Capture.pending = clip.text }, Modifier.fillMaxWidth(), icon = L.drawable.lucide_ic_download, accent = Ink.green)
            }
        }
    }
}

/** Settings: the copy watcher (with how to switch it on) and the Quick Settings tile. */
@Composable
fun CatchRows() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(Capture.watcherOn(context)) }
    var help by remember { mutableStateOf(false) }
    // Re-check when coming back from Android's settings.
    val owner = LocalLifecycleOwner.current
    var resumes by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumes++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    LaunchedEffect(resumes) { on = Capture.watcherOn(context) }
    SettingRow("Auto-catch copied links", if (on) "On: copy a link anywhere and Grabber pops up" else "Off: switch on Grabber in Accessibility") {
        Button(if (on) "On" else "Turn on", { help = true }, accent = if (on) Ink.green else Ink.red, compact = true)
    }
    SettingRow("Quick Settings tile", "Swipe down, edit tiles, add \"Grab clipboard\" for one-tap catching") {}
    SettingRow("Share → Grabber", "Share a link from any app to Grabber, or select text and pick Grabber") {}
    if (help) BottomSheet("Turn on auto-catch", { help = false }) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "Android lets an app read the clipboard only while it's on screen. Grabber's accessibility service watches for you tapping Copy " +
                    "(only the button's words, never the screen), then briefly opens its popup to read the clipboard.",
                style = Type.body,
            )
            Spacer(Modifier.height(10.dp))
            Text("1. Tap Open settings, find Grabber (under Installed apps / Downloaded apps), switch it on.", style = Type.body.copy(color = Ink.text))
            Text("2. If it's greyed out (\"Restricted setting\"): tap App info below, then ⋮ (top right) → Allow restricted settings, and try again.", Modifier.padding(top = 6.dp), style = Type.body.copy(color = Ink.text))
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = SpreadOut) {
                Button("App info", {
                    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))) }
                }, compact = true)
                Button("Open settings", {
                    runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    help = false
                }, accent = Ink.red, compact = true)
            }
        }
    }
}

package com.hwezah.grabber

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import com.hwezah.grabber.design.StretchRow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.composables.icons.lucide.R as L
import com.hwezah.grabber.design.BottomSheet
import com.hwezah.grabber.design.Button
import com.hwezah.grabber.design.CARD
import com.hwezah.grabber.design.EDGE
import com.hwezah.grabber.design.Ink
import com.hwezah.grabber.design.Lc
import com.hwezah.grabber.design.Note
import com.hwezah.grabber.design.Tap
import com.hwezah.grabber.design.Type
import com.hwezah.grabber.design.frost
import com.hwezah.grabber.design.solo

@Composable
fun BrowserPage() {
    val focus = LocalFocusManager.current
    var typing by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var tabsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Browser.url, typing, Browser.current) { if (!typing) input = Browser.url }
    BackHandler(enabled = Browser.canBack) { Browser.back() }
    val site = Browser.url.isNotBlank() && Media.looksLikeSite(Browser.url)
    // One frosted dark background for the whole tab: behind the bar and the blank page alike.
    Box(Modifier.fillMaxSize().background(Ink.bg).frost(RoundedCornerShape(0.dp), alpha = 0.03f)) {
        Column(Modifier.fillMaxSize()) {
            // The bar: tabs, the address, what's on the page to download, and the menu.
            Row(
                Modifier.statusBarsPadding().padding(start = EDGE + 2.dp, end = 2.dp, top = 6.dp, bottom = 6.dp).fillMaxWidth().height(BAR),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Tabs: a squircle with how many are open.
                Tap({ tabsOpen = true }, Modifier.size(40.dp), RoundedCornerShape(12.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(28.dp).border(2.dp, Ink.text, RoundedCornerShape(9.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Text("${Browser.tabs.size}", style = Type.meta.copy(color = Ink.text, fontWeight = FontWeight.Bold)) }
                    }
                }
                Spacer(Modifier.width(8.dp))
                val pill = RoundedCornerShape(50)
                Row(
                    Modifier.weight(1f).height(46.dp).clip(pill).background(Ink.surface).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (input.isEmpty()) Text("Search or type an address", style = Type.label.copy(color = Ink.faint), maxLines = 1)
                        BasicTextField(
                            input, { input = it },
                            Modifier.fillMaxWidth().onFocusChanged { typing = it.isFocused },
                            singleLine = true, textStyle = Type.label, cursorBrush = SolidColor(Ink.red),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { Browser.go(input); focus.clearFocus() }),
                        )
                    }
                    if (input.isNotEmpty()) Icon(L.drawable.lucide_ic_x, true) { input = "" }
                }
                // What this page has that can be downloaded, with a count.
                Tap({ picking = true }, Modifier.size(44.dp), CircleShape) {
                    Box(contentAlignment = Alignment.Center) {
                        Lc(L.drawable.lucide_ic_download, tint = if (Browser.found.isNotEmpty() || site) Ink.green else Ink.text, size = 22.dp)
                        if (Browser.found.isNotEmpty()) Box(
                            Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 2.dp).size(17.dp).clip(CircleShape).background(Ink.green),
                            contentAlignment = Alignment.Center,
                        ) { Text("${Browser.found.size.coerceAtMost(99)}", style = Type.meta.copy(fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)) }
                    }
                }
                Icon(L.drawable.lucide_ic_ellipsis_vertical, true) { menu = true }
            }
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (Browser.loading) Box(Modifier.fillMaxWidth(Browser.progress / 100f).height(2.dp).background(Ink.red))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // The current tab's page, swapped in when the tab changes.

                AndroidView(
                    factory = { android.widget.FrameLayout(it) },
                    update = { frame ->
                        val w = Browser.view(frame.context, Browser.tab)
                        if (w.parent !== frame) {
                            (w.parent as? android.view.ViewGroup)?.removeView(w)
                            frame.removeAllViews()
                            frame.addView(w, android.view.ViewGroup.LayoutParams(-1, -1))
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                // Nothing open yet: a bare, dark frosted page.
                if (Browser.url.isEmpty()) Box(Modifier.fillMaxSize().background(Ink.bg).frost(RoundedCornerShape(0.dp), alpha = 0.03f))
            }
        }
        if (menu) BottomSheet("Browser", { menu = false }, icon = L.drawable.lucide_ic_globe) {
            Column(Modifier.padding(horizontal = CARD)) {
                StretchRow {
                    Button("Back", { Browser.back(); menu = false }, icon = L.drawable.lucide_ic_arrow_left, compact = true)
                    Button("Forward", { Browser.forward(); menu = false }, icon = L.drawable.lucide_ic_arrow_right, compact = true)
                    Button("Reload", { Browser.reload(); menu = false }, icon = L.drawable.lucide_ic_rotate_cw, compact = true)
                }
                Spacer(Modifier.height(8.dp))
                StretchRow {
                    Button("New tab", { Browser.newTab(); menu = false }, icon = L.drawable.lucide_ic_plus, compact = true)
                    Button("Close tab", { Browser.close(Browser.current); menu = false }, icon = L.drawable.lucide_ic_x, compact = true)
                }
                if (site) {
                    Spacer(Modifier.height(8.dp))
                    Button("Grab this page's video", { menu = false; Capture.offer(Browser.url, Browser.title) }, Modifier.solo(), icon = L.drawable.lucide_ic_download, accent = Ink.green)
                }
            }
        }
        if (tabsOpen) BottomSheet("Tabs", { tabsOpen = false }, icon = L.drawable.lucide_ic_copy) {
            Column(Modifier.padding(horizontal = CARD)) {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    Browser.tabs.forEachIndexed { i, t ->
                        val on = i == Browser.current
                        Tap({ Browser.select(i); tabsOpen = false }, Modifier.fillMaxWidth(), RoundedCornerShape(12.dp)) {
                            Row(
                                Modifier.background(if (on) Ink.text.copy(alpha = 0.07f) else Color.Transparent).padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Lc(L.drawable.lucide_ic_globe, tint = if (on) Ink.red else Ink.muted, size = 18.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(t.title.ifBlank { if (t.url.isBlank()) "New tab" else t.url }, style = Type.label.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (t.url.isNotBlank()) Text(t.url, style = Type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(L.drawable.lucide_ic_x, true) { Browser.close(i) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button("New tab", { Browser.newTab(); tabsOpen = false }, Modifier.solo(), icon = L.drawable.lucide_ic_plus)
            }
        }
        if (picking) BottomSheet("On this page", { picking = false }, icon = L.drawable.lucide_ic_radar, iconTint = Ink.green) {
            Column(Modifier.padding(horizontal = CARD)) {
                if (site) {
                    Button("Grab this page's video", {
                        picking = false
                        Capture.offer(Browser.url, Browser.title)
                    }, Modifier.solo(), icon = L.drawable.lucide_ic_download, accent = Ink.green)
                    Spacer(Modifier.height(10.dp))
                }
                if (Browser.found.isEmpty()) Note("Nothing loaded yet. Start the video playing and it will show up here.")
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    Browser.found.forEach { s ->
                        Tap({
                            picking = false
                            Capture.offer(s.url, s.title.ifBlank { null }, Browser.headersFor(s))
                        }, Modifier.fillMaxWidth(), RoundedCornerShape(10.dp)) {
                            Row(Modifier.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(36.dp).clip(CircleShape).background(Ink.text.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
                                    Lc(
                                        when {
                                            "HLS" in s.kind || "Stream" in s.kind || s.kind == "Video" -> L.drawable.lucide_ic_film
                                            s.kind == "Audio" -> L.drawable.lucide_ic_music
                                            s.kind == "Torrent" -> L.drawable.lucide_ic_magnet
                                            else -> L.drawable.lucide_ic_file
                                        },
                                        tint = Ink.green, size = 18.dp,
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(s.kind, style = Type.label.copy(fontWeight = FontWeight.SemiBold))
                                    Text(s.url.substringBefore('?').substringAfterLast('/').ifBlank { s.url }, style = Type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                if (Browser.blocked > 0) Text("${Browser.blocked} ads and trackers blocked on this page", Modifier.padding(top = 8.dp), style = Type.meta.copy(color = Ink.faint))
            }
        }
    }
}

private val BAR = 54.dp

@Composable
private fun Icon(icon: Int, enabled: Boolean, onClick: () -> Unit) {
    Tap({ if (enabled) onClick() }, Modifier.size(44.dp), CircleShape) {
        Box(contentAlignment = Alignment.Center) { Lc(icon, tint = if (enabled) Ink.text else Ink.faint, size = 22.dp) }
    }
}

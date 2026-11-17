package com.hwezah.grabber

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var typing by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(Browser.url, typing) { if (!typing) input = Browser.url }
    BackHandler(enabled = Browser.canBack) { Browser.back() }
    Box(Modifier.fillMaxSize()) {
        // The page first, the bar drawn after it: nothing the web view paints can cover the bar.
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.statusBarsPadding().height(BAR + 16.dp))
            if (Browser.loading) Box(Modifier.fillMaxWidth().height(2.dp)) {
                Box(Modifier.fillMaxWidth(Browser.progress / 100f).height(2.dp).background(Ink.red))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory = { Browser.view(it) }, modifier = Modifier.fillMaxSize())
                // Nothing open yet: a bare, dark frosted page.
                if (Browser.url.isEmpty()) Box(Modifier.fillMaxSize().background(Ink.bg).frost(RoundedCornerShape(0.dp), alpha = 0.03f))
            }
        }
        // The bar: a frosted pill about 92% of the width; back and forward inside on the left, reload on the right,
        // and the address in the middle on a slightly darker field.
        Box(Modifier.fillMaxWidth().background(Ink.bg).statusBarsPadding().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            val shape = RoundedCornerShape(50)
            Row(
                Modifier.fillMaxWidth(0.92f).height(BAR).clip(shape).background(Ink.surface2).frost(shape, alpha = 0.04f).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(L.drawable.lucide_ic_arrow_left, Browser.canBack) { Browser.back() }
                Icon(L.drawable.lucide_ic_arrow_right, Browser.canForward) { Browser.forward() }
                Box(
                    Modifier.weight(1f).height(BAR - 12.dp).clip(shape).background(Ink.surface).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (input.isEmpty()) Text("Search or type an address", style = Type.label.copy(color = Ink.faint), maxLines = 1)
                    BasicTextField(
                        input, { input = it },
                        Modifier.fillMaxWidth().onFocusChanged { typing = it.isFocused },
                        singleLine = true, textStyle = Type.label, cursorBrush = SolidColor(Ink.red),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { Browser.go(input); focus.clearFocus() }),
                    )
                }
                Icon(L.drawable.lucide_ic_rotate_cw, Browser.url.isNotEmpty()) { Browser.reload() }
            }
        }
        // What the page has loaded that can be grabbed; or, on a video site, the page itself.
        val site = Browser.url.isNotBlank() && Media.looksLikeSite(Browser.url)
        if (Prefs.sniffMedia && (Browser.found.isNotEmpty() || site)) {
            Tap({ picking = true }, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = BottomRoom), RoundedCornerShape(50)) {
                Row(Modifier.clip(RoundedCornerShape(50)).background(Ink.greenButton.copy(alpha = 0.92f)).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Lc(L.drawable.lucide_ic_download, tint = Color.White, size = 20.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(if (Browser.found.isEmpty()) "Grab" else "${Browser.found.size}", style = Type.label.copy(color = Color.White, fontWeight = FontWeight.Bold))
                }
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
                                            s.kind.startsWith("Stream") || s.kind == "Video" -> L.drawable.lucide_ic_film
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

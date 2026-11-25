package com.hwezah.grabber

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.hwezah.grabber.design.Sheets
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
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
    Browser("Browser", L.drawable.lucide_ic_globe),
    Files("Files", L.drawable.lucide_ic_folder),
    Clipboard("Clipboard", L.drawable.lucide_ic_clipboard_list),
    Settings("Settings", L.drawable.lucide_ic_settings),
}

@Composable
fun App() {
    val scheme = if (Prefs.dark) darkColorScheme(primary = Ink.red, background = Ink.bg, surface = Ink.surface)
    else lightColorScheme(primary = Ink.red, background = Ink.bg, surface = Ink.surface)
    MaterialTheme(colorScheme = scheme) {
        var tab by rememberSaveable { mutableStateOf(Tab.Downloads) }
        // A link sent to the browser (the globe in a download sheet): show it.
        if (Browser.openNow) {
            tab = Tab.Browser
            Browser.openNow = false
        }
        // The floating bar slips away while you scroll down and comes back when you scroll up.
        var barShown by remember { mutableStateOf(true) }
        // The browser is full screen with no tab bar; Back leaves it once the page has nowhere to go back to.
        val navShown = if (tab == Tab.Browser) false else barShown
        androidx.activity.compose.BackHandler(tab == Tab.Browser && !Browser.canBack && !Browser.fullScreen) { tab = Tab.Downloads }
        // An incognito tab on screen: no screenshots, and a blank card in Recents.
        val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
        val secret = tab == Tab.Browser && Browser.tab.incognito
        LaunchedEffect(secret) {
            activity?.window?.let { w ->
                if (secret) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                else w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        // Hidden only while the list moves down; back as soon as scrolling stops.
        var scrollTick by remember { mutableStateOf(0) }
        LaunchedEffect(scrollTick) {
            if (scrollTick > 0) {
                kotlinx.coroutines.delay(700)
                barShown = true
            }
        }
        val scrollWatch = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y < -6f) barShown = false else if (available.y > 6f) barShown = true
                    if (available.y != 0f) scrollTick++
                    return Offset.Zero
                }

                override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                    barShown = true
                    return androidx.compose.ui.unit.Velocity.Zero
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Ink.bg).frost(androidx.compose.ui.graphics.RectangleShape, alpha = 0f, grain = 0.6f).nestedScroll(scrollWatch)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                        when (t) {
                            Tab.Downloads -> DownloadsPage()
                            Tab.Files -> FilesPage()
                            Tab.Clipboard -> ClipboardPage()
                            Tab.Settings -> SettingsPage()
                            Tab.Browser -> BrowserPage()
                        }
                    }
                }
                // The bottom bar: part of the layout on the other tabs; over the page in the browser, so it can come
                // and go without the page jumping.
                if (!Prefs.pillBar && tab != Tab.Browser) TabBar(tab) { tab = it; barShown = true }
            }
            if (!Prefs.pillBar && tab == Tab.Browser) AnimatedVisibility(
                navShown && Sheets.open == 0,
                Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) { TabBar(tab) { tab = it; barShown = true } }
            if (Prefs.pillBar) {
                AnimatedVisibility(
                    navShown && Sheets.open == 0,
                    Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically { it * 2 } + fadeIn(),
                    exit = slideOutVertically { it * 2 } + fadeOut(),
                ) { PillBar(tab, { tab = it; barShown = true }) }
            }
            UpdateBar(Modifier.align(Alignment.TopCenter))
            Capture.pending?.let { CaptureSheet(it, title = Capture.pendingTitle, headers = Capture.pendingHeaders) { Capture.pending = null; Capture.pendingTitle = null; Capture.pendingHeaders = emptyMap() } }
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
                        Lc(t.icon, tint = if (on) (if (Prefs.accentTabIcon) Ink.red else Ink.text) else Ink.faint, size = 22.dp)
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
        modifier.fillMaxWidth(0.9f).navigationBarsPadding().padding(bottom = 12.dp).frost(RoundedCornerShape(20.dp), Ink.bg, alpha = 0.02f)
            // A very faint edge, so the bar stands off the page it now matches.
            .border(1.dp, Ink.text.copy(alpha = 0.07f), RoundedCornerShape(20.dp)).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { t ->
            val on = t == tab
            Tap({ onTab(t) }, Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                Box(
                    Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) Ink.text.copy(alpha = 0.08f) else Color.Transparent)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Lc(t.icon, tint = if (on && Prefs.accentTabIcon) Ink.red else Ink.text, size = 24.dp) }
            }
        }
    }
}

/** Room under a page's last row for the floating pill bar. */
val BottomRoom get() = if (Prefs.pillBar) 96.dp else 16.dp

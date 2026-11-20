package com.hwezah.grabber

import com.hwezah.grabber.design.grain
import androidx.compose.foundation.background
import kotlinx.coroutines.launch
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
import com.hwezah.grabber.design.StretchRow
import com.hwezah.grabber.design.Lc
import com.hwezah.grabber.design.Note
import com.hwezah.grabber.design.Overline
import com.hwezah.grabber.design.SpreadOut
import com.hwezah.grabber.design.Toggle
import com.hwezah.grabber.design.Type
import com.hwezah.grabber.design.frost

@Composable
fun SettingsPage() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var sheet by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Heading("Settings")
        Column(Modifier.padding(horizontal = EDGE)) {
            Overline("Downloads")
            Group {
                FolderRow()
                ChipRow("Connections per file", listOf(8, 16, 32, 48, 64).map { it to "$it" }, Prefs.connections, Prefs::useConnections)
                ChipRow("Downloads at the same time", (1..5).map { it to "$it" }, Prefs.parallel, Prefs::useParallel)
                ChipRow("Speed limit", listOf(0L to "Off", 1L to "1 MB/s", 5L to "5 MB/s", 10L to "10 MB/s").map { (mb, l) -> mb * 1024 * 1024 to l }, Prefs.speedLimit, Prefs::useSpeedLimit)
                BatteryRow()
                SettingRow("Wi-Fi only", "On mobile data, downloads wait for Wi-Fi") { Toggle(Prefs.wifiOnly, Prefs::useWifiOnly) }
                SettingRow("Retry failed downloads", "Keeps trying, even for days, when the connection drops; carries on when the network is back") { Toggle(Prefs.autoRetry, Prefs::useAutoRetry) }
                SettingRow("Notify when done", "A notification for each finished download, tap to open") { Toggle(Prefs.notifyDone, Prefs::useNotifyDone) }
                SettingRow("Browser identity", if (Prefs.userAgent.isBlank()) "Chrome on Android (default). Change it if a site blocks downloads." else Prefs.userAgent) {
                    Button("Edit", { sheet = "ua" }, compact = true)
                }
            }
            Spacer(Modifier.height(20.dp))
            Overline("Catching links")
            Group {
                CatchRows()
                SettingRow("Catch browser downloads", "Chrome, Samsung Internet, Edge and others: a download they start pauses and Grabber offers to take it. Needs auto-catch on") { Toggle(Prefs.catchBrowser, Prefs::useCatchBrowser) }
                SettingRow("Firefox", "Settings › Downloads › External download manager sends every Firefox download straight to Grabber") {}
                SettingRow("Save copied plain text", "Keep copied text without links in Clipboard too") { Toggle(Prefs.savePlainText, Prefs::useSavePlainText) }
                SettingRow("Offer copied links on open", "Opening Grabber with a new link copied shows the download sheet") { Toggle(Prefs.offerOnOpen, Prefs::useOfferOnOpen) }
                ChipRow("Clipboard history keeps", listOf(100, 500, 1000).map { it to "$it" }, Prefs.historyLimit, Prefs::useHistoryLimit)
                SettingRow("Clear clipboard history", "Pinned clips stay") { Button("Clear", { sheet = "clear" }, compact = true) }
            }
            Spacer(Modifier.height(20.dp))
            Overline("Browser")
            Group {
                ChipRow("Search engine", Browser.ENGINES.map { it.first to it.first }, Prefs.searchEngine, Prefs::useSearchEngine)
                SettingRow("Block ads", "Drops requests to ad and tracker networks") { Toggle(Prefs.blockAds, Prefs::useBlockAds) }
                SettingRow("Dark pages", "Shows light websites dark") { Toggle(Prefs.darkPages, Prefs::useDarkPages) }
                SettingRow("Block pop-ups", "Pages can't open new windows") { Toggle(Prefs.blockPopups, Prefs::useBlockPopups) }
                SettingRow("Block redirects", "Stops pages jumping to ad and betting sites; a bar offers Open anyway") { Toggle(Prefs.blockRedirects, Prefs::useBlockRedirects) }
                SettingRow("Spot videos and files", "Watches what pages load and offers it to download") { Toggle(Prefs.sniffMedia, Prefs::useSniffMedia) }
                SettingRow("Clear browsing data", "Cookies, history and cache. Site sign-ins in the browser go too") {
                    Button("Clear", { Browser.clearData() }, compact = true)
                }
            }
            Spacer(Modifier.height(20.dp))
            Overline("Video sites")
            Group {
                YtDlpRow()
                SignInRows()
                ChipRow("Default quality", listOf("v:best" to "Best", "v:1080" to "1080p", "v:720" to "720p", "v:480" to "480p"), Prefs.videoQuality, Prefs::useVideoQuality)
                ChipRow("Default audio format", listOf("mp3" to "MP3", "m4a" to "M4A", "opus" to "Opus"), Prefs.audioFormat, Prefs::useAudioFormat, Ink.orange)
            }
            Spacer(Modifier.height(20.dp))
            Overline("Torrents")
            Group {
                SettingRow("Download in order", "Default for new torrents: start to end, so videos can play early") { Toggle(Prefs.sequentialDefault, Prefs::useSequentialDefault, accent = Ink.orange) }
                SettingRow("Seeding", "Off: Grabber stops sharing as soon as a torrent finishes")
            }
            Spacer(Modifier.height(20.dp))
            Overline("Storage")
            Group { TempRow() }
            Spacer(Modifier.height(20.dp))
            Overline("Look")
            Group {
                SettingRow("Dark theme", "Near-black page; off for warm paper") { Toggle(Prefs.dark, Prefs::useDark) }
                AccentRow()
                SettingRow("Floating tab bar", "A pill that hides as you scroll; off for a bar along the bottom") { Toggle(Prefs.pillBar, Prefs::usePillBar) }
                SettingRow("Accent tab icon", "The tab you're on in your accent colour; off for white") { Toggle(Prefs.accentTabIcon, Prefs::useAccentTabIcon) }
            }
            Spacer(Modifier.height(20.dp))
            Overline("About")
            Group {
                SettingRow("Grabber ${BuildConfig.VERSION_NAME}", "Updates come from GitHub; a bar appears up top when one is ready.") {
                    Button("Check", { Updater.dismissed = false; Updater.check(force = true) }, compact = true)
                }
                SettingRow("Open-source licences", "yt-dlp, FFmpeg, libtorrent, OkHttp, Google Sans Flex…") { Button("View", { sheet = "licences" }, compact = true) }
            }
            Spacer(Modifier.height(BottomRoom))
        }
    }
    when (sheet) {
        "ua" -> {
            var ua by remember { mutableStateOf(Prefs.userAgent) }
            com.hwezah.grabber.design.BottomSheet("Browser identity", { sheet = null }, icon = com.composables.icons.lucide.R.drawable.lucide_ic_globe) {
                Column(Modifier.padding(horizontal = CARD)) {
                    Text("The User-Agent sent with every download. Leave empty for Chrome on Android.", style = Type.body)
                    Spacer(Modifier.height(10.dp))
                    Field(ua, { ua = it }, "Mozilla/5.0 …")
                    Spacer(Modifier.height(12.dp))
                    StretchRow {
                        Button("Reset", { Prefs.useUserAgent(""); sheet = null }, compact = true)
                        Button("Save", { Prefs.useUserAgent(ua.trim()); sheet = null }, accent = Ink.green, compact = true)
                    }
                }
            }
        }
        "clear" -> com.hwezah.grabber.design.BottomSheet("Clear clipboard history?", { sheet = null }, icon = com.composables.icons.lucide.R.drawable.lucide_ic_trash_2) {
            Column(Modifier.padding(horizontal = CARD)) {
                Text("Every clip goes except pinned ones. This can't be undone.", style = Type.body)
                Spacer(Modifier.height(12.dp))
                StretchRow {
                    Button("Cancel", { sheet = null }, compact = true)
                    Button("Clear", { Clips.clearUnpinned(); sheet = null }, accent = Ink.red, compact = true)
                }
            }
        }
        "licences" -> com.hwezah.grabber.design.BottomSheet("Open-source licences", { sheet = null }, icon = com.composables.icons.lucide.R.drawable.lucide_ic_file_text) {
            Column(Modifier.padding(horizontal = CARD).heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                listOf(
                    "yt-dlp" to "Unlicense (public domain)",
                    "youtubedl-android" to "GPL-3.0",
                    "FFmpeg" to "LGPL/GPL",
                    "aria2" to "GPL-2.0",
                    "Python (bundled for yt-dlp)" to "PSF License",
                    "libtorrent / libtorrent4j" to "BSD-3-Clause / MIT",
                    "OkHttp, Okio" to "Apache-2.0",
                    "Kotlin, coroutines" to "Apache-2.0",
                    "Jetpack Compose, AndroidX" to "Apache-2.0",
                    "Lucide icons" to "ISC",
                    "Google Sans Flex" to "SIL Open Font License 1.1",
                ).forEach { (n, l) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                        Text(n, Modifier.weight(1f), style = Type.label)
                        Text(l, style = Type.meta)
                    }
                }
                Text("Because it includes GPL parts, Grabber's own source is open: github.com/Hwezah/Grabber", Modifier.padding(top = 8.dp), style = Type.meta)
            }
        }
    }
    }
}

/** A setting with a few choices as pills. */
@Composable
fun <T> ChipRow(title: String, options: List<Pair<T, String>>, value: T, onPick: (T) -> Unit, accent: androidx.compose.ui.graphics.Color = Ink.red) {
    Text(title, Modifier.padding(top = 10.dp), style = Type.label.copy(fontWeight = FontWeight.SemiBold))
    // Short choices (numbers) spread across the full width; many long ones (search engines) scroll sideways.
    val scroll = options.size > 4 && options.sumOf { it.second.length } > 24
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).then(if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
        horizontalArrangement = if (scroll) Arrangement.spacedBy(6.dp) else SpreadOut, verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, (v, l) ->
            if (i > 0) com.hwezah.grabber.design.Pipe(com.hwezah.grabber.design.pipeColor(i - 1))
            Chip(l, value == v, { onPick(v) }, accent = accent)
        }
    }
}

/** Partly downloaded streams, videos and updates left in the cache. */
@Composable
fun TempRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var size by remember { mutableStateOf(-1L) }
    var tick by remember { mutableStateOf(0) }
    val dirs = remember { listOf(java.io.File(context.filesDir, "work"), java.io.File(context.cacheDir, "convert"), java.io.File(context.cacheDir, "update.apk")) }
    androidx.compose.runtime.LaunchedEffect(tick) {
        size = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dirs.sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } } }
    }
    val busy = Downloads.items.value.any { it.state == State.Running || it.state == State.Queued }
    SettingRow("Temporary files", if (size < 0) "Measuring…" else "${Fmt.bytes(size)} of unfinished streams, videos and updates" + if (busy) " · pause downloads first" else "") {
        Button("Clear", {
            if (!busy) {
                dirs.forEach { it.deleteRecursively() }
                tick++
            }
        }, compact = true)
    }
}

@Composable
fun Group(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Ink.section).grain(0.7f).padding(horizontal = CARD, vertical = 6.dp)) {
        androidx.compose.runtime.CompositionLocalProvider(com.hwezah.grabber.design.LocalQuiet provides true) { content() }
    }
}

@Composable
fun SettingRow(title: String, hint: String, trailing: @Composable () -> Unit = {}) {
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

/** yt-dlp's version, and a button to update it when a site stops working. */
@Composable
fun YtDlpRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var version by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { version = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Media.version(context) } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    SettingRow("yt-dlp " + (version ?: "…"), note ?: "Finds the video on YouTube and ~1,800 sites. Update it if a site stops working.") {
        Button(if (busy) "Updating…" else "Update", {
            if (!busy) {
                busy = true
                scope.launch {
                    note = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Media.update(context) } }
                        .fold({ version = it; "Up to date" }, { "Update failed: ${it.message}" })
                    busy = false
                }
            }
        }, compact = true)
    }
}

/** Sign-ins for sites that hide some videos from signed-out visitors. */
@Composable
fun SignInRows() {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Re-read when coming back from the sign-in page.
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var tick by remember { mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    Text("Sign-ins", Modifier.padding(top = 10.dp), style = Type.label.copy(fontWeight = FontWeight.SemiBold))
    Text("For private, age-restricted or members-only videos. Cookies stay on this phone, used only for that site.", style = Type.meta)
    Site.entries.forEach { s ->
        val on = remember(tick) { SignIns.signedIn(context, s) }
        SettingRow(s.label, if (on) "Signed in" else "Not signed in") {
            if (on) Button("Sign out", { SignIns.signOut(context, s); tick++ }, compact = true)
            else Button("Sign in", { SignIns.open(context, s) }, compact = true)
        }
    }
}

/** The main colour (buttons, selected chips, toggles): a row of circles to pick from. */
@Composable
fun AccentRow() {
    Text("Main colour", Modifier.padding(top = 10.dp), style = Type.label.copy(fontWeight = FontWeight.SemiBold))
    Text(Prefs.accent, style = Type.meta)
    // Two rows of five circles.
    Ink.ACCENTS.chunked(5).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = SpreadOut) {
            row.forEach { (name, color) ->
                val on = Prefs.accent == name
                com.hwezah.grabber.design.Tap({ Prefs.useAccent(name) }, Modifier.size(38.dp), androidx.compose.foundation.shape.CircleShape) {
                    Box(Modifier.background(color), contentAlignment = Alignment.Center) {
                        if (on) com.hwezah.grabber.design.Lc(com.composables.icons.lucide.R.drawable.lucide_ic_check, tint = Ink.on(color), size = 18.dp)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

/** Android pauses background apps' network to save battery; downloads need to be let off that. */
@Composable
fun BatteryRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pm = remember { context.getSystemService(android.os.PowerManager::class.java) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var tick by remember { mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val free = remember(tick) { pm?.isIgnoringBatteryOptimizations(context.packageName) == true }
    SettingRow(
        "Keep downloading in the background",
        if (free) "On: Android won't pause Grabber's downloads to save battery" else "Off: Android may stall downloads when the screen is off. Turn on for long downloads.",
    ) {
        if (free) Button("On", {}, accent = Ink.green, compact = true)
        else Button("Turn on", {
            runCatching {
                @android.annotation.SuppressLint("BatteryLife")
                val i = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + context.packageName))
                context.startActivity(i)
            }.onFailure { runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
        }, accent = Ink.red, compact = true)
    }
}

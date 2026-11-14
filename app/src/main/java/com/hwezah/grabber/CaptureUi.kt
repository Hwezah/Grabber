package com.hwezah.grabber

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import com.hwezah.grabber.engine.Hls
import com.hwezah.grabber.engine.Names
import com.hwezah.grabber.engine.Http
import com.hwezah.grabber.engine.Variant
import androidx.compose.foundation.horizontalScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.composables.icons.lucide.R as L
import com.hwezah.grabber.design.BottomSheet
import com.hwezah.grabber.design.Button
import com.hwezah.grabber.design.CARD
import com.hwezah.grabber.design.Ink
import com.hwezah.grabber.design.StretchRow
import com.hwezah.grabber.design.solo
import com.hwezah.grabber.design.frost
import com.hwezah.grabber.design.Lc
import com.hwezah.grabber.design.Note
import com.hwezah.grabber.design.Overline
import com.hwezah.grabber.design.SpreadOut
import com.hwezah.grabber.design.Tap
import com.hwezah.grabber.design.Type

/** The links in [found], each with a tick; [off] holds the ones unticked. */
@Composable
fun LinkPicker(found: List<Found>, off: SnapshotStateList<String>) {
    Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
        found.forEach { f ->
            val magnet = f is Found.Magnet
            val on = f.text !in off
            Tap({ if (on) off += f.text else off -= f.text }, Modifier.fillMaxWidth(), RoundedCornerShape(8.dp)) {
                Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(22.dp).clip(CircleShape).background(if (on) (if (magnet) Ink.orange else Ink.green) else Ink.text.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            on -> Lc(L.drawable.lucide_ic_check, tint = Color.White, size = 14.dp)
                            magnet -> Lc(L.drawable.lucide_ic_magnet, tint = Ink.orange, size = 14.dp)
                            else -> Lc(L.drawable.lucide_ic_link, tint = Ink.faint, size = 14.dp)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(if (magnet) magnetName(f.text) else f.text, Modifier.weight(1f), style = Type.meta.copy(color = if (on) Ink.text else Ink.faint), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The torrent's name from a magnet's dn=, else its hash. */
fun magnetName(magnet: String): String =
    Regex("""[?&]dn=([^&]+)""").find(magnet)?.groupValues?.get(1)?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        ?: magnet.substringAfter("btih:").substringBefore('&').take(40)

/** Queues [urls] (magnets as torrents), asking for notification permission first time on Android 13+. */
fun startDownloads(context: Context, urls: List<String>, askNotify: () -> Unit) {
    if (urls.isEmpty()) return
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) askNotify()
    val (mags, web) = urls.partition { it.startsWith("magnet:", true) }
    if (web.isNotEmpty()) Downloads.add(web)
    mags.forEach { Downloads.add(listOf(it), name = magnetName(it), kind = Kind.Torrent) }
}

/**
 * What Grabber can do with some copied or shared [text]: download its links, copy it, keep it.
 * The text is already saved in the clipboard history when this shows.
 */
@Composable
fun CaptureSheet(text: String, title: String? = null, headers: Map<String, String> = emptyMap(), onDismiss: () -> Unit) {
    val context = LocalContext.current
    val found = remember(text) { LinkFinder.find(text) }
    val off = remember { mutableStateListOf<String>() }
    val web = found.map { it.text }.filter { it !in off }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val heading = when {
        found.isEmpty() -> "Saved to clipboard"
        found.size == 1 -> if (found[0] is Found.Magnet) "Torrent" else "Link caught"
        else -> "${found.size} links caught"
    }
    val single = found.size == 1
    // A name for a lone link: what the sending app said, else words copied around it.
    val nameHint = title ?: if (single) Capture.nameAround(text) else null
    BottomSheet(if (single && found[0] is Found.Web) "Download file" else heading, onDismiss) {
        Column(Modifier.padding(horizontal = CARD)) {
            if (found.isEmpty()) {
                Text(text, style = Type.body.copy(color = Ink.text), maxLines = 6, overflow = TextOverflow.Ellipsis)
            } else if (single) {
                if (Prefs.folder == null) {
                    Text("First, choose where downloads go.", style = Type.body.copy(color = Ink.text))
                    FolderRow()
                }
                if (found[0] is Found.Magnet) OneMagnet(found[0].text, onDismiss) else OneLink(found[0].text, onDismiss, nameHint, headers)
            } else {
                if (Prefs.folder == null) {
                    Text("First, choose where downloads go.", style = Type.body.copy(color = Ink.text))
                    FolderRow()
                }
                Overline(if (web.isEmpty()) "Nothing to download yet" else "${web.size} to download")
                LinkPicker(found, off)
            }
            if (!single) {
                Spacer(Modifier.height(14.dp))
                StretchRow {
                    Button("Copy", {
                        context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Grabber", text))
                        Capture.seen(context, text)
                        onDismiss()
                    }, icon = L.drawable.lucide_ic_copy, compact = true)
                    Button("Close", onDismiss, icon = L.drawable.lucide_ic_x, compact = true)
                }
                if (found.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        if (web.size > 1) "Download ${web.size}" else "Download",
                        {
                            if (Prefs.folder != null && web.isNotEmpty()) {
                                startDownloads(context, web) { notify.launch(Manifest.permission.POST_NOTIFICATIONS) }
                                onDismiss()
                            }
                        },
                        Modifier.solo(), icon = L.drawable.lucide_ic_download,
                        accent = if (Prefs.folder != null && web.isNotEmpty()) Ink.green else null,
                    )
                }
            }
        }
    }
}

/** One link, as 1DM shows it: checked with the server first, name editable, referer optional. */
@Composable
private fun OneLink(url: String, onDismiss: () -> Unit, title: String? = null, given: Map<String, String> = emptyMap()) {
    val context = LocalContext.current
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var info by remember { mutableStateOf<com.hwezah.grabber.engine.Info?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var referer by remember { mutableStateOf(given["Referer"] ?: given["referer"] ?: "") }
    fun headersNow() = given.filterKeys { !it.equals("Referer", true) } + (if (referer.isNotBlank()) mapOf("Referer" to referer.trim()) else emptyMap())
    var tries by remember { mutableStateOf(0) }
    /** An HLS stream: its qualities (empty when the link is already a single quality). */
    var stream by remember { mutableStateOf<List<Variant>?>(null) }
    /** A page yt-dlp understands (YouTube and co.): its qualities, and the choice. */
    var media by remember { mutableStateOf<MediaInfo?>(null) }
    var pick by remember { mutableStateOf("v:1080") }
    var variant by remember { mutableStateOf<Variant?>(null) }
    LaunchedEffect(url, tries) {
        checking = true
        error = null
        val headers = headersNow()
        runCatching {
            withContext(Dispatchers.IO) {
                // Pages from video sites (or anything that answers with a web page) go to yt-dlp.
                val i = if (Media.looksLikeSite(url)) null else runCatching { Http.probe(url, headers) }.getOrNull()
                if (i == null || i.mime == "text/html") {
                    val m = Media.info(context, url)
                    media = m
                    val cap = Prefs.videoQuality.removePrefix("v:").toIntOrNull() ?: Int.MAX_VALUE
                    pick = m.heights.firstOrNull { it <= cap }?.let { "v:$it" } ?: "v:best"
                    if (name.isEmpty()) name = Names.clean(title ?: m.title)
                    return@withContext
                }
                info = i
                if (Hls.isPlaylist(i.finalUrl, i.mime)) {
                    val v = Hls.variants(i.finalUrl, Hls.text(i.finalUrl, headers))
                    stream = v
                    variant = v.firstOrNull()
                }
            }
        }
            .onSuccess {
                info?.let {
                    if (name.isEmpty()) {
                        val ext = it.name.substringAfterLast('.', "")
                        name = when {
                            stream != null -> title?.let { t -> Names.clean(t) } ?: it.name.substringBeforeLast('.')
                            title != null -> Names.clean(title) + if (ext.isNotEmpty() && ext.length <= 5) ".$ext" else ""
                            else -> it.name
                        }
                    }
                }
            }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
        checking = false
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(url, Modifier.weight(1f), style = Type.meta.copy(color = Ink.text), maxLines = 3, overflow = TextOverflow.Ellipsis)
        Button("", {
            context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("link", url))
            Capture.seen(context, url)
        }, icon = L.drawable.lucide_ic_copy, compact = true)
        Spacer(Modifier.width(6.dp))
        Button("", {
            runCatching { context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, url), null)) }
        }, icon = L.drawable.lucide_ic_share_2, compact = true)
    }
    Spacer(Modifier.height(10.dp))
    val status = when {
        checking -> "Checking the link…"
        media != null -> listOfNotNull(media!!.extractor, if (media!!.duration > 0) "${media!!.duration / 60}:${"%02d".format(media!!.duration % 60)}" else null).joinToString(" · ")
        error != null -> "Error: $error"
        stream != null -> "Video stream (HLS) · saved as .ts" + if (stream!!.isNotEmpty()) " · pick a quality" else ""
        else -> info!!.let { i ->
            listOfNotNull(
                if (i.size > 0) Fmt.bytes(i.size) else "Size unknown",
                i.mime,
                if (i.resumable) "resumable, ${Prefs.connections} connections" else "one connection, can't resume",
            ).joinToString(" · ")
        }
    }
    Text(status, style = Type.meta.copy(color = if (error != null) Ink.orange else Ink.muted))
    media?.let { m ->
        val video = m.heights.filter { it in setOf(2160, 1440, 1080, 720, 480, 360, 240) }.ifEmpty { m.heights.take(4) }
        Text("Video", Modifier.padding(top = 10.dp), style = Type.meta)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            (video.map { "v:$it" }.ifEmpty { listOf("v:best") }).forEach { v ->
                com.hwezah.grabber.design.Chip(if (v == "v:best") "Best" else v.removePrefix("v:") + "p", pick == v, { pick = v }, accent = Ink.green)
            }
        }
        Text("Audio only", Modifier.padding(top = 10.dp), style = Type.meta)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            listOf("a:mp3", "a:m4a", "a:opus").sortedByDescending { it == "a:" + Prefs.audioFormat }.forEach { a ->
                com.hwezah.grabber.design.Chip(a.removePrefix("a:").uppercase(), pick == a, { pick = a }, accent = Ink.orange)
            }
        }
    }
    stream?.takeIf { it.isNotEmpty() }?.let { vs ->
        Row(Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            vs.forEach { v -> com.hwezah.grabber.design.Chip(v.label, v == variant, { variant = v }, accent = Ink.green) }
        }
    }
    Spacer(Modifier.height(10.dp))
    Field(name, { name = it }, "File name")
    Spacer(Modifier.height(8.dp))
    Field(referer, { referer = it }, "Referer page (leave empty if not sure)")
    Spacer(Modifier.height(12.dp))
    StretchRow {
        Button("Cancel", onDismiss, compact = true)
        Button(if (checking) "Checking…" else "Check again", { tries++ }, icon = L.drawable.lucide_ic_rotate_cw, compact = true)
    }
    Spacer(Modifier.height(10.dp))
    Button(
        if (media != null && pick.startsWith("a:")) "Download ${pick.removePrefix("a:").uppercase()}" else "Download", {
            if (Prefs.folder != null && !checking) {
                val headers = headersNow()
                if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notify.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (media != null) Downloads.add(listOf(url), headers, name.ifBlank { media!!.title }, kind = Kind.Media, format = pick)
                else if (stream != null) Downloads.add(listOf(variant?.url ?: info?.finalUrl ?: url), headers, name.ifBlank { "video" }, kind = Kind.Hls)
                else Downloads.add(listOf(info?.finalUrl ?: url), headers, name.takeIf { it != info?.name })
                onDismiss()
            }
        },
        Modifier.solo(), icon = L.drawable.lucide_ic_download, accent = if (Prefs.folder != null) Ink.green else null,
    )
}

/** A text box on frosted glass. */
@Composable
fun Field(value: String, onValue: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, onGo: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.fillMaxWidth().frost(shape).padding(horizontal = CARD, vertical = 12.dp)) {
        if (value.isEmpty()) Text(placeholder, style = Type.label.copy(color = Ink.faint), maxLines = 1)
        BasicTextField(
            value, onValue, Modifier.fillMaxWidth(), singleLine = true, textStyle = Type.label, cursorBrush = SolidColor(Ink.red),
            keyboardOptions = KeyboardOptions(imeAction = if (onGo != null) ImeAction.Go else ImeAction.Done),
            keyboardActions = KeyboardActions(onGo = { onGo?.invoke() }),
        )
    }
}

/** A magnet: asks peers for the file list, then lets you pick files and order. */
@Composable
private fun OneMagnet(magnet: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var meta by remember { mutableStateOf<TorrentMeta?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tries by remember { mutableStateOf(0) }
    val off = remember { mutableStateListOf<Int>() }
    var sequential by remember { mutableStateOf(Prefs.sequentialDefault) }
    LaunchedEffect(magnet, tries) {
        error = null
        runCatching { withContext(Dispatchers.IO) { Torrents.metadata(context, magnet) } }
            .onSuccess { meta = it }
            .onFailure { error = it.message ?: "Couldn't get the file list" }
    }
    Text(magnetName(magnet), style = Type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 3, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(8.dp))
    val m = meta
    when {
        m == null && error == null -> Text("Asking peers for the file list…", style = Type.meta)
        m == null -> {
            Text(error!!, style = Type.meta.copy(color = Ink.orange))
            Spacer(Modifier.height(8.dp))
            Button("Try again", { tries++ }, icon = L.drawable.lucide_ic_rotate_cw, compact = true)
        }
        else -> {
            val chosen = m.files.filter { it.index !in off }
            Overline("${chosen.size} of ${m.files.size} files · ${Fmt.bytes(chosen.sumOf { it.size })}")
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                m.files.forEach { f ->
                    val on = f.index !in off
                    Tap({ if (on) off += f.index else off -= f.index }, Modifier.fillMaxWidth(), RoundedCornerShape(8.dp)) {
                        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(22.dp).clip(CircleShape).background(if (on) Ink.orange else Ink.text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                                if (on) Lc(L.drawable.lucide_ic_check, tint = Color.White, size = 14.dp)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(f.path.substringAfterLast('/'), Modifier.weight(1f), style = Type.meta.copy(color = if (on) Ink.text else Ink.faint), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(Fmt.bytes(f.size), Modifier.padding(start = 8.dp), style = Type.meta)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("In order", style = Type.label)
                    Text("Downloads from the start, so a video can play early", style = Type.meta)
                }
                com.hwezah.grabber.design.Toggle(sequential, { sequential = it }, accent = Ink.orange)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    StretchRow {
        Button("Cancel", onDismiss, compact = true)
        Button("Copy", {
            context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("magnet", magnet))
            Capture.seen(context, magnet)
        }, icon = L.drawable.lucide_ic_copy, compact = true)
    }
    Spacer(Modifier.height(10.dp))
    val ready = meta != null && Prefs.folder != null && meta!!.files.any { it.index !in off }
    Button(
        "Download torrent", {
            val mm = meta ?: return@Button
            if (Prefs.folder == null) return@Button
            if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notify.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            val files = if (off.isEmpty()) "" else mm.files.filter { it.index !in off }.joinToString(",") { it.index.toString() }
            Downloads.add(listOf(magnet), name = mm.name, kind = Kind.Torrent, files = files, sequential = sequential)
            onDismiss()
        },
        Modifier.solo(), icon = L.drawable.lucide_ic_magnet, accent = if (ready) Ink.orange else null,
    )
}

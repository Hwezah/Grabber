package com.hwezah.grabber

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.core.content.ContextCompat
import com.composables.icons.lucide.R as L
import com.hwezah.grabber.design.BottomSheet
import com.hwezah.grabber.design.Button
import com.hwezah.grabber.design.Chip
import com.hwezah.grabber.design.EDGE
import com.hwezah.grabber.design.Heading
import com.hwezah.grabber.design.CARD
import com.hwezah.grabber.design.Ink
import com.hwezah.grabber.design.Pipe
import com.hwezah.grabber.design.SwipeRow
import com.hwezah.grabber.design.CARD
import com.hwezah.grabber.design.StretchRow
import com.hwezah.grabber.design.solo
import com.hwezah.grabber.design.Lc
import com.hwezah.grabber.design.Note
import com.hwezah.grabber.design.Overline
import com.hwezah.grabber.design.SpreadOut
import com.hwezah.grabber.design.Tap
import com.hwezah.grabber.design.Type
import com.hwezah.grabber.design.frost
import com.hwezah.grabber.engine.Category
import kotlinx.coroutines.launch

private val FILTERS = listOf("All", "Active", "Done") + Category.entries.map { it.folder }

private fun iconOf(c: Category) = when (c) {
    Category.Video -> L.drawable.lucide_ic_film
    Category.Audio -> L.drawable.lucide_ic_music
    Category.Images -> L.drawable.lucide_ic_file_image
    Category.Documents -> L.drawable.lucide_ic_file_text
    Category.Archives -> L.drawable.lucide_ic_file_archive
    Category.Torrents -> L.drawable.lucide_ic_magnet
    Category.Other -> L.drawable.lucide_ic_file
}

private fun tintOf(c: Category) = when (c) {
    Category.Video -> Ink.red
    Category.Torrents -> Ink.orange
    Category.Audio -> Ink.green
    else -> Ink.muted
}

@Composable
fun DownloadsPage() {
    val items by Downloads.items.collectAsState()
    val live by Downloads.live.collectAsState()
    var filter by rememberSaveable { mutableStateOf("All") }
    var adding by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    /** The download just swiped off the list, offered back for a few seconds. */
    var removed by remember { mutableStateOf<Item?>(null) }
    androidx.compose.runtime.LaunchedEffect(removed) {
        val r = removed ?: return@LaunchedEffect
        kotlinx.coroutines.delay(4000)
        // Not undone: an unfinished download's part-file goes too (a finished file always stays).
        if (r.state != State.Done && Downloads.get(r.id) == null) {
            r.doc?.let { doc -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Folders.delete(context, doc) } }
            if (r.kind == Kind.Torrent) Torrents.forget(context, r)
        }
        removed = null
    }
    val shown = items.filter { i ->
        when (filter) {
            "All" -> true
            "Active" -> i.state != State.Done
            "Done" -> i.state == State.Done
            else -> i.category.folder == filter
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Heading("Downloads") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (items.isNotEmpty()) Button("", { menu = true }, icon = L.drawable.lucide_ic_sliders_horizontal, compact = true)
                    Button("Add", { adding = true }, icon = L.drawable.lucide_ic_plus, accent = Ink.red, compact = true)
                }
            }
            Row(Modifier.padding(horizontal = EDGE).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                // Upright phone: Video and Audio dropped from the row, for room.
                val portrait = androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenHeightDp > it.screenWidthDp && it.screenWidthDp < 600 }
                val filters = if (portrait) FILTERS - listOf("Video", "Audio") else FILTERS
                filters.forEachIndexed { i, c -> if (i > 0) Pipe(com.hwezah.grabber.design.pipeColor(i - 1)); Chip(c, c == filter, { filter = c }) }
            }
            if (items.isNotEmpty()) Text(
                "Swipe left to remove · swipe right to pause, resume or open",
                Modifier.fillMaxWidth().padding(top = 8.dp),
                style = Type.meta.copy(color = Ink.faint, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            )
            if (shown.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = EDGE * 3), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(72.dp).frost(CircleShape), contentAlignment = Alignment.Center) {
                            Lc(L.drawable.lucide_ic_download, tint = Ink.green, size = 30.dp)
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(if (items.isEmpty()) "Nothing downloading yet" else "Nothing here", style = Type.title)
                        Spacer(Modifier.height(6.dp))
                        Note("Tap Add and paste one link or many.")
                    }
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = EDGE, end = EDGE, top = 12.dp, bottom = BottomRoom),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id }) { item ->
                        val active = item.state == State.Running || item.state == State.Queued
                        SwipeRow(
                            onLeft = { Downloads.delete(item.id, false); removed = item },
                            onRight = {
                                when {
                                    active -> Downloads.pause(item.id)
                                    item.state == State.Done -> item.doc?.let { doc ->
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(doc), mimeOf(item)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                        }
                                    }
                                    else -> Downloads.resume(item.id)
                                }
                            },
                            leftIcon = L.drawable.lucide_ic_trash_2,
                            rightIcon = when {
                                active -> com.hwezah.grabber.design.R.drawable.pause_bars
                                item.state == State.Done -> L.drawable.lucide_ic_play
                                else -> L.drawable.lucide_ic_play
                            },
                            leftColor = Ink.red, rightColor = if (active) Ink.orange else Ink.green,
                            shape = RoundedCornerShape(18.dp), modifier = Modifier.animateItem(),
                        ) { DownloadRow(item, live[item.id]) { picked = item.id } }
                    }
                }
            }
        }
        removed?.let { r ->
            Row(
                Modifier.align(Alignment.BottomCenter).padding(start = EDGE, end = EDGE, bottom = BottomRoom).fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)).background(Ink.sheet).padding(start = CARD, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (r.state == State.Done) "Removed from the list (file kept)" else "Removed; its unfinished file goes too", Modifier.weight(1f), style = Type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Button("Undo", { Downloads.restore(r); removed = null }, accent = Ink.green, compact = true)
            }
        }
        if (adding) AddSheet { adding = false }
        if (menu) AllSheet { menu = false }
        picked?.let { id -> Downloads.get(id)?.let { ItemSheet(it) { picked = null } } ?: run { picked = null } }
    }
}

@Composable
private fun DownloadRow(item: Item, live: Live?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Tap(onClick, Modifier.fillMaxWidth(), shape) {
        val done = live?.bytes ?: item.done
        Column(Modifier.frost(shape).padding(top = 12.dp, bottom = 12.dp)) {
        // The play/pause target is 40dp around a 20dp icon: less padding on the right so the icon sits CARD from the edge.
        Row(Modifier.padding(start = CARD, end = CARD - 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(tintOf(item.category).copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Lc(iconOf(item.category), tint = tintOf(item.category), size = 20.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val pctOnly = item.kind == Kind.Media && item.state != State.Done
                val line = when (item.state) {
                    State.Running -> live?.text ?: if (live == null) "Starting…" else listOf(
                        Fmt.speed(live.speed),
                        Fmt.bytes(done) + if (item.size > 0) " of ${Fmt.bytes(item.size)}" else "",
                        if (item.size > 0) Fmt.eta(item.size - done, live.speed) else "",
                    ).filter { it.isNotEmpty() }.joinToString(" · ")
                    State.Queued -> "Waiting"
                    State.Paused -> "Paused · " + if (pctOnly) "${done / 10}%" else Fmt.bytes(done) + if (item.size > 0) " of ${Fmt.bytes(item.size)}" else ""
                    State.Done -> Fmt.bytes(item.size.coerceAtLeast(item.done))
                    State.Failed -> "Failed: " + (item.error ?: "unknown error")
                }
                Text(line, style = Type.meta.copy(color = if (item.state == State.Failed) Ink.danger else Ink.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(4.dp))
            when (item.state) {
                State.Running, State.Queued -> Tap({ Downloads.pause(item.id) }, Modifier.size(40.dp), CircleShape) {
                    Box(contentAlignment = Alignment.Center) { PauseBars() }
                }
                State.Paused, State.Failed -> Tap({ Downloads.resume(item.id) }, Modifier.size(40.dp), CircleShape) {
                    Box(contentAlignment = Alignment.Center) { Lc(L.drawable.lucide_ic_play, tint = Ink.green) }
                }
                State.Done -> {}
            }
        }
        // The bar under everything, across the whole row's content, a thin 3dp.
        if (item.state != State.Done) {
            val p = if (item.size > 0) (done.toFloat() / item.size).coerceIn(0f, 1f) else 0f
            Box(Modifier.padding(start = CARD, end = CARD, top = 10.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(Ink.text.copy(alpha = 0.1f))) {
                Box(Modifier.fillMaxWidth(p).height(3.dp).clip(CircleShape).background(if (item.state == State.Running) Ink.green else Ink.faint))
            }
        }
        }
    }
}

/** Open, share, pause/resume, copy link, delete. */
@Composable
private fun ItemSheet(item: Item, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    BottomSheet(item.name, onDismiss) {
        Column(Modifier.padding(horizontal = CARD)) {
            Text(item.url, style = Type.meta, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            StretchRow {
                if (item.state == State.Done && item.doc != null) {
                    Button("Open", {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.doc), mimeOf(item)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                    }, icon = L.drawable.lucide_ic_play, accent = Ink.green, compact = true)
                    Button("Share", {
                        val send = Intent(Intent.ACTION_SEND).setType(mimeOf(item)).putExtra(Intent.EXTRA_STREAM, Uri.parse(item.doc)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { context.startActivity(Intent.createChooser(send, item.name)) }
                    }, icon = L.drawable.lucide_ic_share_2, compact = true)
                } else if (item.state == State.Running || item.state == State.Queued) {
                    Button("Pause", { Downloads.pause(item.id) }, icon = com.hwezah.grabber.design.R.drawable.pause_bars, compact = true)
                } else {
                    Button("Resume", { Downloads.resume(item.id) }, icon = L.drawable.lucide_ic_play, accent = Ink.green, compact = true)
                }
                Button("Copy", {
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("link", item.url))
                }, icon = L.drawable.lucide_ic_copy, compact = true)
            }
            if (item.state == State.Failed && item.error?.contains("Start over") == true) {
                Spacer(Modifier.height(10.dp))
                Button("Start over", { Downloads.startOver(item.id); onDismiss() }, Modifier.solo(), icon = L.drawable.lucide_ic_rotate_cw, accent = Ink.orange)
            }
            if (item.state != State.Done && item.kind == Kind.Http) RefreshLink(item)
            if (item.state == State.Done && item.doc != null && item.category == Category.Video) {
                Spacer(Modifier.height(10.dp))
                Text("Convert to audio", style = Type.meta)
                StretchRow(Modifier.padding(top = 6.dp)) {
                    listOf("mp3", "m4a", "opus").sortedByDescending { it == Prefs.audioFormat }.forEach { fmt ->
                        Button(fmt.uppercase(), {
                            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
                            scope.launch {
                                runCatching {
                                    val f = Media.stage(context, item.doc, item.name)
                                    Downloads.add(listOf("file://" + f.absolutePath), name = item.name.substringBeforeLast('.'), kind = Kind.Media, format = "a:$fmt")
                                }
                            }
                            onDismiss()
                        }, accent = Ink.orange, compact = true)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (!confirm) {
                Button("Delete…", { confirm = true }, Modifier.solo(), icon = L.drawable.lucide_ic_trash_2)
            } else {
                StretchRow {
                    Button("Remove from list", { Downloads.delete(item.id, false); onDismiss() }, compact = true)
                    Button("Delete file too", { Downloads.delete(item.id, true); onDismiss() }, accent = Ink.red, compact = true)
                }
            }
        }
    }
}

/** Pause all, resume all, clear finished. */
@Composable
private fun AllSheet(onDismiss: () -> Unit) {
    BottomSheet("All downloads", onDismiss) {
        StretchRow(Modifier.padding(horizontal = CARD)) {
            Button("Pause all", { Downloads.pauseAll(); onDismiss() }, icon = com.hwezah.grabber.design.R.drawable.pause_bars, compact = true)
            Button("Resume all", { Downloads.resumeAll(); onDismiss() }, icon = L.drawable.lucide_ic_play, accent = Ink.green, compact = true)
            Button("Clear", { Downloads.clearFinished(); onDismiss() }, icon = L.drawable.lucide_ic_check, compact = true)
        }
    }
}

/** Choose the download folder; Android remembers the permission. */
@Composable
fun FolderRow() {
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            Prefs.useFolder(uri.toString())
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Download folder", style = Type.label.copy(fontWeight = FontWeight.SemiBold))
            Text(Folders.label(Prefs.folder) + " · one subfolder per type", style = Type.meta)
        }
        Button(if (Prefs.folder == null) "Choose" else "Change", { pick.launch(null) }, icon = L.drawable.lucide_ic_folder_open, accent = if (Prefs.folder == null) Ink.red else null, compact = true)
    }
}

/** Paste text with one or many links; tick the ones to download. */
@Composable
private fun AddSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(readClipboard(context)) }
    val found = remember(text) { LinkFinder.find(text).filterIsInstance<Found.Web>() }
    val off = remember { mutableStateListOf<String>() }
    val chosen = found.map { it.text }.filter { it !in off }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    BottomSheet("Add downloads", onDismiss) {
        Column(Modifier.padding(horizontal = CARD)) {
            if (Prefs.folder == null) {
                Text("First, choose where downloads go.", style = Type.body.copy(color = Ink.text))
                FolderRow()
                Spacer(Modifier.height(8.dp))
            }
            val shape = RoundedCornerShape(14.dp)
            Box(Modifier.fillMaxWidth().heightIn(min = 90.dp, max = 160.dp).frost(shape).padding(horizontal = CARD, vertical = 12.dp)) {
                if (text.isEmpty()) Text("Paste links, or any text with links in it", style = Type.label.copy(color = Ink.faint))
                BasicTextField(
                    text, { text = it }, Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), textStyle = Type.label, cursorBrush = SolidColor(Ink.red),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = {
                        if (chosen.isNotEmpty() && Prefs.folder != null) {
                            startDownloads(context, chosen) { notify.launch(Manifest.permission.POST_NOTIFICATIONS) }
                            onDismiss()
                        }
                    }),
                )
            }
            Spacer(Modifier.height(10.dp))
            StretchRow {
                Button("Paste", { text = readClipboard(context) }, icon = L.drawable.lucide_ic_clipboard_paste, compact = true)
                Button("Clear", { text = "" }, icon = L.drawable.lucide_ic_x, compact = true)
            }
            Spacer(Modifier.height(12.dp))
            Overline(if (found.isEmpty()) "No links found" else "${chosen.size} of ${found.size} selected", center = found.isEmpty())
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                found.forEach { f ->
                    val on = f.text !in off
                    Tap({ if (on) off += f.text else off -= f.text }, Modifier.fillMaxWidth(), RoundedCornerShape(8.dp)) {
                        Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(22.dp).clip(CircleShape).background(if (on) Ink.green else Ink.text.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                                if (on) Lc(L.drawable.lucide_ic_check, tint = Color.White, size = 14.dp)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(f.text, Modifier.weight(1f), style = Type.meta.copy(color = Ink.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                if (chosen.size > 1) "Download ${chosen.size}" else "Download",
                {
                    if (chosen.isEmpty() || Prefs.folder == null) return@Button
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notify.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    Downloads.add(chosen)
                    onDismiss()
                },
                Modifier.solo(), icon = L.drawable.lucide_ic_download, accent = if (chosen.isNotEmpty() && Prefs.folder != null) Ink.green else null,
            )
        }
    }
}

/** 1DM's "refresh link": a fresh link to the same file carries on from where the old one stopped. */
@Composable
private fun RefreshLink(item: Item) {
    var open by remember { mutableStateOf(item.error?.startsWith("Link expired") == true) }
    var link by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    Spacer(Modifier.height(10.dp))
    if (!open) {
        Button("Refresh link", { open = true }, Modifier.solo(), icon = L.drawable.lucide_ic_rotate_cw)
        return
    }
    Text("Paste a new link to the same file (get it again from the site). Progress is kept.", style = Type.meta)
    Spacer(Modifier.height(8.dp))
    Field(link, { link = it }, "New link")
    note?.let { Text(it, Modifier.padding(top = 6.dp), style = Type.meta.copy(color = Ink.orange)) }
    Spacer(Modifier.height(8.dp))
    StretchRow {
        Button("Paste", { link = readClipboard(context).trim() }, icon = L.drawable.lucide_ic_clipboard_paste, compact = true)
        Button(if (busy) "Checking…" else "Use it", {
            val url = LinkFinder.find(link).firstOrNull { it is Found.Web }?.text
            if (url == null) { note = "That isn't a link"; return@Button }
            busy = true
            scope.launch {
                val r = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.hwezah.grabber.engine.Http.probe(url, item.headers) } }
                busy = false
                r.onSuccess { info ->
                    if (item.size > 0 && info.size > 0 && info.size != item.size) {
                        note = "That file is ${Fmt.bytes(info.size)}, not ${Fmt.bytes(item.size)}: a different file"
                    } else {
                        Downloads.edit(item.id) { it.copy(url = info.finalUrl, resumable = info.resumable || it.resumable, state = State.Queued, error = null) }
                        Downloads.kick()
                        note = "Carrying on with the new link"
                        open = false
                    }
                }.onFailure { note = "That link doesn't work either: ${it.message}" }
            }
        }, accent = Ink.green, compact = true)
    }
}

/** Pause as two solid bars (Lucide's is an outline). */
@Composable
fun PauseBars(color: Color = Ink.text) {
    // Matched to the play icon beside it: its line weight (about 2dp at this size), its height, a clear gap.
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(2) { Box(Modifier.size(width = 2.dp, height = 16.dp).clip(RoundedCornerShape(1.dp)).background(color)) }
    }
}

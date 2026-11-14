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
import com.hwezah.grabber.engine.Http
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
import com.hwezah.grabber.design.Ink
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
            val on = !magnet && f.text !in off
            Tap({ if (!magnet) { if (on) off += f.text else off -= f.text } }, Modifier.fillMaxWidth(), RoundedCornerShape(8.dp)) {
                Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(22.dp).clip(CircleShape).background(if (on) Ink.green else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            magnet -> Lc(L.drawable.lucide_ic_magnet, tint = Ink.orange, size = 16.dp)
                            on -> Lc(L.drawable.lucide_ic_check, tint = Color.White, size = 14.dp)
                            else -> Lc(L.drawable.lucide_ic_link, tint = Ink.faint, size = 14.dp)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(f.text, Modifier.weight(1f), style = Type.meta.copy(color = if (on || magnet) Ink.text else Ink.faint), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (found.any { it is Found.Magnet }) Note("Torrents and magnets arrive in a later build.", Modifier.padding(top = 6.dp))
    }
}

/** Queues [urls], asking for notification permission first time on Android 13+. */
fun startDownloads(context: Context, urls: List<String>, askNotify: () -> Unit) {
    if (urls.isEmpty()) return
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) askNotify()
    Downloads.add(urls)
}

/**
 * What Grabber can do with some copied or shared [text]: download its links, copy it, keep it.
 * The text is already saved in the clipboard history when this shows.
 */
@Composable
fun CaptureSheet(text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val found = remember(text) { LinkFinder.find(text) }
    val off = remember { mutableStateListOf<String>() }
    val web = found.filterIsInstance<Found.Web>().map { it.text }.filter { it !in off }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val title = when {
        found.isEmpty() -> "Saved to clipboard"
        found.size == 1 -> if (found[0] is Found.Magnet) "Magnet link" else "Link caught"
        else -> "${found.size} links caught"
    }
    val single = found.size == 1 && found[0] is Found.Web
    BottomSheet(if (single) "Download file" else title, onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            if (found.isEmpty()) {
                Text(text, style = Type.body.copy(color = Ink.text), maxLines = 6, overflow = TextOverflow.Ellipsis)
            } else if (found.size == 1 && found[0] is Found.Web) {
                if (Prefs.folder == null) {
                    Text("First, choose where downloads go.", style = Type.body.copy(color = Ink.text))
                    FolderRow()
                }
                OneLink(found[0].text, onDismiss)
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = SpreadOut) {
                    Button("Copy", {
                        context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Grabber", text))
                        Capture.seen(context, text)
                        onDismiss()
                    }, icon = L.drawable.lucide_ic_copy, compact = true)
                    Button("Close", onDismiss, icon = L.drawable.lucide_ic_x, compact = true)
                }
                if (found.any { it is Found.Web }) {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        if (web.size > 1) "Download ${web.size}" else "Download",
                        {
                            if (Prefs.folder != null && web.isNotEmpty()) {
                                startDownloads(context, web) { notify.launch(Manifest.permission.POST_NOTIFICATIONS) }
                                onDismiss()
                            }
                        },
                        Modifier.fillMaxWidth(), icon = L.drawable.lucide_ic_download,
                        accent = if (Prefs.folder != null && web.isNotEmpty()) Ink.green else null,
                    )
                }
            }
        }
    }
}

/** One link, as 1DM shows it: checked with the server first, name editable, referer optional. */
@Composable
private fun OneLink(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var info by remember { mutableStateOf<com.hwezah.grabber.engine.Info?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var referer by remember { mutableStateOf("") }
    var tries by remember { mutableStateOf(0) }
    LaunchedEffect(url, tries) {
        checking = true
        error = null
        val headers = if (referer.isNotBlank()) mapOf("Referer" to referer.trim()) else emptyMap()
        runCatching { withContext(Dispatchers.IO) { Http.probe(url, headers) } }
            .onSuccess { info = it; if (name.isEmpty()) name = it.name }
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
        checking -> "Checking with the server…"
        error != null -> "Error: $error"
        else -> info!!.let { i ->
            listOfNotNull(
                if (i.size > 0) Fmt.bytes(i.size) else "Size unknown",
                i.mime,
                if (i.resumable) "resumable, ${Prefs.connections} connections" else "one connection, can't resume",
            ).joinToString(" · ")
        }
    }
    Text(status, style = Type.meta.copy(color = if (error != null) Ink.orange else Ink.muted))
    Spacer(Modifier.height(10.dp))
    Field(name, { name = it }, "File name")
    Spacer(Modifier.height(8.dp))
    Field(referer, { referer = it }, "Referer page (leave empty if not sure)")
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = SpreadOut) {
        Button("Cancel", onDismiss, compact = true)
        Button(if (checking) "Checking…" else "Check again", { tries++ }, icon = L.drawable.lucide_ic_rotate_cw, compact = true)
    }
    Spacer(Modifier.height(10.dp))
    Button(
        "Download", {
            if (Prefs.folder != null) {
                val headers = if (referer.isNotBlank()) mapOf("Referer" to referer.trim()) else emptyMap()
                if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notify.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                Downloads.add(listOf(info?.finalUrl ?: url), headers, name.takeIf { it != info?.name })
                onDismiss()
            }
        },
        Modifier.fillMaxWidth(), icon = L.drawable.lucide_ic_download, accent = if (Prefs.folder != null) Ink.green else null,
    )
}

/** A flat text box with a thin border. */
@Composable
fun Field(value: String, onValue: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, onGo: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.fillMaxWidth().border(1.dp, Ink.stroke, shape).padding(horizontal = 12.dp, vertical = 12.dp)) {
        if (value.isEmpty()) Text(placeholder, style = Type.label.copy(color = Ink.faint), maxLines = 1)
        BasicTextField(
            value, onValue, Modifier.fillMaxWidth(), singleLine = true, textStyle = Type.label, cursorBrush = SolidColor(Ink.red),
            keyboardOptions = KeyboardOptions(imeAction = if (onGo != null) ImeAction.Go else ImeAction.Done),
            keyboardActions = KeyboardActions(onGo = { onGo?.invoke() }),
        )
    }
}

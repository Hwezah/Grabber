package com.hwezah.grabber

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as L
import com.hwezah.grabber.design.BottomSheet
import com.hwezah.grabber.design.Button
import com.hwezah.grabber.design.CARD
import com.hwezah.grabber.design.Ink
import com.hwezah.grabber.design.Lc
import com.hwezah.grabber.design.Type
import com.hwezah.grabber.design.solo
import com.hwezah.grabber.engine.Names
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A shared file's name, type and size, as the sending app reports them. */
data class Shared(val uri: Uri, val name: String, val mime: String?, val size: Long)

fun describe(context: Context, uri: Uri): Shared {
    var name: String? = null
    var size = -1L
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                size = if (c.isNull(1)) -1 else c.getLong(1)
            }
        }
    }
    val mime = context.contentResolver.getType(uri)
    val ext = mime?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
    val fallback = "Grabber_${System.currentTimeMillis()}" + (ext?.let { ".$it" } ?: "")
    return Shared(uri, Names.clean(name ?: uri.lastPathSegment?.substringAfterLast('/') ?: fallback).ifBlank { fallback }.let {
        if (!it.contains('.') && ext != null) "$it.$ext" else it
    }, mime, size)
}

/** Copies a shared file into the download folder's category, and lists it under Downloads as done. */
fun saveShared(context: Context, s: Shared) {
    val tree = Prefs.folder ?: error("Choose a download folder in Settings first")
    val cat = Names.category(s.name, s.mime)
    val doc = Folders.create(context, tree, cat, s.name) ?: error("Can't write to the download folder")
    var size = 0L
    context.contentResolver.openOutputStream(doc)?.use { o ->
        context.contentResolver.openInputStream(s.uri)?.use { i -> size = i.copyTo(o, 256 * 1024) } ?: error("Can't read ${s.name}")
    }
    val name = doc.lastPathSegment?.substringAfterLast('/') ?: s.name
    Downloads.addFinished(s.uri.toString(), name, s.mime, cat, doc.toString(), size)
}

/** Share → Grabber with photos or videos (a WhatsApp status): save them, one tap. */
@Composable
fun SaveFilesSheet(uris: List<Uri>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shared = remember(uris) { uris.map { describe(context, it) } }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    BottomSheet(if (shared.size == 1) "Save file" else "Save ${shared.size} files", onDismiss, icon = L.drawable.lucide_ic_download, iconTint = Ink.green) {
        Column(Modifier.padding(horizontal = CARD)) {
            if (Prefs.folder == null) {
                Text("First, choose where files go.", style = Type.body.copy(color = Ink.text))
                FolderRow()
            }
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                shared.forEach { s ->
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Lc(
                            when {
                                s.mime?.startsWith("video/") == true -> L.drawable.lucide_ic_film
                                s.mime?.startsWith("image/") == true -> L.drawable.lucide_ic_file_image
                                s.mime?.startsWith("audio/") == true -> L.drawable.lucide_ic_music
                                else -> L.drawable.lucide_ic_file
                            },
                            tint = Ink.green, size = 18.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(s.name, Modifier.weight(1f), style = Type.meta.copy(color = Ink.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (s.size > 0) Text(Fmt.bytes(s.size), Modifier.padding(start = 8.dp), style = Type.meta)
                    }
                }
            }
            note?.let { Text(it, Modifier.padding(top = 6.dp), style = Type.meta.copy(color = Ink.orange)) }
            Spacer(Modifier.height(12.dp))
            Button(
                if (busy) "Saving…" else "Save", {
                    if (busy || Prefs.folder == null) return@Button
                    busy = true
                    scope.launch {
                        val failed = withContext(Dispatchers.IO) { shared.mapNotNull { s -> runCatching { saveShared(context, s) }.exceptionOrNull()?.message } }
                        busy = false
                        if (failed.isEmpty()) {
                            android.widget.Toast.makeText(context, if (shared.size == 1) "Saved" else "Saved ${shared.size} files", android.widget.Toast.LENGTH_SHORT).show()
                            onDismiss()
                        } else note = failed.first()
                    }
                },
                Modifier.solo(), icon = L.drawable.lucide_ic_download, accent = if (Prefs.folder != null) Ink.green else null,
            )
        }
    }
}

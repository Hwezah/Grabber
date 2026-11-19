package com.hwezah.grabber

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as L
import com.hwezah.grabber.design.BottomSheet
import com.hwezah.grabber.design.Button
import com.hwezah.grabber.design.CARD
import com.hwezah.grabber.design.Heading
import com.hwezah.grabber.design.Ink
import com.hwezah.grabber.design.Lc
import com.hwezah.grabber.design.Note
import com.hwezah.grabber.design.Tap
import com.hwezah.grabber.design.Type
import com.hwezah.grabber.design.grain
import com.hwezah.grabber.design.solo
import com.hwezah.grabber.engine.Category
import com.hwezah.grabber.engine.Names
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One thing in a folder. [count]: how many things a folder holds. */
private data class Entry(val id: String, val name: String, val folder: Boolean, val mime: String?, val size: Long, val modified: Long, val count: Int)

/** Reads the download folder through the permission the folder picker gave: nothing outside it, everything inside it. */
private object Browse {
    private val COLS = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    fun rootId(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree)

    fun uri(tree: Uri, id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private fun raw(context: Context, tree: Uri, id: String): List<Entry> {
        val out = ArrayList<Entry>()
        runCatching {
            context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), COLS, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    out += Entry(c.getString(0), name, mime == DocumentsContract.Document.MIME_TYPE_DIR, mime, c.getLong(3), c.getLong(4), 0)
                }
            }
        }
        // Never shown: hidden files and files still being written.
        return out.filterNot { it.name.startsWith(".") || it.name.endsWith(".part", true) }
    }

    /** A folder's contents: folders first, A to Z, then files, newest first. */
    fun list(context: Context, tree: Uri, id: String): List<Entry> {
        val all = raw(context, tree, id).map { if (it.folder) it.copy(count = raw(context, tree, it.id).size) else it }
        return all.filter { it.folder }.sortedBy { it.name.lowercase() } + all.filterNot { it.folder }.sortedByDescending { it.modified }
    }

    /** Deletes [e]; a folder at the top is emptied instead, so the layout stays. */
    fun delete(context: Context, tree: Uri, e: Entry, keepFolder: Boolean) {
        if (e.folder && keepFolder) raw(context, tree, e.id).forEach { runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri(tree, it.id)) } }
        else runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri(tree, e.id)) }
    }
}

private fun age(t: Long): String {
    if (t <= 0) return ""
    val m = (System.currentTimeMillis() - t) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 60 * 24 -> "${m / 60} h ago"
        m < 60 * 24 * 2 -> "yesterday"
        m < 60 * 24 * 30 -> "${m / (60 * 24)} days ago"
        m < 60 * 24 * 365 -> "${m / (60 * 24 * 30)} months ago"
        else -> "${m / (60 * 24 * 365)} years ago"
    }
}

private fun kindOf(e: Entry): Category = Names.category(e.name, e.mime)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesPage() {
    val context = LocalContext.current
    val tree = Prefs.folder?.let { Uri.parse(it) }
    // Where you are: folder ids and names from the top, kept when the phone turns.
    var ids by rememberSaveable { mutableStateOf(listOf<String>()) }
    var names by rememberSaveable { mutableStateOf(listOf<String>()) }
    var entries by remember { mutableStateOf<List<Entry>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var asked by remember { mutableStateOf<Entry?>(null) }
    // A download finishing or being removed refreshes the folder.
    val items by Downloads.items.collectAsState()
    val doneKey = items.count { it.state == State.Done } to items.size

    if (tree == null) {
        Column(Modifier.fillMaxSize().background(Ink.bg)) {
            Heading("Files")
            Note("Choose a download folder in Settings first.", Modifier.fillMaxWidth().padding(horizontal = CARD, vertical = 24.dp))
        }
        return
    }
    val root = remember(tree) { runCatching { Browse.rootId(tree) }.getOrNull() }
    if (root == null) return
    if (ids.isEmpty() || ids.first() != root) {
        ids = listOf(root)
        names = listOf(Folders.label(Prefs.folder).substringAfterLast('/').ifBlank { "Downloads" })
    }
    val here = ids.last()
    LaunchedEffect(here, reload, doneKey) {
        entries = withContext(Dispatchers.IO) { Browse.list(context, tree, here) }
    }
    fun up() { if (ids.size > 1) { ids = ids.dropLast(1); names = names.dropLast(1); entries = null } }
    BackHandler(ids.size > 1) { up() }

    Column(Modifier.fillMaxSize().background(Ink.bg)) {
        Heading("Files")
        // Where you are: tap a folder on the way to go back to it.
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ids.size > 1) {
                Tap({ up() }, Modifier.size(40.dp), CircleShape) {
                    Box(contentAlignment = Alignment.Center) { Lc(L.drawable.lucide_ic_arrow_left, tint = Ink.text, size = 24.dp) }
                }
                Spacer(Modifier.width(6.dp))
            }
            Row(Modifier.weight(1f), horizontalArrangement = if (names.size > 1) Arrangement.SpaceBetween else Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                names.forEachIndexed { i, n ->
                    if (i > 0) Text("›", style = Type.label.copy(fontSize = 20.sp, color = Ink.faint))
                    val last = i == names.lastIndex
                    Tap({ if (!last) { ids = ids.take(i + 1); names = names.take(i + 1); entries = null } }, Modifier.weight(1f, fill = false), RoundedCornerShape(8.dp)) {
                        Text(
                            n, Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                            style = Type.label.copy(fontSize = 16.sp, color = if (last) Ink.text else Ink.muted, fontWeight = if (last) FontWeight.SemiBold else FontWeight.Medium),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        val list = entries
        if (list != null && list.isEmpty()) {
            Note(
                if (ids.size == 1) "Finished downloads land here, sorted into folders." else "This folder is empty.",
                Modifier.fillMaxWidth().padding(horizontal = CARD, vertical = 24.dp),
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 180.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(list.orEmpty(), key = { it.id }) { e ->
                FileCard(e, onOpen = {
                    if (e.folder) {
                        ids = ids + e.id
                        names = names + e.name
                        entries = null
                    } else open(context, Browse.uri(tree, e.id), e)
                }, onLong = { asked = e })
            }
        }
    }

    asked?.let { e ->
        val keep = e.folder && ids.size == 1
        BottomSheet(
            if (e.folder) "Delete the ${e.name} folder and everything in it?" else "Delete ${e.name}?",
            { asked = null }, icon = L.drawable.lucide_ic_trash_2, iconTint = Ink.danger,
        ) {
            Column(Modifier.padding(horizontal = CARD)) {
                Text(
                    if (e.folder) (if (e.count == 1) "1 item" else "${e.count} items") + if (keep) " · the folder itself stays" else "" else Fmt.bytes(e.size),
                    style = Type.meta,
                )
                Spacer(Modifier.padding(top = 12.dp))
                Button("Delete from this phone", {
                    asked = null
                    val folderUri = Browse.uri(tree, e.id)
                    kotlin.concurrent.thread(name = "grabber-files-delete") {
                        Browse.delete(context, tree, e, keep)
                        // Gone from the downloads list too.
                        val gone = DocumentsContract.getDocumentId(folderUri)
                        Downloads.items.value.filter { item ->
                            val id = item.doc?.let { runCatching { DocumentsContract.getDocumentId(Uri.parse(it)) }.getOrNull() } ?: return@filter false
                            id == gone || id.startsWith("$gone/")
                        }.forEach { Downloads.delete(it.id, false) }
                        android.os.Handler(android.os.Looper.getMainLooper()).post { reload++ }
                    }
                }, Modifier.solo(), icon = L.drawable.lucide_ic_trash_2, accent = Ink.danger)
                Spacer(Modifier.padding(top = 8.dp))
                Button("Keep it", { asked = null }, Modifier.solo())
            }
        }
    }
}

/** Opens a file with whichever app the person picks; videos too, as Grabber has no player of its own. */
private fun open(context: Context, uri: Uri, e: Entry) {
    val mime = e.mime?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
        ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(e.name.substringAfterLast('.', "").lowercase())
        ?: "*/*"
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (view.resolveActivity(context.packageManager) == null && context.packageManager.queryIntentActivities(view, 0).isEmpty()) {
        android.widget.Toast.makeText(context, "No app here opens ${e.name.substringAfterLast('.', "this").uppercase()}.", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    runCatching { context.startActivity(Intent.createChooser(view, e.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
        android.widget.Toast.makeText(context, "No app here opens ${e.name.substringAfterLast('.', "this").uppercase()}.", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileCard(e: Entry, onOpen: () -> Unit, onLong: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val kind = if (e.folder) null else kindOf(e)
    Tap(onOpen, Modifier.fillMaxWidth(), shape, onLongClick = onLong) {
        Row(
            Modifier.clip(shape).background(Ink.card).grain().padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Lc(
                when {
                    e.folder && e.name == "YouTube" -> L.drawable.lucide_ic_youtube
                    e.folder -> L.drawable.lucide_ic_folder
                    kind == Category.Video -> L.drawable.lucide_ic_film
                    kind == Category.Audio -> L.drawable.lucide_ic_music
                    kind == Category.Images -> L.drawable.lucide_ic_file_image
                    else -> L.drawable.lucide_ic_file_text
                },
                tint = Ink.red, size = 26.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name, Modifier.basicMarquee(), style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                Text(
                    if (e.folder) (if (e.count == 1) "1 item" else "${e.count} items")
                    else listOf(Fmt.bytes(e.size), age(e.modified)).filter { it.isNotBlank() }.joinToString(" · "),
                    Modifier.padding(top = 2.dp), style = Type.meta.copy(fontSize = 12.sp), maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            Lc(
                when {
                    e.folder -> L.drawable.lucide_ic_chevron_right
                    kind == Category.Video -> L.drawable.lucide_ic_play
                    else -> L.drawable.lucide_ic_external_link
                },
                tint = Ink.muted, size = 20.dp,
            )
        }
    }
}

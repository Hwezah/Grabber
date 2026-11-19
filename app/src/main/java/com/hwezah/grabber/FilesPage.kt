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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.lazy.itemsIndexed
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

    /** Folders inside [id] only, for the move/copy picker. */
    fun folders(context: Context, tree: Uri, id: String): List<Entry> = raw(context, tree, id).filter { it.folder }.sortedBy { it.name.lowercase() }

    fun mkdir(context: Context, tree: Uri, parent: String, name: String): Boolean =
        runCatching { DocumentsContract.createDocument(context.contentResolver, uri(tree, parent), DocumentsContract.Document.MIME_TYPE_DIR, name) != null }.getOrDefault(false)

    /** The downloads list follows a file or folder that moved or was renamed from [oldId] to [now]. */
    private fun follow(tree: Uri, oldId: String, now: Uri) {
        val newId = runCatching { DocumentsContract.getDocumentId(now) }.getOrNull() ?: return
        Downloads.items.value.forEach { item ->
            val id = item.doc?.let { runCatching { DocumentsContract.getDocumentId(Uri.parse(it)) }.getOrNull() } ?: return@forEach
            val moved = when {
                id == oldId -> newId
                id.startsWith("$oldId/") -> newId + id.removePrefix(oldId)
                else -> return@forEach
            }
            Downloads.edit(item.id) { it.copy(doc = uri(tree, moved).toString()) }
        }
    }

    fun rename(context: Context, tree: Uri, e: Entry, name: String): Boolean = runCatching {
        val now = DocumentsContract.renameDocument(context.contentResolver, uri(tree, e.id), name) ?: return false
        follow(tree, e.id, now)
        true
    }.getOrDefault(false)

    fun move(context: Context, tree: Uri, e: Entry, from: String, to: String): Boolean = runCatching {
        val now = DocumentsContract.moveDocument(context.contentResolver, uri(tree, e.id), uri(tree, from), uri(tree, to)) ?: return false
        follow(tree, e.id, now)
        true
    }.getOrDefault(false)

    /** Copies a file, or a folder and everything in it, into [to]. */
    fun copy(context: Context, tree: Uri, e: Entry, to: String): Boolean = runCatching {
        val cr = context.contentResolver
        if (e.folder) {
            val made = DocumentsContract.createDocument(cr, uri(tree, to), DocumentsContract.Document.MIME_TYPE_DIR, e.name) ?: return false
            val madeId = DocumentsContract.getDocumentId(made)
            raw(context, tree, e.id).all { copy(context, tree, it, madeId) }
        } else {
            val made = DocumentsContract.createDocument(cr, uri(tree, to), "application/octet-stream", e.name) ?: return false
            cr.openInputStream(uri(tree, e.id))?.use { i -> cr.openOutputStream(made)?.use { o -> i.copyTo(o, 512 * 1024) } }
            true
        }
    }.getOrDefault(false)

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
    /** The ⋮ sheet for this entry. */
    var acting by remember { mutableStateOf<Entry?>(null) }
    var renaming by remember { mutableStateOf<Entry?>(null) }
    /** Picking where to move (false) or copy (true) this entry. */
    var placing by remember { mutableStateOf<Pair<Entry, Boolean>?>(null) }
    var making by remember { mutableStateOf(false) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    fun work(done: String, failed: String, job: () -> Boolean) {
        kotlin.concurrent.thread(name = "grabber-files") {
            val ok = job()
            main.post {
                reload++
                android.widget.Toast.makeText(context, if (ok) done else failed, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
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
    // Opening Files tidies loose videos into Shows, Movies and YouTube first, and says so.
    LaunchedEffect(Unit) {
        val n = withContext(Dispatchers.IO) { runCatching { Folders.shelveAll(context) }.getOrDefault(0) }
        val why = Folders.lastProblem
        when {
            n > 0 -> android.widget.Toast.makeText(context, "Sorted $n video${if (n == 1) "" else "s"} into their folders", android.widget.Toast.LENGTH_SHORT).show()
            why != null -> android.widget.Toast.makeText(context, "Couldn't sort a video: $why", android.widget.Toast.LENGTH_LONG).show()
        }
        if (n > 0) reload++
    }
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
            Tap({ making = true }, Modifier.size(40.dp), CircleShape) {
                Box(contentAlignment = Alignment.Center) { Lc(L.drawable.lucide_ic_folder_plus, tint = Ink.text, size = 22.dp) }
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
                }, onMore = { acting = e })
            }
        }
    }

    val top = ids.size == 1
    acting?.let { e ->
        val fixed = e.folder && top
        BottomSheet(e.name, { acting = null }, icon = if (e.folder) L.drawable.lucide_ic_folder else L.drawable.lucide_ic_file) {
            Column(Modifier.padding(horizontal = CARD), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!e.folder) com.hwezah.grabber.design.StretchRow {
                    Button("Open With", { acting = null; open(context, Browse.uri(tree, e.id), e) }, icon = L.drawable.lucide_ic_external_link, compact = true)
                    Button("Share", {
                        acting = null
                        val send = Intent(Intent.ACTION_SEND).setType(e.mime ?: "*/*").putExtra(Intent.EXTRA_STREAM, Browse.uri(tree, e.id)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { context.startActivity(Intent.createChooser(send, e.name)) }
                    }, icon = L.drawable.lucide_ic_share_2, compact = true)
                }
                com.hwezah.grabber.design.StretchRow {
                    if (!fixed) Button("Move To", { acting = null; placing = e to false }, icon = L.drawable.lucide_ic_folder_input, compact = true)
                    Button("Copy To", { acting = null; placing = e to true }, icon = L.drawable.lucide_ic_copy, compact = true)
                }
                if (!fixed) Button("Rename", { acting = null; renaming = e }, Modifier.solo(), icon = L.drawable.lucide_ic_pencil, compact = true)
                Button(if (fixed) "Empty Folder" else "Delete", { acting = null; asked = e }, Modifier.solo(), icon = L.drawable.lucide_ic_trash_2, accent = Ink.danger, compact = true)
            }
        }
    }
    renaming?.let { e ->
        var name by remember(e) { mutableStateOf(e.name) }
        BottomSheet("Rename", { renaming = null }, icon = L.drawable.lucide_ic_pencil) {
            Column(Modifier.padding(horizontal = CARD)) {
                Field(name, { name = it }, "Name")
                Spacer(Modifier.padding(top = 12.dp))
                Button("Save", {
                    val n = name.trim()
                    renaming = null
                    if (n.isNotEmpty() && n != e.name) work("Renamed", "Couldn't rename it") { Browse.rename(context, tree, e, n) }
                }, Modifier.solo(), accent = Ink.red)
            }
        }
    }
    if (making) {
        var name by remember { mutableStateOf("") }
        BottomSheet("New Folder", { making = false }, icon = L.drawable.lucide_ic_folder_plus) {
            Column(Modifier.padding(horizontal = CARD)) {
                Field(name, { name = it }, "Folder name")
                Spacer(Modifier.padding(top = 12.dp))
                Button("Create", {
                    val n = name.trim()
                    making = false
                    if (n.isNotEmpty()) work("Folder made", "Couldn't make the folder") { Browse.mkdir(context, tree, here, n) }
                }, Modifier.solo(), accent = Ink.red)
            }
        }
    }
    placing?.let { (e, copying) ->
        FolderPicker(tree, root, names.first(), e, copying, onDismiss = { placing = null }) { target ->
            placing = null
            val from = here
            if (copying) work("Copied", "Couldn't copy it") { Browse.copy(context, tree, e, target) }
            else if (target != from) work("Moved", "Couldn't move it") { Browse.move(context, tree, e, from, target) }
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
private fun FileCard(e: Entry, onOpen: () -> Unit, onMore: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val kind = if (e.folder) null else kindOf(e)
    Tap(onOpen, Modifier.fillMaxWidth(), shape, onLongClick = onMore) {
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
            // Options: open with, share, move, copy, rename, delete. A long press opens the same.
            Tap(onMore, Modifier.size(40.dp), CircleShape) {
                Box(contentAlignment = Alignment.Center) { Lc(L.drawable.lucide_ic_ellipsis_vertical, tint = Ink.muted, size = 20.dp) }
            }
        }
    }
}

/** Where to move or copy [e]: browse the folders, make a new one, then "Move here" or "Copy here". */
@Composable
private fun FolderPicker(tree: Uri, root: String, rootName: String, e: Entry, copying: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    var ids by remember { mutableStateOf(listOf(root)) }
    var names by remember { mutableStateOf(listOf(rootName)) }
    var folders by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var newName by remember { mutableStateOf<String?>(null) }
    val here = ids.last()
    LaunchedEffect(here, reload) { folders = withContext(Dispatchers.IO) { Browse.folders(context, tree, here) } }
    // A folder can't go inside itself.
    val inside = e.folder && (here == e.id || here.startsWith(e.id + "/"))
    BottomSheet(if (copying) "Copy ${e.name}" else "Move ${e.name}", onDismiss, icon = if (copying) L.drawable.lucide_ic_copy else L.drawable.lucide_ic_folder_input) {
        Column(Modifier.padding(horizontal = CARD)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ids.size > 1) Tap({ ids = ids.dropLast(1); names = names.dropLast(1) }, Modifier.size(40.dp), CircleShape) {
                    Box(contentAlignment = Alignment.Center) { Lc(L.drawable.lucide_ic_arrow_left, tint = Ink.text, size = 22.dp) }
                }
                Text(names.joinToString(" › "), Modifier.weight(1f).padding(start = 4.dp), style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Tap({ newName = "" }, Modifier.size(40.dp), CircleShape) {
                    Box(contentAlignment = Alignment.Center) { Lc(L.drawable.lucide_ic_folder_plus, tint = Ink.text, size = 22.dp) }
                }
            }
            newName?.let { n ->
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Field(n, { newName = it }, "New folder name", Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Button("Make", {
                        val name = n.trim()
                        newName = null
                        if (name.isNotEmpty()) kotlin.concurrent.thread {
                            Browse.mkdir(context, tree, here, name)
                            android.os.Handler(android.os.Looper.getMainLooper()).post { reload++ }
                        }
                    }, compact = true)
                }
            }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.padding(top = 6.dp).androidx_heightCap()) {
                items(folders.filterNot { it.id == e.id }, key = { it.id }) { f ->
                    Tap({ ids = ids + f.id; names = names + f.name }, Modifier.fillMaxWidth(), RoundedCornerShape(10.dp)) {
                        Row(Modifier.padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Lc(L.drawable.lucide_ic_folder, tint = Ink.red, size = 22.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(f.name, Modifier.weight(1f), style = Type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Lc(L.drawable.lucide_ic_chevron_right, tint = Ink.muted, size = 18.dp)
                        }
                    }
                }
            }
            Spacer(Modifier.padding(top = 10.dp))
            Button(if (copying) "Copy Here" else "Move Here", { if (!inside) onPick(here) }, Modifier.solo(), accent = if (inside) null else Ink.red)
        }
    }
}

private fun Modifier.androidx_heightCap() = this.then(Modifier.heightIn(max = 320.dp))

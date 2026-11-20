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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import com.hwezah.grabber.design.StretchRow
import com.hwezah.grabber.design.Tap
import com.hwezah.grabber.design.Type
import com.hwezah.grabber.design.grain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One thing in a folder. [count]: how many things a folder holds. */
private data class Entry(val id: String, val name: String, val folder: Boolean, val mime: String?, val size: Long, val modified: Long, val count: Int)

private val VIDEO = setOf("mkv", "mp4", "avi", "mov", "m4v", "webm", "ts", "wmv", "flv", "mpg", "mpeg", "3gp")
private val AUDIO = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma")
private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp")
private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

/** A to Z ignoring case, with runs of digits compared as numbers: Season 2 before Season 10. */
private val NATURAL = Comparator<String> { a, b ->
    val pa = Regex("""\d+|\D+""").findAll(a.lowercase()).map { it.value }.toList()
    val pb = Regex("""\d+|\D+""").findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(pa.size, pb.size)) {
        val x = pa[i]
        val y = pb[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) {
            val tx = x.trimStart('0')
            val ty = y.trimStart('0')
            if (tx.length != ty.length) tx.length - ty.length else tx.compareTo(ty)
        } else x.compareTo(y)
        if (c != 0) return@Comparator c
    }
    pa.size - pb.size
}

/** Reads and changes the download folder through the permission the folder picker gave. */
private object Browse {
    private val COLS = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    fun rootId(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree)

    fun uri(tree: Uri, id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    /** Everything in [id]; [all] keeps hidden and half-written files too. */
    fun raw(context: Context, tree: Uri, id: String, all: Boolean = false): List<Entry> {
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
        return if (all) out else out.filterNot { it.name.startsWith(".") || it.name.endsWith(".part", true) }
    }

    /** A folder's contents: folders first in natural order, then files, newest first. */
    fun list(context: Context, tree: Uri, id: String): List<Entry> {
        val all = raw(context, tree, id).map { if (it.folder) it.copy(count = raw(context, tree, it.id).size) else it }
        return all.filter { it.folder }.sortedWith(compareBy(NATURAL) { it.name }) + all.filterNot { it.folder }.sortedByDescending { it.modified }
    }

    /** [name], or "name (2).ext"… whichever isn't taken in [dir] (ignoring case). A folder keeps its whole name. */
    fun freeName(context: Context, tree: Uri, dir: String, name: String, folder: Boolean): String {
        val taken = raw(context, tree, dir, all = true).map { it.name.lowercase() }.toHashSet()
        if (name.lowercase() !in taken) return name
        val e = if (folder) "" else ext(name).let { if (it.isEmpty() || it.length > 5) "" else ".$it" }
        val base = name.removeSuffix(e)
        var n = 2
        while ("$base ($n)$e".lowercase() in taken) n++
        return "$base ($n)$e"
    }

    fun has(context: Context, tree: Uri, dir: String, name: String, except: String? = null) =
        raw(context, tree, dir, all = true).any { it.id != except && it.name.equals(name, true) }

    fun mkdir(context: Context, tree: Uri, parent: String, name: String): Boolean =
        runCatching { DocumentsContract.createDocument(context.contentResolver, uri(tree, parent), DocumentsContract.Document.MIME_TYPE_DIR, name) != null }.getOrDefault(false)

    /** The downloads list follows a file or folder that moved or was renamed from [oldId] to [now]. */
    fun follow(tree: Uri, oldId: String, now: Uri) {
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

    /** Renames; a change of case alone goes through a hidden name first. Returns the new address or null. */
    fun rename(context: Context, tree: Uri, e: Entry, name: String): Uri? = runCatching {
        val cr = context.contentResolver
        var cur = uri(tree, e.id)
        if (name.equals(e.name, true)) cur = DocumentsContract.renameDocument(cr, cur, ".grabber-rename-${System.nanoTime()}") ?: return null
        val now = DocumentsContract.renameDocument(cr, cur, name) ?: return null
        follow(tree, e.id, now)
        now
    }.getOrNull()

    /** Total bytes in a file or folder. */
    fun sizeOf(context: Context, tree: Uri, e: Entry): Long =
        if (!e.folder) e.size.coerceAtLeast(0) else raw(context, tree, e.id, all = true).sumOf { sizeOf(context, tree, it) }

    fun hasPart(context: Context, tree: Uri, e: Entry): Boolean =
        e.folder && raw(context, tree, e.id, all = true).any { (!it.folder && it.name.endsWith(".part", true)) || hasPart(context, tree, it) }

    /** Copies [e] into [to] as [name], reporting bytes as they go; returns the copy's address. A half-made copy is removed. */
    fun copy(context: Context, tree: Uri, e: Entry, to: String, name: String, done: (Long) -> Unit): Uri? {
        val cr = context.contentResolver
        return if (e.folder) {
            val made = DocumentsContract.createDocument(cr, uri(tree, to), DocumentsContract.Document.MIME_TYPE_DIR, name) ?: return null
            val madeId = DocumentsContract.getDocumentId(made)
            for (c in raw(context, tree, e.id, all = true)) {
                if (copy(context, tree, c, madeId, c.name, done) == null) {
                    runCatching { DocumentsContract.deleteDocument(cr, made) }
                    return null
                }
            }
            made
        } else {
            val made = DocumentsContract.createDocument(cr, uri(tree, to), "application/octet-stream", name) ?: return null
            val ok = runCatching {
                cr.openInputStream(uri(tree, e.id))!!.use { i ->
                    cr.openOutputStream(made)!!.use { o ->
                        val buf = ByteArray(512 * 1024)
                        while (true) {
                            val n = i.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done(n.toLong())
                        }
                    }
                }
            }.isSuccess
            if (!ok) { runCatching { DocumentsContract.deleteDocument(cr, made) }; null } else made
        }
    }

    /** Deletes [e]; a fixed folder is emptied instead. */
    fun delete(context: Context, tree: Uri, e: Entry, empty: Boolean) {
        if (e.folder && empty) raw(context, tree, e.id, all = true).forEach { runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri(tree, it.id)) } }
        else runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri(tree, e.id)) }
    }

    /** Free bytes where [id] lives; -1 when it can't be told. */
    fun free(id: String): Long = runCatching {
        if (id.startsWith("primary:")) android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes
        else android.os.StatFs("/storage/" + id.substringBefore(':')).availableBytes
    }.getOrDefault(-1L)
}

/** The move or copy running now: one at a time, carried on even when the tab is left. */
private object FileJob {
    var label by mutableStateOf<String?>(null)
    var copying by mutableStateOf(false)
    var pct by mutableIntStateOf(0)
    var finished by mutableIntStateOf(0)
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

/** Something picked up to move or copy, and the folder it came from. */
private data class Carry(val e: Entry, val from: String, val copy: Boolean)

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
    var acting by remember { mutableStateOf<Entry?>(null) }
    var asked by remember { mutableStateOf<Entry?>(null) }
    var renaming by remember { mutableStateOf<Entry?>(null) }
    var making by remember { mutableStateOf(false) }
    var carry by remember { mutableStateOf<Carry?>(null) }
    val items by Downloads.items.collectAsState()
    val doneKey = items.count { it.state == State.Done } to items.size
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    fun say(text: String, long: Boolean = false) =
        android.widget.Toast.makeText(context, text, if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT).show()

    if (tree == null) {
        Column(Modifier.fillMaxSize()) {
            Heading("Files")
            Note("Choose a download folder in Settings first.", Modifier.fillMaxWidth().padding(horizontal = CARD, vertical = 24.dp))
        }
        return
    }
    val root = remember(tree) { runCatching { Browse.rootId(tree) }.getOrNull() } ?: return
    if (ids.isEmpty() || ids.first() != root) {
        ids = listOf(root)
        names = listOf(Folders.label(Prefs.folder).substringAfterLast('/').ifBlank { "Downloads" })
    }
    val here = ids.last()
    // This folder's path from the top ("Video/Shows"), for telling the fixed folders.
    val herePath = names.drop(1).joinToString("/")
    fun fixed(e: Entry) = e.folder && Folders.isFixed(if (herePath.isEmpty()) e.name else "$herePath/${e.name}")

    // Opening Files tidies loose videos into Shows, Movies and YouTube first, and says so.
    LaunchedEffect(tree) {
        val n = withContext(Dispatchers.IO) { Folders.housekeep(context) }
        val why = Folders.lastProblem
        when {
            n > 0 -> say("Sorted $n video${if (n == 1) "" else "s"} into their folders")
            why != null -> say("Couldn't sort a video: $why", long = true)
        }
        if (n > 0) reload++
    }
    LaunchedEffect(here, reload, doneKey, FileJob.finished) {
        entries = withContext(Dispatchers.IO) { Browse.list(context, tree, here) }
    }
    fun go(i: Int) { ids = ids.take(i + 1); names = names.take(i + 1); entries = null }
    fun up() { if (ids.size > 1) go(ids.size - 2) }
    BackHandler(ids.size > 1 || carry != null) { if (ids.size > 1) up() else carry = null }

    /** Moves or copies what's carried into this folder, in the background. */
    fun drop(c: Carry) {
        if (FileJob.label != null) { say("One at a time: wait for the ${if (FileJob.copying) "copy" else "move"} to finish."); return }
        if (c.e.folder && (here == c.e.id || here.startsWith(c.e.id + "/"))) { say("A folder can't go inside itself."); return }
        if (here == c.from && !c.copy) { say("It's already in this folder."); return }
        val target = here
        val where = names.joinToString(" › ")
        carry = null
        FileJob.label = (if (c.copy) "Copying " else "Moving ") + c.e.name
        FileJob.copying = c.copy
        FileJob.pct = 0
        kotlin.concurrent.thread(name = "grabber-files-job") {
            val result: String = try {
                if (!c.copy && Browse.hasPart(context, tree, c.e)) throw IllegalStateException("it still has a download in progress")
                val name = Browse.freeName(context, tree, target, c.e.name, c.e.folder)
                var moved: Uri? = null
                if (!c.copy) {
                    // A move is a rename into the other folder; where that can't be done (phone to SD card), a copy, then the original goes.
                    moved = runCatching {
                        val src = if (name != c.e.name) Browse.rename(context, tree, c.e, name) ?: error("rename failed") else Browse.uri(tree, c.e.id)
                        DocumentsContract.moveDocument(context.contentResolver, src, Browse.uri(tree, c.from), Browse.uri(tree, target))
                    }.getOrNull()
                    moved?.let { Browse.follow(tree, c.e.id, it) }
                }
                if (moved == null) {
                    val source = Browse.raw(context, tree, c.from, all = true).firstOrNull { it.name.equals(name, true) || it.id == c.e.id } ?: c.e
                    val total = Browse.sizeOf(context, tree, source)
                    val free = Browse.free(target)
                    if (free in 0 until total) throw IllegalStateException("not enough room (it needs ${Fmt.bytes(total)}, ${Fmt.bytes(free)} free)")
                    var done = 0L
                    val made = Browse.copy(context, tree, source, target, name) { n ->
                        done += n
                        if (total > 0) main.post { FileJob.pct = (done * 100 / total).toInt().coerceIn(0, 100) }
                    } ?: throw IllegalStateException("couldn't write the copy")
                    if (!c.copy) {
                        Browse.follow(tree, source.id, made)
                        Browse.delete(context, tree, source, empty = false)
                    }
                }
                (if (c.copy) "Copied to " else "Moved to ") + where
            } catch (e: Exception) {
                (if (c.copy) "Couldn't copy it: " else "Couldn't move it: ") + (e.message ?: "unknown error")
            }
            main.post {
                FileJob.label = null
                FileJob.finished++
                say(result, long = true)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Heading("Files") {
            Tap({ making = true }, Modifier.semantics { contentDescription = "New Folder" }, CircleShape) {
                Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) { Lc(L.drawable.lucide_ic_folder_plus, tint = Ink.text, size = 24.dp) }
            }
        }
        // Saving into Grabber!/Video makes Video/Video…: say so, with the picker one tap away.
        if (Folders.wrongRoot(tree.toString())) Column(Modifier.padding(horizontal = CARD).padding(bottom = 8.dp)) { FolderRow() }
        // Where you are: tap a folder on the way to go back to it.
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ids.size > 1) {
                Tap({ up() }, Modifier.size(40.dp).semantics { contentDescription = "Up a folder" }, CircleShape) {
                    Box(contentAlignment = Alignment.Center) { Lc(L.drawable.lucide_ic_arrow_left, tint = Ink.text, size = 24.dp) }
                }
                Spacer(Modifier.width(6.dp))
            }
            Row(Modifier.weight(1f), horizontalArrangement = if (names.size > 1) Arrangement.SpaceBetween else Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                names.forEachIndexed { i, n ->
                    if (i > 0) Text("›", style = Type.label.copy(fontSize = 20.sp, color = Ink.faint))
                    val last = i == names.lastIndex
                    Tap({ if (!last) go(i) }, Modifier.weight(1f, fill = false), RoundedCornerShape(6.dp)) {
                        Text(
                            n, Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                            style = Type.label.copy(fontSize = 16.sp, color = if (last) Ink.text else Ink.muted, fontWeight = if (last) FontWeight.SemiBold else FontWeight.Medium),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        // Picked up to move or copy: open the folder it goes in, then Move Here.
        carry?.let { c ->
            Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.card).grain().padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Lc(if (c.copy) L.drawable.lucide_ic_copy else L.drawable.lucide_ic_folder_input, tint = Ink.red, size = 22.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (c.copy) "Copying" else "Moving", style = Type.meta.copy(fontSize = 12.sp))
                        Text(c.e.name, Modifier.basicMarquee(), style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                    }
                }
                Text("Open the folder it goes in, then tap ${if (c.copy) "Copy" else "Move"} Here.", Modifier.padding(top = 6.dp), style = Type.meta.copy(color = Ink.faint))
                Spacer(Modifier.height(10.dp))
                StretchRow {
                    Button("Cancel", { carry = null }, compact = true)
                    Button(if (c.copy) "Copy Here" else "Move Here", { drop(c) }, accent = Ink.red, compact = true)
                }
            }
        }
        // A move or copy under way.
        FileJob.label?.let { label ->
            Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.card).grain().padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Lc(if (FileJob.copying) L.drawable.lucide_ic_copy else L.drawable.lucide_ic_folder_input, tint = Ink.red, size = 22.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(label, Modifier.weight(1f).basicMarquee(), style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                    Text("${FileJob.pct}%", style = Type.meta)
                }
                Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(Ink.text.copy(alpha = 0.1f))) {
                    Box(Modifier.fillMaxWidth(FileJob.pct / 100f).height(3.dp).clip(CircleShape).background(Ink.red))
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
                val c = carry
                // While carrying: files and the thing being carried rest; other folders still open.
                val resting = c != null && (!e.folder || e.id == c.e.id)
                val fx = fixed(e)
                FileCard(
                    e, resting = resting, quiet = c != null,
                    onOpen = {
                        if (!resting) {
                            if (e.folder) { ids = ids + e.id; names = names + e.name; entries = null }
                            else openFile(context, Browse.uri(tree, e.id), e)
                        }
                    },
                    onMore = { acting = e },
                    actions = listOfNotNull(
                        if (!fx) "Move" to { carry = Carry(e, here, false) } else null,
                        "Copy" to { carry = Carry(e, here, true) },
                        if (!fx) "Rename" to { renaming = e } else null,
                        (if (fx) "Empty" else "Delete") to { asked = e },
                    ),
                )
            }
        }
    }

    acting?.let { e ->
        val fx = fixed(e)
        BottomSheet(e.name, { acting = null }, icon = if (e.folder) L.drawable.lucide_ic_folder else L.drawable.lucide_ic_file) {
            Column(Modifier.padding(horizontal = CARD)) {
                if (!fx) ActionRow(L.drawable.lucide_ic_folder_input, "Move", "Pick the folder it goes in") { acting = null; carry = Carry(e, here, false) }
                ActionRow(L.drawable.lucide_ic_copy, "Copy", "Keep this one, put a copy in another folder") { acting = null; carry = Carry(e, here, true) }
                if (!fx) ActionRow(L.drawable.lucide_ic_pencil, "Rename", null) { acting = null; renaming = e }
                ActionRow(
                    L.drawable.lucide_ic_trash_2, if (fx) "Empty" else "Delete",
                    if (e.folder) (if (e.count == 1) "1 item" else "${e.count} items") else Fmt.bytes(e.size), danger = true,
                ) { acting = null; asked = e }
            }
        }
    }

    renaming?.let { e ->
        val keepExt = if (e.folder) "" else ext(e.name).let { if (it.isEmpty() || it.length > 5) "" else ".$it" }
        var name by remember(e) { mutableStateOf(e.name.removeSuffix(keepExt)) }
        var error by remember(e) { mutableStateOf<String?>(null) }
        BottomSheet("Rename", { renaming = null }, icon = L.drawable.lucide_ic_pencil) {
            Column(Modifier.padding(horizontal = CARD)) {
                Field(name, { name = it; error = null }, "Name")
                error?.let { Text(it, Modifier.padding(top = 6.dp), style = Type.meta.copy(color = Ink.danger)) }
                Spacer(Modifier.height(12.dp))
                StretchRow {
                    Button("Cancel", { renaming = null }, compact = true)
                    Button("Rename", {
                        // The extension always goes back on: a rename can't change the type.
                        val n = Folders.safe(name).trimStart('.').ifBlank { "Download" } + keepExt
                        if (n == e.name) renaming = null
                        else kotlin.concurrent.thread {
                            val msg = when {
                                Browse.has(context, tree, here, n, except = e.id) -> "There's already a $n here."
                                Browse.rename(context, tree, e, n) == null -> "Couldn't rename it here."
                                else -> null
                            }
                            main.post { if (msg != null) error = msg else { renaming = null; reload++ } }
                        }
                    }, accent = Ink.red, compact = true)
                }
            }
        }
    }

    if (making) {
        var name by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        BottomSheet("New Folder", { making = false }, icon = L.drawable.lucide_ic_folder_plus) {
            Column(Modifier.padding(horizontal = CARD)) {
                Field(name, { name = it; error = null }, "Name")
                error?.let { Text(it, Modifier.padding(top = 6.dp), style = Type.meta.copy(color = Ink.danger)) }
                Spacer(Modifier.height(12.dp))
                StretchRow {
                    Button("Cancel", { making = false }, compact = true)
                    Button("Create", {
                        if (name.isNotBlank()) {
                            val n = Folders.safe(name).trimStart('.').ifBlank { "Download" }
                            kotlin.concurrent.thread {
                                val msg = when {
                                    Browse.has(context, tree, here, n) -> "There's already a $n here."
                                    !Browse.mkdir(context, tree, here, n) -> "Couldn't make a folder here."
                                    else -> null
                                }
                                main.post { if (msg != null) error = msg else { making = false; reload++ } }
                            }
                        }
                    }, accent = Ink.red, compact = true)
                }
            }
        }
    }

    asked?.let { e ->
        val empty = fixed(e)
        BottomSheet(
            when {
                empty -> "Empty the ${e.name} folder?"
                e.folder -> "Delete the ${e.name} folder and everything in it?"
                else -> "Delete ${e.name}?"
            },
            { asked = null }, icon = L.drawable.lucide_ic_trash_2, iconTint = Ink.danger,
        ) {
            Column(Modifier.padding(horizontal = CARD)) {
                ActionRow(L.drawable.lucide_ic_trash_2, if (empty) "Delete everything in it" else "Delete from this phone", null, danger = true) {
                    asked = null
                    val gone = e.id
                    kotlin.concurrent.thread(name = "grabber-files-delete") {
                        Browse.delete(context, tree, e, empty)
                        // Gone from the downloads list too.
                        Downloads.items.value.filter { item ->
                            val id = item.doc?.let { runCatching { DocumentsContract.getDocumentId(Uri.parse(it)) }.getOrNull() } ?: return@filter false
                            if (empty) id.startsWith("$gone/") else id == gone || id.startsWith("$gone/")
                        }.forEach { Downloads.delete(it.id, false) }
                        main.post { reload++ }
                    }
                }
                ActionRow(L.drawable.lucide_ic_check, "Keep it", null) { asked = null }
            }
        }
    }
}

/** A row in an action sheet: icon, label, and a quiet hint under it. */
@Composable
private fun ActionRow(icon: Int, label: String, hint: String?, danger: Boolean = false, onClick: () -> Unit) {
    Tap(onClick, Modifier.fillMaxWidth(), RoundedCornerShape(9.dp)) {
        Row(Modifier.padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Lc(icon, tint = if (danger) Ink.danger else Ink.red, size = 22.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = Type.label.copy(fontWeight = FontWeight.SemiBold, color = if (danger) Ink.danger else Ink.text))
                if (hint != null) Text(hint, style = Type.meta.copy(fontSize = 12.sp))
            }
        }
    }
}

/** Opens a file with whichever app the person picks; videos too, as Grabber has no player of its own. */
private fun openFile(context: Context, uri: Uri, e: Entry) {
    val mime = e.mime?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
        ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext(e.name))
        ?: "*/*"
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val none = "No app here opens ${ext(e.name).ifEmpty { "this" }.uppercase()}."
    if (context.packageManager.queryIntentActivities(view, 0).isEmpty()) {
        android.widget.Toast.makeText(context, none, android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    runCatching { context.startActivity(Intent.createChooser(view, e.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { android.widget.Toast.makeText(context, none, android.widget.Toast.LENGTH_SHORT).show() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileCard(e: Entry, resting: Boolean, quiet: Boolean, onOpen: () -> Unit, onMore: () -> Unit, actions: List<Pair<String, () -> Unit>>) {
    val shape = RoundedCornerShape(14.dp)
    val x = ext(e.name)
    Tap(
        onOpen,
        Modifier.fillMaxWidth().alpha(if (resting) 0.4f else 1f).semantics {
            if (!quiet) {
                onLongClick(label = "Options") { onMore(); true }
                customActions = actions.map { (label, run) -> CustomAccessibilityAction(label) { run(); true } }
            }
        },
        shape, onLongClick = if (quiet) null else onMore,
    ) {
        Row(
            Modifier.clip(shape).background(Ink.card).grain().padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                Lc(
                    when {
                        e.folder && e.name == "YouTube" -> L.drawable.lucide_ic_youtube
                        e.folder && e.name == "Browser" -> L.drawable.lucide_ic_globe
                        e.folder -> L.drawable.lucide_ic_folder
                        x in VIDEO -> L.drawable.lucide_ic_film
                        x in AUDIO -> L.drawable.lucide_ic_music
                        x in IMAGE -> L.drawable.lucide_ic_image
                        else -> L.drawable.lucide_ic_file_text
                    },
                    tint = Ink.red, size = 26.dp,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name, Modifier.basicMarquee(), style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                Text(
                    if (e.folder) (if (e.count == 1) "1 item" else "${e.count} items")
                    else listOf(Fmt.bytes(e.size), age(e.modified)).filter { it.isNotBlank() }.joinToString("  ·  "),
                    Modifier.padding(top = 2.dp), style = Type.meta.copy(fontSize = 12.sp), maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            Lc(
                when {
                    e.folder -> L.drawable.lucide_ic_chevron_right
                    x in VIDEO -> L.drawable.lucide_ic_play
                    else -> L.drawable.lucide_ic_external_link
                },
                tint = Ink.muted, size = 20.dp,
            )
            if (!quiet) Tap(onMore, Modifier.size(40.dp).semantics { contentDescription = "Options for ${e.name}" }, CircleShape) {
                Box(contentAlignment = Alignment.Center) { Lc(L.drawable.lucide_ic_ellipsis_vertical, tint = Ink.muted, size = 20.dp) }
            } else Spacer(Modifier.width(12.dp))
        }
    }
}

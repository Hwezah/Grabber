package com.hwezah.grabber

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as L
import com.hwezah.grabber.design.BottomSheet
import com.hwezah.grabber.design.Button
import com.hwezah.grabber.design.CARD
import com.hwezah.grabber.design.Ink
import com.hwezah.grabber.design.Lc
import com.hwezah.grabber.design.Note
import com.hwezah.grabber.design.Tap
import com.hwezah.grabber.design.Type
import com.hwezah.grabber.design.solo
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * The Bin: deleted files and folders kept for 30 days in a hidden ".Bin" folder inside the download folder (a move,
 * so instant and no extra space), each in a folder of its own so names never clash. Restore puts it back where it
 * was; anything older than 30 days is erased for good.
 */
object Bin {
    private const val DAYS = 30
    private const val DIR = ".Bin"

    data class Binned(val holder: String, val name: String, val from: String, val at: Long, val size: Long, val folder: Boolean)

    /** Bumped when the Bin changes, so screens read it again. */
    var version by mutableIntStateOf(0)
        private set

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("bin", Context.MODE_PRIVATE)

    fun list(c: Context): List<Binned> = runCatching {
        val a = JSONArray(sp(c).getString("items", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Binned(it.getString("holder"), it.getString("name"), it.getString("from"), it.getLong("at"), it.optLong("size", -1), it.optBoolean("folder"))
        }.sortedByDescending { it.at }
    }.getOrDefault(emptyList())

    private fun save(c: Context, items: List<Binned>) {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("holder", it.holder).put("name", it.name).put("from", it.from).put("at", it.at).put("size", it.size).put("folder", it.folder)) }
        sp(c).edit().putString("items", a.toString()).apply()
        Handler(Looper.getMainLooper()).post { version++ }
    }

    private fun tree(): Uri? = Prefs.folder?.let { Uri.parse(it) }
    private fun uri(tree: Uri, id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    /** The Bin folder's id, made if it isn't there. */
    private fun binId(c: Context, tree: Uri): String? = runCatching {
        val root = DocumentsContract.getTreeDocumentId(tree)
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, root)
        c.contentResolver.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { q ->
            while (q.moveToNext()) if (q.getString(1) == DIR) return@runCatching q.getString(0)
        }
        DocumentsContract.createDocument(c.contentResolver, uri(tree, root), DocumentsContract.Document.MIME_TYPE_DIR, DIR)?.let { DocumentsContract.getDocumentId(it) }
    }.getOrNull()

    /** Moves the document [id] (a file or folder in the download folder) into the Bin. Blocking. True when it went. */
    fun put(c: Context, id: String, name: String, size: Long, folder: Boolean): Boolean {
        val tree = tree() ?: return false
        val from = id.substringBeforeLast('/', "").ifEmpty { return false }
        return runCatching {
            val bin = binId(c, tree) ?: return false
            val holder = DocumentsContract.createDocument(c.contentResolver, uri(tree, bin), DocumentsContract.Document.MIME_TYPE_DIR, System.currentTimeMillis().toString()) ?: return false
            val holderId = DocumentsContract.getDocumentId(holder)
            if (DocumentsContract.moveDocument(c.contentResolver, uri(tree, id), uri(tree, from), holder) == null) {
                DocumentsContract.deleteDocument(c.contentResolver, holder)
                return false
            }
            save(c, list(c) + Binned(holderId, name, from, System.currentTimeMillis(), size, folder))
            true
        }.getOrDefault(false)
    }

    /** Back where it was (or the top of the download folder if that's gone), under a free name. Blocking. */
    fun restore(c: Context, b: Binned): Boolean {
        val tree = tree() ?: return false
        return runCatching {
            val inside = childId(c, tree, b.holder) ?: return false
            val root = DocumentsContract.getTreeDocumentId(tree)
            val to = if (exists(c, tree, b.from)) b.from else root
            val name = freeName(c, tree, to, b.name, b.folder)
            var cur = uri(tree, inside)
            if (name != b.name) cur = DocumentsContract.renameDocument(c.contentResolver, cur, name) ?: cur
            DocumentsContract.moveDocument(c.contentResolver, cur, uri(tree, b.holder), uri(tree, to)) ?: return false
            runCatching { DocumentsContract.deleteDocument(c.contentResolver, uri(tree, b.holder)) }
            save(c, list(c).filterNot { it.holder == b.holder })
            true
        }.getOrDefault(false)
    }

    /** Erased for good. Blocking. */
    fun erase(c: Context, b: Binned) {
        tree()?.let { t -> runCatching { DocumentsContract.deleteDocument(c.contentResolver, uri(t, b.holder)) } }
        save(c, list(c).filterNot { it.holder == b.holder })
    }

    fun empty(c: Context) = list(c).forEach { erase(c, it) }

    /** Anything in the Bin over 30 days. */
    fun purge(c: Context) {
        val cut = System.currentTimeMillis() - DAYS * 24L * 3600 * 1000
        list(c).filter { it.at < cut }.forEach { erase(c, it) }
    }

    fun daysLeft(b: Binned): Int = (DAYS - ((System.currentTimeMillis() - b.at) / (24L * 3600 * 1000)).toInt()).coerceAtLeast(0)

    private fun exists(c: Context, tree: Uri, id: String) = runCatching {
        c.contentResolver.query(uri(tree, id), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } ?: false
    }.getOrDefault(false)

    /** [name], or "name (2).ext"… whichever isn't taken in [dir]. */
    private fun freeName(c: Context, tree: Uri, dir: String, name: String, folder: Boolean): String {
        val taken = HashSet<String>()
        runCatching {
            c.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { q ->
                while (q.moveToNext()) q.getString(0)?.let { taken += it.lowercase() }
            }
        }
        if (name.lowercase() !in taken) return name
        val e = if (folder) "" else name.substringAfterLast('.', "").let { if (it.isEmpty() || it.length > 5) "" else ".$it" }
        val base = name.removeSuffix(e)
        var n = 2
        while ("$base ($n)$e".lowercase() in taken) n++
        return "$base ($n)$e"
    }

    private fun childId(c: Context, tree: Uri, parent: String): String? = runCatching {
        c.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { q ->
            if (q.moveToFirst()) q.getString(0) else null
        }
    }.getOrNull()
}

/**
 * A delete held for a few seconds with Undo before it happens (Move to Bin and Delete permanently alike). Only one at a
 * time: a new one does the waiting one straight away.
 */
object Hold {
    const val SECONDS = 5

    class Pending(val label: String, val ids: Set<String>, val run: () -> Unit, val undo: () -> Unit)

    var pending by mutableStateOf<Pending?>(null)
        private set
    /** Bumped on each new hold, so the countdown starts over. */
    var tick by mutableIntStateOf(0)
        private set

    /** Documents waiting to go: the Files list leaves them out meanwhile. */
    val hidden: Set<String> get() = pending?.ids.orEmpty()

    fun start(label: String, ids: Set<String> = emptySet(), undo: () -> Unit = {}, run: () -> Unit) {
        commit()
        pending = Pending(label, ids, run, undo)
        tick++
    }

    /** Does the waiting delete now (its time is up, or another one came). */
    fun commit() {
        val p = pending ?: return
        pending = null
        thread(name = "grabber-delete") {
            runCatching { p.run() }
            Handler(Looper.getMainLooper()).post { done++ }
        }
    }

    /** Bumped when a held delete has gone through, so lists read their folders again. */
    var done by mutableIntStateOf(0)
        private set

    fun undo() {
        val p = pending ?: return
        pending = null
        p.undo()
    }
}

/** The Undo strip at the foot of the screen while a delete is held, counting down. */
@Composable
fun HoldBar(modifier: Modifier = Modifier) {
    val p = Hold.pending
    var left by remember { mutableIntStateOf(Hold.SECONDS) }
    LaunchedEffect(Hold.tick) {
        if (Hold.pending == null) return@LaunchedEffect
        for (s in Hold.SECONDS downTo 1) { left = s; kotlinx.coroutines.delay(1000) }
        Hold.commit()
    }
    AnimatedVisibility(p != null, modifier, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        val shown = p ?: return@AnimatedVisibility
        Row(
            Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp).fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)).background(Ink.sheet).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(shown.label, Modifier.weight(1f), style = Type.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${left}s", Modifier.padding(horizontal = 8.dp), style = Type.meta)
            Button("Undo", { Hold.undo() }, accent = Ink.green, compact = true)
        }
    }
}

/** The Bin: what's in it, each restorable or erased; Empty Bin erases everything. */
@Composable
fun BinSheet(onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val items = remember(Bin.version) { Bin.list(context) }
    val main = remember { Handler(Looper.getMainLooper()) }
    fun say(t: String) = android.widget.Toast.makeText(context, t, android.widget.Toast.LENGTH_SHORT).show()
    BottomSheet("Bin", onDismiss, icon = L.drawable.lucide_ic_trash_2) {
        Column(Modifier.padding(horizontal = CARD)) {
            Text("Deleted files stay here for 30 days, then they're erased.", style = Type.meta)
            Spacer(Modifier.height(8.dp))
            if (items.isEmpty()) Note("The Bin is empty.", Modifier.padding(vertical = 18.dp))
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                items.forEach { b ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Lc(if (b.folder) L.drawable.lucide_ic_folder else L.drawable.lucide_ic_file, tint = Ink.muted, size = 20.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.name, style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(b.size.takeIf { it > 0 }?.let { Fmt.bytes(it) }, Bin.daysLeft(b).let { if (it == 1) "1 day left" else "$it days left" }).joinToString(" · "), style = Type.meta)
                        }
                        Tap({
                            thread(name = "grabber-restore") {
                                val ok = Bin.restore(context, b)
                                main.post { say(if (ok) "Restored ${b.name}" else "Couldn't restore ${b.name}"); onChanged() }
                            }
                        }, Modifier.size(44.dp), CircleShape) { Box(contentAlignment = Alignment.Center) { Lc(L.drawable.lucide_ic_rotate_ccw, tint = Ink.green, size = 22.dp) } }
                        Tap({
                            Hold.start("Erasing ${b.name} for good") { Bin.erase(context, b) }
                        }, Modifier.size(44.dp), CircleShape) { Box(contentAlignment = Alignment.Center) { Lc(L.drawable.lucide_ic_x, tint = Ink.muted, size = 22.dp) } }
                    }
                }
            }
            if (items.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                com.hwezah.grabber.design.SheetRow(L.drawable.lucide_ic_trash_2, "Empty Bin", { Hold.start("Emptying the Bin (${items.size})") { Bin.empty(context) }; onDismiss() }, hint = "Erase everything in it for good", danger = true)
            }
        }
    }
}

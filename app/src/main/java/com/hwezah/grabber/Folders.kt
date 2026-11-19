package com.hwezah.grabber

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.hwezah.grabber.engine.Category

/** The download folder the user chose (Storage Access Framework), with one subfolder per category. */
object Folders {
    /** A new empty file in [category]'s subfolder, renamed "name (2).ext" if the name is taken. Null without a folder. */
    fun create(context: Context, tree: String, category: Category, name: String, part: Boolean = false): Uri? {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: return null
        if (!root.canWrite()) return null
        val dir = root.findFile(category.folder)?.takeIf { it.isDirectory } ?: root.createDirectory(category.folder) ?: return null
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        // A name is taken by a finished file or by one still downloading (".part").
        val taken = dir.listFiles().mapNotNull { it.name?.removeSuffix(".part") }.toHashSet()
        var candidate = name
        var n = 2
        while (candidate in taken) candidate = "$base ($n)$ext".also { n++ }
        // octet-stream so the provider keeps our name and extension as they are.
        return dir.createFile("application/octet-stream", if (part) "$candidate.part" else candidate)?.uri
    }

    /** While downloading a file is "name.ext.part"; done, it gets its real name. Returns (document, name). */
    fun finish(context: Context, doc: String, fallback: String): Pair<String, String> {
        val uri = Uri.parse(doc)
        val cur = runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull() ?: return doc to fallback
        if (!cur.endsWith(".part")) return doc to cur
        val renamed = runCatching { DocumentsContract.renameDocument(context.contentResolver, uri, cur.removeSuffix(".part")) }.getOrNull() ?: uri
        val name = runCatching { DocumentFile.fromSingleUri(context, renamed)?.name }.getOrNull() ?: cur.removeSuffix(".part")
        // An episode goes on into Video/Shows/<Show>/Season N.
        val shelved = Prefs.folder?.let { shelve(context, it, renamed, name) } ?: renamed
        return shelved.toString() to name
    }

    /** "Dexter_ Resurrection · S01E05.mkv" → ("Dexter - Resurrection", 1). Null when it isn't an episode. */
    fun episode(name: String): Pair<String, Int>? {
        val base = name.substringBeforeLast('.', name)
        val m = Regex("""^(.+?)[\s._·\-]*[Ss](\d{1,2})[\s._-]*[Ee]\d{1,3}""").find(base) ?: return null
        val show = m.groupValues[1]
            .replace("_ ", " - ").replace('_', ' ').replace('.', ' ')
            .replace(Regex("""\s+"""), " ").trim().trimEnd('·', '-', ' ').trim()
        if (show.isEmpty()) return null
        return show to m.groupValues[2].toInt()
    }

    /** A child folder named [name], made if missing. */
    private fun dir(parent: DocumentFile, name: String): DocumentFile? =
        parent.findFile(name)?.takeIf { it.isDirectory } ?: parent.createDirectory(name)

    /**
     * Moves an episode sitting in the Video folder into Video/Shows/<Show>/Season N. Returns its new address, or
     * [doc] unchanged when it isn't an episode, isn't in Video, or can't be moved.
     */
    fun shelve(context: Context, tree: String, doc: Uri, name: String): Uri {
        val (show, season) = episode(name) ?: return doc
        return runCatching {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: return doc
            val video = root.findFile(Category.Video.folder)?.takeIf { it.isDirectory } ?: return doc
            // Only files sitting straight in Video/ (not already sorted, not elsewhere).
            val id = DocumentsContract.getDocumentId(doc)
            if (id.substringBeforeLast('/') != DocumentsContract.getDocumentId(video.uri)) return doc
            val safe = show.replace(Regex("""[\\/:*?"<>|]"""), "_").trimEnd('.').ifBlank { "Show" }
            val target = dir(video, "Shows")?.let { dir(it, safe) }?.let { dir(it, "Season $season") } ?: return doc
            if (target.findFile(name) != null) return doc
            DocumentsContract.moveDocument(context.contentResolver, doc, video.uri, target.uri) ?: doc
        }.getOrDefault(doc)
    }

    /** Sorts episodes already in Video/ into their show folders, and points the downloads list at their new places. */
    fun shelveAll(context: Context) {
        val tree = Prefs.folder ?: return
        val root = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: return
        val video = root.findFile(Category.Video.folder)?.takeIf { it.isDirectory } ?: return
        video.listFiles().filter { it.isFile && it.name?.endsWith(".part") == false }.forEach { f ->
            val name = f.name ?: return@forEach
            if (episode(name) == null) return@forEach
            val old = runCatching { DocumentsContract.getDocumentId(f.uri) }.getOrNull() ?: return@forEach
            val moved = shelve(context, tree, f.uri, name)
            if (moved == f.uri) return@forEach
            Downloads.items.value.filter { it.doc?.let { d -> runCatching { DocumentsContract.getDocumentId(Uri.parse(d)) }.getOrNull() } == old }
                .forEach { item -> Downloads.edit(item.id) { it.copy(doc = moved.toString()) } }
        }
    }

    fun delete(context: Context, doc: String) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(doc)) }
    }

    /** Human name of the chosen folder, e.g. "Download/Grabber". */
    fun label(tree: String?): String {
        if (tree == null) return "Not chosen"
        val id = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(tree)) }.getOrNull() ?: return "Chosen"
        return id.substringAfter(':').ifEmpty { "Phone storage" }
    }
}

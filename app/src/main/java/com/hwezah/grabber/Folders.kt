package com.hwezah.grabber

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.hwezah.grabber.engine.Category

/** The download folder the user chose (Storage Access Framework), with one subfolder per category. */
object Folders {
    /** A new empty file in [category]'s subfolder, renamed "name (2).ext" if the name is taken. Null without a folder. */
    fun create(context: Context, tree: String, category: Category, name: String, part: Boolean = false, browser: Boolean = false): Uri? {
        val top = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: return null
        if (!top.canWrite()) return null
        // From Grabber's browser: Browser/Video, Browser/Audio… Anything else unusual joins Browser/Other, so the top stays clean.
        val browser = browser || category == Category.Other
        val root = if (browser) synchronized(this) { top.findFile("Browser")?.takeIf { it.isDirectory } ?: top.createDirectory("Browser") } ?: return null else top
        // One at a time: two downloads making the same folder at once would get "Other" and "Other (1)".
        // The browser keeps five: Video, Audio, Documents, Images, and Other for the rest.
        val folder = if (browser && category !in setOf(Category.Video, Category.Audio, Category.Documents, Category.Images)) "Other" else category.folder
        val dir = synchronized(this) { root.findFile(folder)?.takeIf { it.isDirectory } ?: root.createDirectory(folder) } ?: return null
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
    fun finish(context: Context, doc: String, fallback: String, youtube: Boolean = false): Pair<String, String> {
        val uri = Uri.parse(doc)
        val cur = runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull() ?: return doc to fallback
        if (!cur.endsWith(".part")) return doc to cur
        val renamed = runCatching { DocumentsContract.renameDocument(context.contentResolver, uri, cur.removeSuffix(".part")) }.getOrNull() ?: uri
        val name = runCatching { DocumentFile.fromSingleUri(context, renamed)?.name }.getOrNull() ?: cur.removeSuffix(".part")
        // An episode goes on into Video/Shows/<Show>/Season N.
        val shelved = Prefs.folder?.let { shelve(context, it, renamed, name, youtube, listOf(fallback)) } ?: renamed
        return shelved.toString() to name
    }

    /** "Dexter_ Resurrection · S01E05.mkv", "The Penguin · season 1 · 2" → (show, season). Null when it isn't an episode. */
    fun episode(name: String): Pair<String, Int>? {
        val base = name.substringBeforeLast('.', name).takeIf { name.substringAfterLast('.', "").length in 2..4 } ?: name
        val m = Regex("""^(.+?)[\s._·\-]*[Ss](\d{1,2})[\s._-]*[Ee]\d{1,3}""").find(base)
            ?: Regex("""^(.+?)[\s._·\-]*[Ss]eason[\s._]*(\d{1,2})""", RegexOption.IGNORE_CASE).find(base)
            ?: return null
        // "1923 · S01E04": the show is the title before " · ".
        val show = (if (" · " in base) base.substringBefore(" · ") else m.groupValues[1])
            .replace("_ ", " - ").replace('_', ' ').replace('.', ' ')
            .replace(Regex("""\s+"""), " ").trim().trimEnd('·', '-', ' ').trim()
        if (show.isEmpty()) return null
        return show to m.groupValues[2].toInt()
    }

    /** "Masterplan (2026)" from a name with a year in it; null without one. */
    fun film(name: String): String? {
        val base = name.substringBeforeLast('.', name).takeIf { name.substringAfterLast('.', "").length in 2..4 } ?: name
        val y = Regex("""(?:^|[\s._(\[])((?:19|20)\d{2})(?:[\s._)\]]|$)""").find(base) ?: return null
        val title = base.substring(0, y.range.first).replace('_', ' ').replace('.', ' ').replace(Regex("""\s+"""), " ").trim().trimEnd('(', '[', '-', '·', ' ')
        return if (title.isBlank()) y.groupValues[1] else "$title (${y.groupValues[1]})"
    }

    /** Safe for a file or folder name: each run of \\ / : * ? " < > | becomes _, trimmed, no trailing dots. */
    fun safe(n: String) = n.replace(Regex("""[\\/:*?"<>|]+"""), "_").trim().trimEnd('.').trim().ifBlank { "Download" }

    /** [name], or "name (2).ext", "name (3).ext"… whichever isn't in [dir] yet (ignoring case). A folder keeps its whole name. */
    fun freeName(dir: DocumentFile, name: String, folder: Boolean = false): String {
        val taken = dir.listFiles().mapNotNull { it.name?.lowercase() }.toHashSet()
        if (name.lowercase() !in taken) return name
        val ext = if (folder) "" else name.substringAfterLast('.', "").let { if (it.isEmpty() || it.length > 5) "" else ".$it" }
        val base = name.removeSuffix(ext)
        var n = 2
        while ("$base ($n)$ext".lowercase() in taken) n++
        return "$base ($n)$ext"
    }

    /** A child folder named [name], made if missing. */
    private fun dir(parent: DocumentFile, name: String): DocumentFile? =
        synchronized(this) { parent.findFile(name)?.takeIf { it.isDirectory } ?: parent.createDirectory(name) }

    /** Why the last sort couldn't move something, for the Files tab to say. */
    @Volatile var lastProblem: String? = null

    /**
     * Moves a video sitting straight in Video/ to where it belongs: Shows/<Show>/Season N for an episode,
     * Movies/<Title (Year)> for a film, YouTube for a video site's download. [labels] are other names it's known by
     * (the downloads list's title), tried before the file's own name. Returns its new address, or [doc] unchanged.
     */
    fun shelve(context: Context, tree: String, doc: Uri, name: String, youtube: Boolean = false, labels: List<String> = emptyList()): Uri {
        val names = (labels + name).filter { it.isNotBlank() }
        val ep = names.firstNotNullOfOrNull { episode(it) }
        val movie = if (ep == null && !youtube) names.firstNotNullOfOrNull { film(it) } else null
        if (ep == null && movie == null && !youtube) return doc
        return try {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: return doc
            val video = root.findFile(Category.Video.folder)?.takeIf { it.isDirectory } ?: return doc
            val id = DocumentsContract.getDocumentId(doc)
            val videoId = DocumentsContract.getDocumentId(video.uri)
            if (id.substringBeforeLast('/') != videoId) return doc
            val target = when {
                ep != null -> dir(video, "Shows")?.let { dir(it, safe(ep.first)) }?.let { dir(it, "Season ${ep.second}") }
                movie != null -> dir(video, "Movies")
                else -> dir(video, "YouTube")
            } ?: run { lastProblem = "couldn't make the folder"; return doc }
            // A clash gets a number: "Film (2).mkv".
            val free = freeName(target, name)
            val src = if (free != name) DocumentsContract.renameDocument(context.contentResolver, doc, free) ?: doc else doc
            DocumentsContract.moveDocument(context.contentResolver, src, video.uri, target.uri) ?: run { lastProblem = "Android refused to move $name"; doc }
        } catch (e: Exception) {
            lastProblem = e.message ?: e.javaClass.simpleName
            doc
        }
    }

    /** The fixed folders, matched by their full path, made up front. */
    private val FIXED = listOf("Video", "Video/Movies", "Video/Shows", "Video/YouTube", "Audio", "Browser", "Browser/Video", "Browser/Audio", "Browser/Documents", "Browser/Images", "Browser/Other")

    /** Whether [path] (from the top, e.g. "Video/Shows") is one of the fixed folders. */
    fun isFixed(path: String) = path in FIXED

    fun ensureLayout(context: Context) {
        val tree = Prefs.folder ?: return
        val root = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: return
        if (!root.canWrite()) return
        FIXED.forEach { p ->
            var d: DocumentFile? = root
            p.split('/').forEach { part -> d = d?.let { dir(it, part) } }
        }
    }

    /** Films that an earlier build put in Movies/<Title (Year)>/ come back up into Movies/, and the empty folders go. */
    fun flattenMovies(context: Context) {
        val tree = Prefs.folder ?: return
        val root = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: return
        val movies = root.findFile("Video")?.findFile("Movies")?.takeIf { it.isDirectory } ?: return
        fun idOf(d: String?) = d?.let { runCatching { DocumentsContract.getDocumentId(Uri.parse(it)) }.getOrNull() }
        movies.listFiles().filter { it.isDirectory }.forEach { sub ->
            sub.listFiles().filter { it.isFile }.forEach { f ->
                val name = f.name ?: return@forEach
                val old = idOf(f.uri.toString())
                runCatching {
                    val free = freeName(movies, name)
                    val src = if (free != name) DocumentsContract.renameDocument(context.contentResolver, f.uri, free) ?: f.uri else f.uri
                    val now = DocumentsContract.moveDocument(context.contentResolver, src, sub.uri, movies.uri) ?: return@runCatching
                    Downloads.items.value.filter { idOf(it.doc) == old }.forEach { item -> Downloads.edit(item.id) { it.copy(doc = now.toString()) } }
                }
            }
            if (sub.listFiles().isEmpty()) sub.delete()
        }
    }

    /**
     * Leftovers from earlier builds at the top: Movies and Shows go into Video/, Other and "Other (1)" into Browser/Other.
     * Files keep their names (numbered on a clash), the downloads list follows, and the emptied folders go.
     */
    fun tidyRoot(context: Context) {
        val tree = Prefs.folder ?: return
        val root = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: return
        if (!root.canWrite()) return
        fun idOf(d: String?) = d?.let { runCatching { DocumentsContract.getDocumentId(Uri.parse(it)) }.getOrNull() }
        // Everything in [from] into [to]; folders of the same name merge.
        fun pour(from: DocumentFile, to: DocumentFile) {
            from.listFiles().forEach { f ->
                val name = f.name ?: return@forEach
                if (f.isDirectory) {
                    val same = to.findFile(name)?.takeIf { it.isDirectory }
                    if (same != null) { pour(f, same); if (f.listFiles().isEmpty()) f.delete(); return@forEach }
                }
                runCatching {
                    val prefix = idOf(f.uri.toString()) ?: return@runCatching
                    val free = freeName(to, name, f.isDirectory)
                    val src = if (free != name) DocumentsContract.renameDocument(context.contentResolver, f.uri, free) ?: f.uri else f.uri
                    val moved = DocumentsContract.moveDocument(context.contentResolver, src, from.uri, to.uri) ?: return@runCatching
                    val now = DocumentsContract.getDocumentId(moved)
                    // A file, or everything inside a moved folder, keeps its place in the list.
                    Downloads.items.value.forEach { item ->
                        val id = idOf(item.doc) ?: return@forEach
                        if (id == prefix || id.startsWith("$prefix/")) {
                            val doc = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree), now + id.removePrefix(prefix))
                            Downloads.edit(item.id) { it.copy(doc = doc.toString()) }
                        }
                    }
                }
            }
        }
        val targets = mapOf("Movies" to "Video/Movies", "Shows" to "Video/Shows")
        root.listFiles().filter { it.isDirectory }.forEach { d ->
            val name = d.name ?: return@forEach
            val path = targets[name] ?: if (name == "Other" || Regex("""^Other \(\d+\)$""").matches(name)) "Browser/Other" else return@forEach
            var to: DocumentFile? = root
            path.split('/').forEach { part -> to = to?.let { dir(it, part) } }
            to?.let { pour(d, it) }
            if (d.listFiles().isEmpty()) d.delete()
        }
    }

    /**
     * Folders made twice by accident ("Other (1)", "Video (2)"): their files go back into the real one, the copy goes.
     * Only for Grabber's own type folders.
     */
    fun mergeDuplicates(context: Context) {
        val tree = Prefs.folder ?: return
        val root = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: return
        val dirs = root.listFiles().filter { it.isDirectory }
        Category.entries.map { it.folder }.forEach { real ->
            val copies = dirs.filter { d -> d.name?.let { Regex("""^${Regex.escape(real)} \(\d+\)$""").matches(it) } == true }
            if (copies.isEmpty()) return@forEach
            val main = synchronized(this) { root.findFile(real)?.takeIf { it.isDirectory } ?: root.createDirectory(real) } ?: return@forEach
            copies.forEach { copy ->
                copy.listFiles().forEach { f ->
                    val name = f.name ?: return@forEach
                    if (main.findFile(name) != null) return@forEach
                    val old = runCatching { DocumentsContract.getDocumentId(f.uri) }.getOrNull()
                    val moved = runCatching { DocumentsContract.moveDocument(context.contentResolver, f.uri, copy.uri, main.uri) }.getOrNull() ?: return@forEach
                    Downloads.items.value.filter { it.doc?.let { d -> runCatching { DocumentsContract.getDocumentId(Uri.parse(d)) }.getOrNull() } == old }
                        .forEach { item -> Downloads.edit(item.id) { it.copy(doc = moved.toString()) } }
                }
                if (copy.listFiles().isEmpty()) copy.delete()
            }
        }
    }

    /**
     * Sorts every finished video still sitting straight in Video/ (from the list, by its title, and any other file
     * there, by its name), and points the downloads list at the new places. Returns how many moved.
     */
    fun shelveAll(context: Context): Int {
        val tree = Prefs.folder ?: return 0
        val root = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: return 0
        val video = root.findFile(Category.Video.folder)?.takeIf { it.isDirectory } ?: return 0
        lastProblem = null
        var moved = 0
        fun idOf(d: String?) = d?.let { runCatching { DocumentsContract.getDocumentId(Uri.parse(it)) }.getOrNull() }
        video.listFiles().filter { it.isFile && it.name?.endsWith(".part") == false }.forEach { f ->
            val name = f.name ?: return@forEach
            val old = idOf(f.uri.toString()) ?: return@forEach
            val owners = Downloads.items.value.filter { !it.browser && idOf(it.doc) == old }
            val labels = owners.flatMap { listOfNotNull(it.fixedName, it.name, it.hint) }
            val youtube = owners.any { it.kind == Kind.Media && Media.looksLikeSite(it.url) }
            val now = shelve(context, tree, f.uri, name, youtube, labels)
            if (now == f.uri) return@forEach
            moved++
            owners.forEach { item -> Downloads.edit(item.id) { it.copy(doc = now.toString()) } }
        }
        return moved
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

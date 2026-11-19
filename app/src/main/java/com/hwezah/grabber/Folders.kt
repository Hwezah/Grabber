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
        // Only Video and Audio live at the top; everything else joins Browser/Documents, Images or Other.
        val browser = browser || category !in setOf(Category.Video, Category.Audio)
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
        // Inside Grabber!/Video these would make Video/Video…: wait for the right folder.
        if (wrongRoot(tree)) return
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
        if (wrongRoot(tree)) return
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

    /** Names a save folder must not be: picking Grabber!/Video as the root makes Video/Video, Movies/Movies… */
    private val INNER = setOf("Video", "Movies", "Shows", "YouTube", "Audio", "Browser", "Documents", "Images", "Other", "Archives", "Torrents")

    /** Whether the chosen folder is one of Grabber's own inner folders instead of Download/Grabber! itself. */
    fun wrongRoot(tree: String?): Boolean {
        val id = tree?.let { runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(it)) }.getOrNull() } ?: return false
        return id.substringAfter(':').substringAfterLast('/') in INNER
    }

    /** Where the folder picker should open: the real Grabber! folder above a wrong pick, else the current one. */
    fun suggestedRoot(tree: String?): Uri? {
        val t = tree?.let { Uri.parse(it) } ?: return null
        var id = runCatching { DocumentsContract.getTreeDocumentId(t) }.getOrNull() ?: return null
        while (id.substringAfter(':').substringAfterLast('/') in INNER && id.contains('/')) id = id.substringBeforeLast('/')
        return runCatching { DocumentsContract.buildDocumentUri(t.authority, id) }.getOrNull()
    }

    /** Everything in [from] into [to]; folders of the same name merge; a clashing name is numbered. The list follows. */
    private fun pour(context: Context, tree: String, from: DocumentFile, to: DocumentFile): Int {
        fun idOf(d: String?) = d?.let { runCatching { DocumentsContract.getDocumentId(Uri.parse(it)) }.getOrNull() }
        var moved = 0
        from.listFiles().forEach { f ->
            val name = f.name ?: return@forEach
            if (f.isDirectory) {
                val same = to.listFiles().firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) }
                if (same != null) { moved += pour(context, tree, f, same); if (f.listFiles().isEmpty()) f.delete(); return@forEach }
            }
            runCatching {
                val prefix = idOf(f.uri.toString()) ?: return@runCatching
                val free = freeName(to, name, f.isDirectory)
                val src = if (free != name) DocumentsContract.renameDocument(context.contentResolver, f.uri, free) ?: f.uri else f.uri
                val now = DocumentsContract.getDocumentId(DocumentsContract.moveDocument(context.contentResolver, src, from.uri, to.uri) ?: return@runCatching)
                moved++
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
        return moved
    }

    private fun at(root: DocumentFile, path: String): DocumentFile? {
        var d: DocumentFile? = root
        if (path.isNotEmpty()) path.split('/').forEach { part -> d = d?.let { dir(it, part) } }
        return d
    }

    /**
     * Puts the tree in the shared layout (the same as WatchHub's), from Download/Grabber! itself:
     * a folder named after a fixed one at the wrong level (Video/Video, Movies/Movies, Grabber!/Grabber!) is merged
     * into the real one; old top-level Movies, Shows and YouTube go into Video/; old top-level Documents, Images,
     * Other, Archives and Torrents into Browser/; "Season 01" becomes "Season 1"; browser files loose in the top
     * Video/Audio go to Browser/Video, Browser/Audio. Emptied leftovers go; fixed folders stay.
     */
    fun tidyRoot(context: Context) {
        val tree = Prefs.folder ?: return
        if (wrongRoot(tree)) return
        val root = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: return
        if (!root.canWrite()) return
        val home = mapOf("Video" to "Video", "Movies" to "Video/Movies", "Shows" to "Video/Shows", "YouTube" to "Video/YouTube", "Audio" to "Audio", "Browser" to "Browser")
        val legacyTop = mapOf("Documents" to "Browser/Documents", "Images" to "Browser/Images", "Other" to "Browser/Other", "Archives" to "Browser/Other", "Torrents" to "Browser/Other")
        val rootName = root.name
        fun walk(d: DocumentFile, path: String): Int {
            var moved = 0
            d.listFiles().filter { it.isDirectory }.forEach { sub ->
                val name = sub.name ?: return@forEach
                val subPath = if (path.isEmpty()) name else "$path/$name"
                when {
                    isFixed(subPath) -> if (subPath != "Video/Shows") moved += walk(sub, subPath) else sub.listFiles().filter { it.isDirectory }.forEach { show ->
                        // Inside a show: "Season 01" → "Season 1", merged if both exist.
                        show.listFiles().filter { it.isDirectory }.forEach { season ->
                            val m = Regex("""^Season 0+(\d+)$""", RegexOption.IGNORE_CASE).find(season.name.orEmpty()) ?: return@forEach
                            val want = "Season ${m.groupValues[1]}"
                            val there = show.listFiles().firstOrNull { it.isDirectory && it.name == want }
                            if (there != null) { moved += pour(context, tree, season, there); if (season.listFiles().isEmpty()) season.delete() }
                            else runCatching { DocumentsContract.renameDocument(context.contentResolver, season.uri, want) }
                        }
                    }
                    name == "Grabber!" || name == rootName -> { moved += pour(context, tree, sub, root); if (sub.listFiles().isEmpty()) sub.delete() }
                    name in home -> at(root, home.getValue(name))?.let { moved += pour(context, tree, sub, it); if (sub.listFiles().isEmpty()) sub.delete() }
                    path.isEmpty() && (name in legacyTop || Regex("""^Other \(\d+\)$""").matches(name)) ->
                        at(root, legacyTop[name] ?: "Browser/Other")?.let { moved += pour(context, tree, sub, it); if (sub.listFiles().isEmpty()) sub.delete() }
                }
            }
            return moved
        }
        // Merging can bring up another nested level; a few rounds settle it.
        for (round in 0 until 3) if (walk(root, "") == 0) break
        // Browser downloads sitting in the top Video or Audio.
        fun idOf(d: String?) = d?.let { runCatching { DocumentsContract.getDocumentId(Uri.parse(it)) }.getOrNull() }
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(tree)) }.getOrNull() ?: return
        listOf("Video", "Audio").forEach { kind ->
            val top = root.findFile(kind)?.takeIf { it.isDirectory } ?: return@forEach
            val ids = Downloads.items.value.filter { it.browser }.mapNotNull { idOf(it.doc) }.toHashSet()
            top.listFiles().filter { it.isFile && idOf(it.uri.toString()) in ids && idOf(it.uri.toString())?.substringBeforeLast('/') == "$rootId/$kind" }.forEach { f ->
                val to = at(root, "Browser/$kind") ?: return@forEach
                runCatching {
                    val name = f.name ?: return@runCatching
                    val old = idOf(f.uri.toString())
                    val free = freeName(to, name)
                    val src = if (free != name) DocumentsContract.renameDocument(context.contentResolver, f.uri, free) ?: f.uri else f.uri
                    val now = DocumentsContract.moveDocument(context.contentResolver, src, top.uri, to.uri) ?: return@runCatching
                    Downloads.items.value.filter { idOf(it.doc) == old }.forEach { item -> Downloads.edit(item.id) { it.copy(doc = now.toString()) } }
                }
            }
        }
    }

    /**
     * Folders made twice by accident ("Other (1)", "Video (2)"): their files go back into the real one, the copy goes.
     * Only for Grabber's own type folders.
     */
    fun mergeDuplicates(context: Context) {
        val tree = Prefs.folder ?: return
        if (wrongRoot(tree)) return
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

    /** All the tidying, in order; returns how many videos were sorted. */
    fun housekeep(context: Context): Int {
        runCatching { mergeDuplicates(context) }
        runCatching { ensureLayout(context) }
        runCatching { tidyRoot(context) }
        runCatching { flattenMovies(context) }
        return runCatching { shelveAll(context) }.getOrDefault(0)
    }

    /**
     * Sorts every finished video still sitting straight in Video/ (from the list, by its title, and any other file
     * there, by its name), and points the downloads list at the new places. Returns how many moved.
     */
    fun shelveAll(context: Context): Int {
        val tree = Prefs.folder ?: return 0
        if (wrongRoot(tree)) return 0
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

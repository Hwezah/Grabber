package com.hwezah.grabber

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.delay
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.Vectors
import org.libtorrent4j.swig.add_torrent_params
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.settings_pack
import java.io.File

data class TorrentFile(val index: Int, val path: String, val size: Long)

data class TorrentMeta(val hash: String, val name: String, val files: List<TorrentFile>) {
    val size get() = files.sumOf { it.size }
}

/**
 * Torrents and magnets through libtorrent. Files download into the app's own storage (libtorrent needs
 * real paths), then move into the chosen folder's Torrents subfolder when done. No seeding after.
 */
object Torrents {
    private const val BOOTSTRAP = "router.bittorrent.com:6881,router.utorrent.com:6881,dht.transmissionbt.com:6881,dht.libtorrent.org:25401"
    private val session = SessionManager()

    private fun base(c: Context) = File(c.getExternalFilesDir(null) ?: c.filesDir, "torrents")
    private fun metaFile(c: Context, hash: String) = File(c.filesDir, "meta/$hash.torrent")
    private fun saveDir(c: Context, hash: String) = File(base(c), hash)

    @Synchronized
    fun ensure() {
        if (session.isRunning) return
        val s = SettingsPack()
            .connectionsLimit(200)
            .activeDhtLimit(88)
            .setString(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), BOOTSTRAP)
            .setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
            .setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
            .setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
            .setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
            .setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
            .setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
        session.start(SessionParams(s))
        session.startDht()
    }

    fun hashOf(magnet: String): String? = runCatching {
        val ec = error_code()
        val p = libtorrent.parse_magnet_uri(magnet, ec)
        if (ec.value() != 0) null else p.getInfo_hashes().get_best().to_hex()
    }.getOrNull()

    private fun find(hash: String): TorrentHandle? =
        runCatching { session.find(Sha1Hash.parseHex(hash)) }.getOrNull()?.takeIf { it.isValid }

    private fun metaOf(ti: TorrentInfo, hash: String): TorrentMeta {
        val fs = ti.files()
        return TorrentMeta(hash, ti.name(), (0 until fs.numFiles()).map { TorrentFile(it, fs.filePath(it), fs.fileSize(it)) })
    }

    /** Fetches the file list for a magnet from peers (can take a while), and keeps it for the download. */
    suspend fun metadata(context: Context, magnet: String, timeoutSec: Int = 120): TorrentMeta {
        val hash = hashOf(magnet) ?: error("That magnet link can't be read")
        metaFile(context, hash).takeIf { it.exists() }?.let { return metaOf(TorrentInfo(it), hash) }
        ensure()
        if (find(hash) == null) session.download(magnet, saveDir(context, hash), TorrentFlags.UPLOAD_MODE)
        repeat(timeoutSec * 2) {
            val h = find(hash)
            if (h != null && h.status().hasMetadata()) {
                val ti = h.torrentFile()
                if (ti != null && ti.isValid) {
                    save(context, hash, ti)
                    runCatching { session.remove(h) }
                    return metaOf(ti, hash)
                }
            }
            delay(500)
        }
        find(hash)?.let { runCatching { session.remove(it) } }
        error("No peers sent the file list yet. Try again in a bit.")
    }

    private fun save(context: Context, hash: String, ti: TorrentInfo) {
        val bytes = runCatching {
            val params = AddTorrentParams(add_torrent_params())
            params.setTorrentInfo(ti)
            Vectors.byte_vector2bytes(libtorrent.write_torrent_file_buf_ex(params.swig()))
        }.getOrNull() ?: return
        metaFile(context, hash).apply { parentFile?.mkdirs() }.writeBytes(bytes)
    }

    class Progress(val done: Long, val total: Long, val rate: Long, val peers: Int)

    /**
     * Downloads [item]'s chosen files, calling [onProgress] each second, until they are all there;
     * then moves them into the download folder and returns the moved folder's (or file's) URI.
     */
    suspend fun run(context: Context, item: Item, onProgress: (Progress) -> Unit): String {
        val hash = hashOf(item.url) ?: error("That magnet link can't be read")
        val meta = metaFile(context, hash)
        if (!meta.exists()) metadata(context, item.url)
        ensure()
        val ti = TorrentInfo(meta)
        val wanted = item.files.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
        val prio = Array(ti.files().numFiles()) { if (wanted.isEmpty() || it in wanted) Priority.DEFAULT else Priority.IGNORE }
        val dir = saveDir(context, hash)
        val running = find(hash)
        val h: TorrentHandle = if (running == null) {
            session.download(ti, dir, null, prio, null, if (item.sequential) TorrentFlags.SEQUENTIAL_DOWNLOAD else TorrentFlags.AUTO_MANAGED)
            repeat(40) { if (find(hash) == null) delay(250) }
            find(hash) ?: error("libtorrent didn't start the torrent")
        } else {
            running.prioritizeFiles(prio)
            running.resume()
            running
        }
        try {
            while (true) {
                val st = h.status()
                onProgress(Progress(st.totalWantedDone(), st.totalWanted(), st.downloadRate().toLong(), st.numPeers()))
                if (st.totalWanted() > 0 && st.totalWantedDone() >= st.totalWanted()) break
                delay(1000)
            }
        } finally {
            // Paused, deleted or done: stop sharing. Files stay; adding it again checks what's there and carries on.
            runCatching { session.remove(h) }
        }
        // Let libtorrent finish writing and close the files.
        delay(1500)
        return export(context, ti, prio, dir)
    }

    /** Copies the finished files into Torrents/ in the chosen folder, keeping their subfolders, then frees the space. */
    private fun export(context: Context, ti: TorrentInfo, prio: Array<Priority>, dir: File): String {
        val tree = Prefs.folder ?: error("Choose a download folder in Settings")
        val root = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: error("Can't open the download folder")
        val cat = root.findFile("Torrents")?.takeIf { it.isDirectory } ?: root.createDirectory("Torrents") ?: error("Can't write to the download folder")
        val fs = ti.files()
        var first: Uri? = null
        var top: Uri? = null
        (0 until fs.numFiles()).filter { prio[it] != Priority.IGNORE }.forEach { i ->
            val path = fs.filePath(i).replace('\\', '/')
            val parts = path.split('/').filter { it.isNotEmpty() }
            var d = cat
            parts.dropLast(1).forEachIndexed { n, p ->
                d = d.findFile(p)?.takeIf { it.isDirectory } ?: d.createDirectory(p) ?: error("Can't make folder $p")
                if (n == 0 && top == null) top = d.uri
            }
            val name = parts.last()
            d.findFile(name)?.delete()
            val out = d.createFile("application/octet-stream", name) ?: error("Can't create $name")
            context.contentResolver.openOutputStream(out.uri)?.use { o -> File(dir, path).inputStream().use { it.copyTo(o, 256 * 1024) } }
            if (first == null) first = out.uri
        }
        dir.deleteRecursively()
        return (if (fs.numFiles() == 1 || top == null) first else top)?.toString() ?: error("Nothing was downloaded")
    }

    /** Deleted from the list: drop its partial files and saved metadata. */
    fun forget(context: Context, item: Item) {
        val hash = hashOf(item.url) ?: return
        find(hash)?.let { runCatching { session.remove(it) } }
        saveDir(context, hash).deleteRecursively()
        metaFile(context, hash).delete()
    }
}

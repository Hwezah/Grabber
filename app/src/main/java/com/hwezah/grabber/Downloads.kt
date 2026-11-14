package com.hwezah.grabber

import android.content.Context
import androidx.core.content.ContextCompat
import com.hwezah.grabber.engine.Category
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class State { Queued, Running, Paused, Done, Failed }

enum class Kind { Http, Hls, Torrent, Media }

data class Item(
    val id: Long,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val name: String,
    val mime: String? = null,
    val category: Category = Category.Other,
    /** -1 unknown; -2 not asked yet. */
    val size: Long = -2,
    val resumable: Boolean = false,
    val doc: String? = null,
    val state: State = State.Queued,
    val error: String? = null,
    val segments: String = "",
    val done: Long = 0,
    val created: Long = System.currentTimeMillis(),
    val finished: Long = 0,
    /** A name the user typed; otherwise the server's is used. */
    val fixedName: String? = null,
    val kind: Kind = Kind.Http,
    /** Torrent: the file indexes chosen, "1,4,5"; empty means all. */
    val files: String = "",
    /** Torrent: pieces in order (so a video can play while it downloads). */
    val sequential: Boolean = false,
    /** Media (yt-dlp): "v:1080" for video up to 1080p, "a:mp3" / "a:m4a" / "a:opus" for audio. */
    val format: String = "",
) {
    val progress get() = if (size > 0) (done.toFloat() / size).coerceIn(0f, 1f) else 0f
}

/** Live numbers for running downloads, refreshed every second; not saved. */
data class Live(val bytes: Long, val speed: Long, val text: String? = null)

/** Every download, kept in a JSON file in the app's private storage. */
object Downloads {
    private lateinit var file: File
    private lateinit var ctx: Context
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()
    val live = MutableStateFlow<Map<Long, Live>>(emptyMap())

    fun init(context: Context) {
        if (::file.isInitialized) return
        ctx = context.applicationContext
        file = File(ctx.filesDir, "downloads.json")
        _items.value = runCatching { parse(file.readText()) }.getOrDefault(emptyList())
            // Anything that was running when the app died goes back in the queue.
            .map { if (it.state == State.Running) it.copy(state = State.Queued) else it }
    }

    fun add(
        urls: List<String>, headers: Map<String, String> = emptyMap(), name: String? = null,
        kind: Kind = Kind.Http, files: String = "", sequential: Boolean = false, format: String = "",
    ) {
        val base = System.currentTimeMillis()
        _items.update { list ->
            urls.mapIndexed { i, u ->
                Item(id = base + i, url = u, headers = headers, fixedName = name?.takeIf { urls.size == 1 && it.isNotBlank() },
                    kind = kind, files = files, sequential = sequential, format = format,
                    category = when {
                        kind == Kind.Torrent -> Category.Torrents
                        kind == Kind.Hls -> Category.Video
                        kind == Kind.Media -> if (format.startsWith("a:")) Category.Audio else Category.Video
                        else -> Category.Other
                    },
                    name = name?.takeIf { it.isNotBlank() } ?: u.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { u })
            } + list
        }
        save()
        kick()
    }

    fun edit(id: Long, change: (Item) -> Item) {
        var finished: Item? = null
        _items.update { list ->
            list.map {
                if (it.id != id) it else change(it).also { n -> if (n.state == State.Done && it.state != State.Done) finished = n }
            }
        }
        save()
        finished?.let { notifyDone(it) }
    }

    /** "Download complete" with an Open button, if wanted in Settings. */
    private fun notifyDone(item: Item) {
        if (!Prefs.notifyDone) return
        val open = item.doc?.let {
            android.app.PendingIntent.getActivity(
                ctx, item.id.toInt(),
                android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(it), item.mime ?: "*/*")
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val n = androidx.core.app.NotificationCompat.Builder(ctx, DownloadService.CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Downloaded")
            .setContentText(item.name)
            .setAutoCancel(true)
            .apply { open?.let { setContentIntent(it) } }
            .build()
        DownloadService.channel(ctx)
        runCatching { androidx.core.app.NotificationManagerCompat.from(ctx).notify(1000 + (item.id % 100000).toInt(), n) }
    }

    fun get(id: Long) = _items.value.firstOrNull { it.id == id }

    fun pause(id: Long) = edit(id) { if (it.state == State.Queued || it.state == State.Running) it.copy(state = State.Paused) else it }

    fun resume(id: Long) {
        edit(id) { if (it.state == State.Paused || it.state == State.Failed) it.copy(state = State.Queued, error = null) else it }
        kick()
    }

    /** Network's back: failed-but-retrying downloads go again now. */
    fun retryNow() {
        _items.update { list -> list.map { if (it.state == State.Failed && it.error?.endsWith("retrying") == true) it.copy(state = State.Queued, error = null) else it } }
        save()
        kick()
    }

    fun pauseAll() {
        _items.update { list -> list.map { if (it.state == State.Queued || it.state == State.Running) it.copy(state = State.Paused) else it } }
        save()
    }

    fun resumeAll() {
        _items.update { list -> list.map { if (it.state == State.Paused || it.state == State.Failed) it.copy(state = State.Queued, error = null) else it } }
        save()
        kick()
    }

    /** Takes it off the list; [withFile] also deletes what was downloaded. */
    fun delete(id: Long, withFile: Boolean) {
        val item = get(id) ?: return
        _items.update { list -> list.filterNot { it.id == id } }
        save()
        if (withFile) item.doc?.let { Folders.delete(ctx, it) }
        if (item.kind == Kind.Torrent) Torrents.forget(ctx, item)
    }

    fun clearFinished() {
        _items.update { list -> list.filterNot { it.state == State.Done } }
        save()
    }

    /** Starts the download service if anything is waiting. */
    fun kick() {
        if (_items.value.none { it.state == State.Queued || it.state == State.Running }) return
        runCatching { ContextCompat.startForegroundService(ctx, android.content.Intent(ctx, DownloadService::class.java)) }
    }

    private val saveLock = Any()

    fun save() = synchronized(saveLock) {
        val tmp = File(file.parentFile, "downloads.json.tmp")
        tmp.writeText(encode(_items.value))
        tmp.renameTo(file)
    }

    fun encode(list: List<Item>): String = JSONArray().apply {
        list.forEach { i ->
            put(JSONObject().apply {
                put("id", i.id); put("url", i.url); put("name", i.name)
                put("headers", JSONObject(i.headers as Map<*, *>))
                i.mime?.let { put("mime", it) }
                put("category", i.category.name); put("size", i.size); put("resumable", i.resumable)
                i.doc?.let { put("doc", it) }
                put("state", i.state.name); i.error?.let { put("error", it) }
                put("segments", i.segments); put("done", i.done); put("created", i.created); put("finished", i.finished)
                i.fixedName?.let { put("fixedName", it) }
                put("kind", i.kind.name); put("files", i.files); put("sequential", i.sequential); put("format", i.format)
            })
        }
    }.toString()

    fun parse(text: String): List<Item> {
        val a = JSONArray(text)
        return (0 until a.length()).map { n ->
            val o = a.getJSONObject(n)
            val h = o.optJSONObject("headers")
            Item(
                id = o.getLong("id"), url = o.getString("url"), name = o.getString("name"),
                headers = h?.keys()?.asSequence()?.associateWith { h.getString(it) } ?: emptyMap(),
                mime = o.optString("mime").ifEmpty { null },
                category = runCatching { Category.valueOf(o.optString("category")) }.getOrDefault(Category.Other),
                size = o.optLong("size", -2), resumable = o.optBoolean("resumable"),
                doc = o.optString("doc").ifEmpty { null },
                state = runCatching { State.valueOf(o.optString("state")) }.getOrDefault(State.Paused),
                error = o.optString("error").ifEmpty { null },
                segments = o.optString("segments"), done = o.optLong("done"),
                created = o.optLong("created"), finished = o.optLong("finished"),
                fixedName = o.optString("fixedName").ifEmpty { null },
                kind = runCatching { Kind.valueOf(o.optString("kind")) }.getOrDefault(Kind.Http),
                files = o.optString("files"), sequential = o.optBoolean("sequential"), format = o.optString("format"),
            )
        }
    }
}

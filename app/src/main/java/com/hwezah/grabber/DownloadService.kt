package com.hwezah.grabber

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.hwezah.grabber.engine.Category
import com.hwezah.grabber.engine.ChannelSink
import com.hwezah.grabber.engine.HlsDownload
import com.hwezah.grabber.engine.Http
import com.hwezah.grabber.engine.HttpDownload
import com.hwezah.grabber.engine.Names
import com.hwezah.grabber.engine.RateLimiter
import com.hwezah.grabber.engine.Segments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileOutputStream

/**
 * Runs the queue: up to [Prefs.parallel] downloads at once, each over [Prefs.connections] connections.
 * A foreground service (a notification shows while it works), so Android lets it keep going with the app closed.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** [bytes] reads how far it is; [rate] a speed the engine knows itself (torrents). */
    private class Running(val job: Job, @Volatile var bytes: (() -> Long)? = null, @Volatile var rate: Long = -1, @Volatile var text: String? = null)
    private val running = HashMap<Long, Running>()
    private val limiter = RateLimiter()
    private var wake: PowerManager.WakeLock? = null
    private val last = HashMap<Long, Long>()
    @Volatile private var updating = false
    private val retries = java.util.concurrent.ConcurrentHashMap<Long, Int>()
    private val deadTries = java.util.concurrent.ConcurrentHashMap<Long, Int>()
    /** Downloads already sent back to their original link since their last progress. */
    private val refetched = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val lastDone = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Downloads.init(this)
        channel(this)
        ServiceCompat.startForeground(
            this, NOTE, note("Starting…", null, 0, true),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "grabber:downloads").apply { acquire(6 * 60 * 60 * 1000L) }
        scope.launch { Downloads.items.collect { pump() } }
        scope.launch {
            while (isActive) {
                delay(1000)
                tick()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PAUSE_ALL -> Downloads.pauseAll()
            RESUME_ALL -> Downloads.resumeAll()
            UPDATE -> if (!updating) {
                updating = true
                scope.launch {
                    try { Updater.fetchAndInstall(this@DownloadService) } finally {
                        updating = false
                        pump()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    @Synchronized
    private fun pump() {
        val items = Downloads.items.value
        // Paused or deleted: stop it.
        running.keys.toList().forEach { id ->
            val st = items.firstOrNull { it.id == id }?.state
            if (st != State.Running && st != State.Queued) running.remove(id)?.job?.cancel()
        }
        val blockedByWifi = Prefs.wifiOnly && metered()
        if (blockedByWifi) {
            // Wi-Fi only and on mobile data: running ones pause back into the queue, nothing new starts.
            running.keys.toList().forEach { id -> running.remove(id)?.job?.cancel(); Downloads.edit(id) { it.copy(state = State.Queued) } }
        }
        items.asReversed().filter { it.state == State.Queued && it.id !in running }.forEach { item ->
            if (running.size >= Prefs.parallel || blockedByWifi) return@forEach
            Downloads.edit(item.id) { it.copy(state = State.Running, error = null) }
            // Lazy, so it is in the map before it runs.
            val job = scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) { work(item.id) }
            running[item.id] = Running(job)
            job.start()
        }
        val updateBusy = updating || Updater.status is Updater.Status.Downloading || Updater.status is Updater.Status.Installing
        if (running.isEmpty() && (items.none { it.state == State.Queued } || blockedByWifi) && !updateBusy) finish(waitingForWifi = blockedByWifi && items.any { it.state == State.Queued })
    }

    private suspend fun work(id: Long) {
        try {
            var item = Downloads.get(id) ?: return
            if (item.kind == Kind.Torrent) return torrent(item)
            if (item.kind == Kind.Hls) return hls(item)
            if (item.kind == Kind.Media) return media(item)
            if (item.doc == null) {
                val tree = Prefs.folder ?: throw IllegalStateException("Choose a download folder in Settings")
                val probed = Http.probe(item.url, item.headers)
                val info = item.fixedName?.let { probed.copy(name = Names.clean(it)) } ?: probed
                val category = Names.category(info.name, info.mime)
                val doc = Folders.create(this, tree, category, info.name, part = true) ?: throw IllegalStateException("Can't write to the download folder; choose it again in Settings")
                Downloads.edit(id) {
                    it.copy(url = info.finalUrl, name = (Uri.parse(doc.toString()).lastPathSegment?.substringAfterLast('/') ?: info.name).removeSuffix(".part"),
                        mime = info.mime, category = category, size = info.size, resumable = info.resumable, doc = doc.toString(), segments = "", done = 0)
                }
                item = Downloads.get(id) ?: return
            }
            // Marked "can't resume" before: ask the server again, it may well allow parts.
            if (!item.resumable) {
                runCatching { Http.probe(item.url, item.headers) }.getOrNull()?.takeIf { it.resumable && it.size > 0 }?.let { info ->
                    val kept = Segments.decode(item.segments).firstOrNull()?.pos?.coerceIn(0, info.size) ?: 0L
                    val segs = listOf(com.hwezah.grabber.engine.Segment(0, info.size - 1, kept))
                    Downloads.edit(id) { it.copy(resumable = true, size = info.size, segments = Segments.encode(segs), done = kept) }
                    item = Downloads.get(id) ?: return
                }
            }
            val pfd = contentResolver.openFileDescriptor(Uri.parse(item.doc), "rw") ?: throw IllegalStateException("The file is gone")
            pfd.use {
                ChannelSink(FileOutputStream(it.fileDescriptor).channel).use { sink ->
                    limiter.bytesPerSecond = Prefs.speedLimit
                    val dl = HttpDownload(
                        url = item.url, headers = item.headers, size = item.size, resumable = item.resumable, sink = sink,
                        // Kept for single-connection downloads too: they try to continue where they stopped.
                        segments = Segments.decode(item.segments),
                        connections = Prefs.connections.coerceIn(1, 64), limiter = limiter,
                        onCheckpoint = { segs ->
                            val done = segs.sumOf { s -> s.pos - s.start }
                            if (done > (Downloads.get(id)?.done ?: 0)) refetched.remove(id)
                            Downloads.edit(id) { d -> d.copy(segments = Segments.encode(segs), done = done) }
                        },
                    )
                    synchronized(this) { running[id]?.bytes = dl::downloaded }
                    dl.run()
                    val total = dl.downloaded()
                    retries.remove(id)
                    val (doc, name) = Folders.finish(this, item.doc!!, item.name)
                    Downloads.edit(id) { d -> d.copy(state = State.Done, doc = doc, name = name, done = total, size = if (d.size > 0) d.size else total, segments = "", finished = System.currentTimeMillis()) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // A resume failed: first go back to the original link for a fresh address (temporary links expire),
            // and carry on from where it stopped. Only once per stretch without progress.
            val failed = Downloads.get(id)
            val noResume = e is com.hwezah.grabber.engine.NoResumeException
            val refetchable = failed != null && failed.kind == Kind.Http && failed.source.isNotBlank() &&
                (noResume || e is com.hwezah.grabber.engine.HttpException || e.message?.contains("stopped allowing parts") == true)
            if (refetchable && refetched.add(id)) {
                val fresh = runCatching { Http.probe(failed!!.source, failed.headers) }.getOrNull()
                if (fresh != null) {
                    Downloads.edit(id) {
                        val segs = if (fresh.resumable && fresh.size > 0 && !it.resumable) {
                            val kept = Segments.decode(it.segments).firstOrNull()?.pos?.coerceIn(0, fresh.size) ?: 0L
                            Segments.encode(listOf(com.hwezah.grabber.engine.Segment(0, fresh.size - 1, kept)))
                        } else it.segments
                        it.copy(url = fresh.finalUrl, resumable = it.resumable || fresh.resumable && fresh.size > 0,
                            size = if (fresh.size > 0) fresh.size else it.size, segments = segs, state = State.Queued, error = null)
                    }
                    return
                }
            }
            if (noResume) {
                Downloads.edit(id) { it.copy(state = State.Failed, error = "This server can't continue it from where it stopped. Tap it, then Start over") }
                return
            }
            val code = (e as? com.hwezah.grabber.engine.HttpException)?.code
            // The link itself is gone or now points at something else: keep the progress, ask for a fresh link.
            val linkDead = (code != null && code in setOf(401, 403, 404, 410, 412)) || e.message?.contains("stopped allowing parts") == true
            val network = e is java.io.IOException && !linkDead && (code == null || code >= 500 || code == 408 || code == 429)
            val tries = (retries[id] ?: 0) + 1
            // "Dead" links often come back (a refusing server, a CDN hiccup): retry those a few times too,
            // and as long as each try gets further, keep going.
            val doneNow = Downloads.get(id)?.done ?: 0
            if (doneNow > (lastDone[id] ?: -1)) deadTries[id] = 0
            lastDone[id] = doneNow
            val deadAgain = linkDead && Prefs.autoRetry && (deadTries[id] ?: 0) < 3
            if (deadAgain) deadTries[id] = (deadTries[id] ?: 0) + 1
            val again = Prefs.autoRetry && (network || deadAgain)
            val msg = when {
                deadAgain -> "Server refused (${code ?: "?"}) · retrying"
                linkDead -> "Link expired or changed. Tap it, then Refresh link"
                else -> (e.message ?: e.javaClass.simpleName) + if (again) " · retrying" else ""
            }
            Downloads.edit(id) { it.copy(state = if (it.state == State.Running) State.Failed else it.state, error = msg) }
            if (again) {
                // However long it takes: waits grow to 5 minutes, and a returning network wakes it sooner.
                retries[id] = tries
                NetWait.arm(this)
                scope.launch {
                    delay(minOf(300_000L, 10_000L shl minOf(tries, 5)))
                    Downloads.edit(id) { if (it.state == State.Failed) it.copy(state = State.Queued, error = null) else it }
                }
            }
        } finally {
            synchronized(this) { running.remove(id) }
            Downloads.live.value = Downloads.live.value - id
            pump()
        }
    }

    private suspend fun torrent(item: Item) {
        val id = item.id
        val doc = Torrents.run(this, item) { p ->
            synchronized(this) { running[id]?.let { it.bytes = { p.done }; it.rate = p.rate } }
            Downloads.edit(id) { it.copy(done = p.done, size = p.total, error = if (p.peers == 0) "Looking for peers…" else null) }
        }
        Downloads.edit(id) { it.copy(state = State.Done, doc = doc, error = null, finished = System.currentTimeMillis(), done = it.size) }
    }

    /** yt-dlp: progress comes as a percentage; it's shown as such. */
    private suspend fun media(item: Item) {
        val id = item.id
        Downloads.edit(id) { it.copy(size = 1000) }
        val (doc, name, size) = Media.run(this, item) { p0 ->
            // aria2c reports its own way: "[#ab12 12MiB/120MiB(10%) CN:16 DL:4.5MiB ETA:24s]".
            val ariaPct = Regex("""\((\d+)%\)""").find(p0.line)?.groupValues?.get(1)?.toFloatOrNull()
            val p = if (p0.pct <= 0f && ariaPct != null) Media.Progress(ariaPct, p0.eta, p0.line) else p0
            val speed = Regex("""at\s+([\d.]+\s*[KMG]i?B/s)""").find(p.line)?.groupValues?.get(1)
                ?: Regex("""DL:([\d.]+[KMG]?i?B)""").find(p.line)?.groupValues?.get(1)?.let { "$it/s" }
            val text = listOfNotNull(
                if (p.pct >= 0) "${p.pct.toInt()}%" else null,
                speed,
                if (p.eta > 0) "${p.eta / 60}:${"%02d".format(p.eta % 60)} left" else null,
                if (p.line.contains("ExtractAudio") || p.line.contains("Merger")) "converting…" else null,
            ).joinToString(" · ").ifEmpty { "Working…" }
            val done = (p.pct.coerceIn(0f, 100f) * 10).toLong()
            synchronized(this) { running[id]?.let { it.bytes = { done }; it.text = text; it.rate = 0 } }
            Downloads.edit(id) { it.copy(done = done, size = 1000) }
        }
        val src = item.url.takeIf { it.startsWith("file://") }?.let { java.io.File(Uri.parse(it).path ?: "") }
        src?.delete()
        Downloads.edit(id) { it.copy(state = State.Done, doc = doc, name = name, mime = null, size = size, done = size, finished = System.currentTimeMillis()).let { d -> d.copy(mime = mimeOf(d)) } }
    }

    /** An HLS stream: segments into the cache, then joined into one file in the Video folder. */
    private suspend fun hls(item0: Item) {
        var item = item0
        val id = item.id
        if (item.doc == null) {
            val tree = Prefs.folder ?: throw IllegalStateException("Choose a download folder in Settings")
            val base = (item.fixedName ?: item.name).substringBeforeLast('.').ifBlank { "video" }
            val name = Names.clean(base) + ".ts"
            val doc = Folders.create(this, tree, Category.Video, name, part = true) ?: throw IllegalStateException("Can't write to the download folder")
            Downloads.edit(id) { it.copy(doc = doc.toString(), name = (doc.lastPathSegment?.substringAfterLast('/') ?: name).removeSuffix(".part"), category = Category.Video) }
            item = Downloads.get(id) ?: return
        }
        val pfd = contentResolver.openFileDescriptor(Uri.parse(item.doc), "rw") ?: throw IllegalStateException("The file is gone")
        pfd.use {
            ChannelSink(FileOutputStream(it.fileDescriptor).channel).use { sink ->
                limiter.bytesPerSecond = Prefs.speedLimit
                val dl = HlsDownload(item.url, item.headers, sink, java.io.File(filesDir, "work/hls/$id"), minOf(Prefs.connections, 16), limiter)
                synchronized(this) { running[id]?.bytes = dl::downloaded }
                val ticker = scope.launch {
                    while (true) {
                        delay(1000)
                        val t = dl.total
                        // Size isn't known until the end: estimate from the segments so far.
                        if (t > 0 && dl.segmentsDone() > 0) {
                            val est = dl.downloaded() * t / dl.segmentsDone()
                            Downloads.edit(id) { d -> d.copy(done = dl.downloaded(), size = est) }
                        }
                    }
                }
                try {
                    dl.run()
                } finally {
                    ticker.cancel()
                }
                val total = dl.downloaded()
                val (doc, name) = Folders.finish(this@DownloadService, item.doc!!, item.name)
                Downloads.edit(id) { d -> d.copy(state = State.Done, doc = doc, name = name, done = total, size = total, finished = System.currentTimeMillis()) }
            }
        }
    }

    /** Every second: speeds for the screen, and the notification. */
    private fun tick() {
        limiter.bytesPerSecond = Prefs.speedLimit
        val rates = synchronized(this) { running.mapValues { it.value.rate } }
        val texts = synchronized(this) { running.mapValues { it.value.text } }
        val snap = synchronized(this) { running.mapValues { it.value.bytes?.invoke() ?: 0L } }
        val live = snap.mapValues { (id, bytes) -> Live(bytes, rates[id]?.takeIf { it >= 0 } ?: (bytes - (last[id] ?: bytes)).coerceAtLeast(0), texts[id]) }
        last.clear(); last.putAll(snap)
        Downloads.live.value = live
        if (snap.isEmpty()) {
            (Updater.status as? Updater.Status.Downloading)?.let {
                runCatching { NotificationManagerCompat.from(this).notify(NOTE, note("Updating Grabber", "${it.pct}%", it.pct, false)) }
            }
            return
        }
        val items = Downloads.items.value.filter { it.id in snap }
        val speed = live.values.sumOf { it.speed }
        val size = items.sumOf { maxOf(it.size, 0) }
        val done = snap.values.sum()
        val title = if (items.size == 1) items[0].name else "${items.size} downloads"
        val pct = if (size > 0) (done * 100 / size).toInt() else 0
        val queued = Downloads.items.value.count { it.state == State.Queued }
        val text = "${Fmt.speed(speed)} · ${Fmt.bytes(done)}${if (size > 0) " of ${Fmt.bytes(size)}" else ""}" + if (queued > 0) " · $queued waiting" else ""
        runCatching { NotificationManagerCompat.from(this).notify(NOTE, note(title, text, pct, size <= 0)) }
    }

    private fun note(title: String, text: String?, pct: Int, indeterminate: Boolean) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, pct, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(this))
            .addAction(0, "Pause all", action(this, PAUSE_ALL))
            .build()

    private fun metered(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun finish(waitingForWifi: Boolean = false) {
        if (waitingForWifi) WifiWait.arm(this)
        val items = Downloads.items.value
        val paused = items.count { it.state == State.Paused }
        val failed = items.count { it.state == State.Failed }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (waitingForWifi) {
            val n = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Waiting for Wi-Fi")
                .setContentText("Downloads carry on when you're on Wi-Fi")
                .setSilent(true)
                .setContentIntent(openApp(this))
                .build()
            runCatching { NotificationManagerCompat.from(this).notify(DONE_NOTE, n) }
        } else if (paused + failed > 0) {
            val n = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(listOfNotNull(paused.takeIf { it > 0 }?.let { "$it paused" }, failed.takeIf { it > 0 }?.let { "$it failed" }).joinToString(" · "))
                .setContentText("Tap Resume all to carry on")
                .setSilent(true)
                .setContentIntent(openApp(this))
                .addAction(0, "Resume all", action(this, RESUME_ALL))
                .build()
            runCatching { NotificationManagerCompat.from(this).notify(DONE_NOTE, n) }
        } else {
            NotificationManagerCompat.from(this).cancel(DONE_NOTE)
        }
        stopSelf()
    }

    companion object {
        const val CHANNEL = "downloads"
        const val NOTE = 1
        const val DONE_NOTE = 2
        const val PAUSE_ALL = "com.hwezah.grabber.PAUSE_ALL"
        const val RESUME_ALL = "com.hwezah.grabber.RESUME_ALL"
        const val UPDATE = "com.hwezah.grabber.UPDATE"

        fun channel(c: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                c.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }

        private fun openApp(c: Context) = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )

        private fun action(c: Context, a: String) = PendingIntent.getService(
            c, a.hashCode(), Intent(c, DownloadService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** After a restart, pick the queue back up. */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.init(context)
        Downloads.init(context)
        Downloads.kick()
    }
}

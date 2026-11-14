package com.hwezah.grabber

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import com.hwezah.grabber.engine.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Updates from GitHub: the grabber-latest release says its versionCode; when it's newer than this build,
 * a bar offers it, and one tap downloads the APK and hands it to Android's installer.
 */
object Updater {
    sealed interface Status {
        data object None : Status
        data class Ready(val code: Int, val url: String, val size: Long) : Status
        data class Downloading(val pct: Int) : Status
        data object Installing : Status
        data object NeedsPermission : Status
        data class Failed(val message: String) : Status
    }

    private const val RELEASE = "https://api.github.com/repos/Hwezah/Grabber/releases/tags/grabber-latest"
    var status by mutableStateOf<Status>(Status.None)
    var dismissed by mutableStateOf(false)
    private var lastCheck = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Asks GitHub, at most every 10 minutes unless [force]. */
    fun check(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 10 * 60_000) return
        if (status is Status.Downloading || status is Status.Installing) return
        lastCheck = now
        scope.launch {
            runCatching {
                val body = Http.client.newCall(Request.Builder().url(RELEASE).header("Accept", "application/vnd.github+json").build()).execute().use { it.body!!.string() }
                val json = JSONObject(body)
                val code = Regex("""versionCode (\d+)""").find(json.optString("body"))?.groupValues?.get(1)?.toInt() ?: return@runCatching
                if (code <= BuildConfig.VERSION_CODE) {
                    if (force) status = Status.Failed("You have the latest build ($code)")
                    return@runCatching
                }
                val assets = json.getJSONArray("assets")
                val byName = (0 until assets.length()).map { assets.getJSONObject(it) }.associateBy { it.getString("name") }
                val pick = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { byName["Grabber-$it.apk"] } ?: byName["Grabber-universal.apk"] ?: return@runCatching
                status = Status.Ready(code, pick.getString("browser_download_url"), pick.optLong("size"))
                dismissed = false
            }.onFailure { if (force) status = Status.Failed(it.message ?: "Couldn't reach GitHub") }
        }
    }

    fun update(context: Context) {
        val ready = status as? Status.Ready ?: return
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            status = Status.NeedsPermission
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            // Back to Ready so the next tap goes ahead once allowed.
            scope.launch { kotlinx.coroutines.delay(800); status = ready }
            return
        }
        last = ready
        status = Status.Downloading(0)
        // In the download service: kept alive with the app in the background, like any download.
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java).setAction(DownloadService.UPDATE))
        }.onFailure { status = Status.Failed("Couldn't start the update: ${it.message}") }
    }

    /** The update we're fetching, so Retry can carry on with it. */
    @Volatile var last: Status.Ready? = null
    private var segments = mutableListOf<com.hwezah.grabber.engine.Segment>()
    private var segmentsFor: String? = null

    /** Called by the download service: fetch the APK (resuming across failures), then install it. */
    suspend fun fetchAndInstall(context: Context) {
        val ready = last ?: return
        try {
            val file = File(context.cacheDir, "update.apk")
            val info = Http.probe(ready.url)
            if (segmentsFor != info.finalUrl.substringBefore('?') + ready.code) {
                segments = mutableListOf()
                segmentsFor = info.finalUrl.substringBefore('?') + ready.code
                file.delete()
            }
            var tries = 0
            while (true) {
                try {
                    java.io.RandomAccessFile(file, "rw").channel.use { ch ->
                        val dl = com.hwezah.grabber.engine.HttpDownload(
                            url = info.finalUrl, headers = emptyMap(), size = info.size, resumable = info.resumable,
                            sink = com.hwezah.grabber.engine.ChannelSink(ch), segments = segments, connections = 8,
                            limiter = com.hwezah.grabber.engine.RateLimiter(),
                        )
                        kotlinx.coroutines.coroutineScope {
                            val ticker = launch {
                                while (true) {
                                    if (info.size > 0) status = Status.Downloading((dl.downloaded() * 100 / info.size).toInt())
                                    kotlinx.coroutines.delay(500)
                                }
                            }
                            try { dl.run() } finally { ticker.cancel() }
                        }
                    }
                    break
                } catch (e: java.io.IOException) {
                    if (++tries > 5) throw e
                    kotlinx.coroutines.delay(2000L * tries)
                }
            }
            status = Status.Installing
            install(context.applicationContext, file)
            segments = mutableListOf()
            segmentsFor = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            status = Status.Failed("Update interrupted (${e.message ?: "network"}). Tap Retry to carry on.")
        }
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("grabber.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, id, Intent(context, UpdateReceiver::class.java), flags)
            session.commit(pending.intentSender)
        }
    }
}

/** Android's answer from the installer: show its confirm screen, or say what went wrong. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let {
                    runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.status = Updater.Status.None
            else -> Updater.status = Updater.Status.Failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install cancelled")
        }
    }
}

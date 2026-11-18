package com.hwezah.grabber

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Small settings, kept on the phone. Compose state, so the screen redraws when one changes. */
object Prefs {
    private const val FILE = "grabber"
    private lateinit var ctx: Context
    private val sp get() = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var dark by mutableStateOf(true)
        private set
    var pillBar by mutableStateOf(true)
        private set
    /** Connections per file. */
    var connections by mutableIntStateOf(32)
        private set
    /** Downloads running at once; the rest wait in the queue. */
    var parallel by mutableIntStateOf(3)
        private set
    /** Bytes per second across everything; 0 = no limit. */
    var speedLimit by mutableLongStateOf(0L)
        private set
    /** The folder the user picked (a SAF tree URI), or null until they pick one. */
    var folder by mutableStateOf<String?>(null)
        private set

    var wifiOnly by mutableStateOf(false)
        private set
    var autoRetry by mutableStateOf(true)
        private set
    var notifyDone by mutableStateOf(true)
        private set
    var userAgent by mutableStateOf("")
        private set
    var savePlainText by mutableStateOf(true)
        private set
    var offerOnOpen by mutableStateOf(true)
        private set
    var historyLimit by mutableIntStateOf(500)
        private set
    var sequentialDefault by mutableStateOf(false)
        private set
    /** "v:best", "v:1080", "v:720", "v:480". */
    var videoQuality by mutableStateOf("v:1080")
        private set
    /** "mp3", "m4a", "opus". */
    var audioFormat by mutableStateOf("mp3")
        private set

    /** The browser drops requests to ad and tracker networks. */
    var blockAds by mutableStateOf(true)
        private set

    fun useBlockAds(v: Boolean) { blockAds = v; sp.edit().putBoolean("blockAds", v).apply() }

    /** Where typed words are searched: a name from [Browser.ENGINES]. */
    var searchEngine by mutableStateOf("DuckDuckGo")
        private set
    var blockPopups by mutableStateOf(true)
        private set
    /** Watch what pages load for videos and files (the grab button). */
    var sniffMedia by mutableStateOf(true)
        private set
    /** Stop pages jumping to other sites (ads, betting) unless asked. */
    var blockRedirects by mutableStateOf(true)
        private set
    /** Show light web pages dark. */
    var darkPages by mutableStateOf(true)
        private set

    fun useBlockRedirects(v: Boolean) { blockRedirects = v; sp.edit().putBoolean("blockRedirects", v).apply() }
    fun useDarkPages(v: Boolean) { darkPages = v; sp.edit().putBoolean("darkPages", v).apply(); Browser.applySettings() }

    fun useSearchEngine(v: String) { searchEngine = v; sp.edit().putString("searchEngine", v).apply() }
    fun useBlockPopups(v: Boolean) { blockPopups = v; sp.edit().putBoolean("blockPopups", v).apply(); Browser.applySettings() }
    fun useSniffMedia(v: Boolean) { sniffMedia = v; sp.edit().putBoolean("sniffMedia", v).apply() }

    /** The main accent's name, one of Ink.ACCENTS. */
    var accent by mutableStateOf("Deep Red")
        private set

    fun init(context: Context) {
        if (::ctx.isInitialized) return
        ctx = context.applicationContext
        dark = sp.getBoolean("dark", true)
        pillBar = sp.getBoolean("pillBar", true)
        connections = sp.getInt("connections", 32)
        parallel = sp.getInt("parallel", 3)
        speedLimit = sp.getLong("speedLimit", 0L)
        folder = sp.getString("folder", null)
        wifiOnly = sp.getBoolean("wifiOnly", false)
        autoRetry = sp.getBoolean("autoRetry", true)
        notifyDone = sp.getBoolean("notifyDone", true)
        userAgent = sp.getString("userAgent", "") ?: ""
        savePlainText = sp.getBoolean("savePlainText", true)
        offerOnOpen = sp.getBoolean("offerOnOpen", true)
        historyLimit = sp.getInt("historyLimit", 500)
        sequentialDefault = sp.getBoolean("sequentialDefault", false)
        videoQuality = sp.getString("videoQuality", "v:1080") ?: "v:1080"
        audioFormat = sp.getString("audioFormat", "mp3") ?: "mp3"
        com.hwezah.grabber.engine.Http.userAgent = userAgent
        // Deep red is the house accent: switched to once, after that whatever is picked stays.
        if (!sp.getBoolean("deepRed", false)) sp.edit().putString("accent", "Deep Red").putBoolean("deepRed", true).apply()
        useAccent(sp.getString("accent", "Deep Red") ?: "Deep Red", save = false)
        blockAds = sp.getBoolean("blockAds", true)
        searchEngine = sp.getString("searchEngine", "DuckDuckGo") ?: "DuckDuckGo"
        blockPopups = sp.getBoolean("blockPopups", true)
        sniffMedia = sp.getBoolean("sniffMedia", true)
        darkPages = sp.getBoolean("darkPages", true)
        blockRedirects = sp.getBoolean("blockRedirects", true)
    }

    fun useAccent(name: String, save: Boolean = true) {
        val c = com.hwezah.grabber.design.Ink.ACCENTS.firstOrNull { it.first == name } ?: com.hwezah.grabber.design.Ink.ACCENTS.first()
        accent = c.first
        com.hwezah.grabber.design.Ink.red = c.second
        if (save) sp.edit().putString("accent", c.first).apply()
    }

    fun useWifiOnly(v: Boolean) { wifiOnly = v; sp.edit().putBoolean("wifiOnly", v).apply(); Downloads.kick() }
    fun useAutoRetry(v: Boolean) { autoRetry = v; sp.edit().putBoolean("autoRetry", v).apply() }
    fun useNotifyDone(v: Boolean) { notifyDone = v; sp.edit().putBoolean("notifyDone", v).apply() }
    fun useUserAgent(v: String) { userAgent = v; sp.edit().putString("userAgent", v).apply(); com.hwezah.grabber.engine.Http.userAgent = v }
    fun useSavePlainText(v: Boolean) { savePlainText = v; sp.edit().putBoolean("savePlainText", v).apply() }
    fun useOfferOnOpen(v: Boolean) { offerOnOpen = v; sp.edit().putBoolean("offerOnOpen", v).apply() }
    fun useHistoryLimit(v: Int) { historyLimit = v; sp.edit().putInt("historyLimit", v).apply() }
    fun useSequentialDefault(v: Boolean) { sequentialDefault = v; sp.edit().putBoolean("sequentialDefault", v).apply() }
    fun useVideoQuality(v: String) { videoQuality = v; sp.edit().putString("videoQuality", v).apply() }
    fun useAudioFormat(v: String) { audioFormat = v; sp.edit().putString("audioFormat", v).apply() }

    fun useDark(v: Boolean) { dark = v; sp.edit().putBoolean("dark", v).apply() }
    fun usePillBar(v: Boolean) { pillBar = v; sp.edit().putBoolean("pillBar", v).apply() }
    fun useConnections(v: Int) { connections = v; sp.edit().putInt("connections", v).apply() }
    fun useParallel(v: Int) { parallel = v; sp.edit().putInt("parallel", v).apply() }
    fun useSpeedLimit(v: Long) { speedLimit = v; sp.edit().putLong("speedLimit", v).apply() }
    fun useFolder(v: String?) { folder = v; sp.edit().putString("folder", v).apply() }
}

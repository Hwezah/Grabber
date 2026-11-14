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

    fun init(context: Context) {
        if (::ctx.isInitialized) return
        ctx = context.applicationContext
        dark = sp.getBoolean("dark", true)
        pillBar = sp.getBoolean("pillBar", true)
        connections = sp.getInt("connections", 32)
        parallel = sp.getInt("parallel", 3)
        speedLimit = sp.getLong("speedLimit", 0L)
        folder = sp.getString("folder", null)
    }

    fun useDark(v: Boolean) { dark = v; sp.edit().putBoolean("dark", v).apply() }
    fun usePillBar(v: Boolean) { pillBar = v; sp.edit().putBoolean("pillBar", v).apply() }
    fun useConnections(v: Int) { connections = v; sp.edit().putInt("connections", v).apply() }
    fun useParallel(v: Int) { parallel = v; sp.edit().putInt("parallel", v).apply() }
    fun useSpeedLimit(v: Long) { speedLimit = v; sp.edit().putLong("speedLimit", v).apply() }
    fun useFolder(v: String?) { folder = v; sp.edit().putString("folder", v).apply() }
}

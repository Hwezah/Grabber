package com.hwezah.grabber

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Small settings, kept on the phone. Compose state, so the screen redraws when one changes. */
object Prefs {
    private const val FILE = "grabber"
    private lateinit var ctx: Context

    var dark by mutableStateOf(true)
        private set
    var pillBar by mutableStateOf(true)
        private set

    fun init(context: Context) {
        ctx = context.applicationContext
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        dark = p.getBoolean("dark", true)
        pillBar = p.getBoolean("pillBar", true)
    }

    fun setDark(v: Boolean) {
        dark = v
        save("dark", v)
    }

    fun setPillBar(v: Boolean) {
        pillBar = v
        save("pillBar", v)
    }

    private fun save(key: String, v: Boolean) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(key, v).apply()
}

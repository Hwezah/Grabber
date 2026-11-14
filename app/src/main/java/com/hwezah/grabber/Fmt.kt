package com.hwezah.grabber

import java.util.Locale

object Fmt {
    fun bytes(b: Long): String {
        if (b < 0) return "?"
        if (b < 1024) return "$b B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = b / 1024.0
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }

    fun speed(bps: Long) = "${bytes(bps)}/s"

    fun eta(left: Long, bps: Long): String {
        if (bps <= 0 || left <= 0) return ""
        val s = left / bps
        return when {
            s < 60 -> "${s}s left"
            s < 3600 -> "${s / 60}m ${s % 60}s left"
            else -> "${s / 3600}h ${s / 60 % 60}m left"
        }
    }
}

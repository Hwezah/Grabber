package com.hwezah.grabber

/** What a piece of copied text holds that Grabber can act on. */
sealed interface Found {
    val text: String

    data class Web(override val text: String) : Found
    data class Magnet(override val text: String) : Found
}

/** Pulls every link (http/https) and magnet out of free text, in order, without repeats. */
object LinkFinder {
    private val WEB = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)
    private val MAGNET = Regex("""magnet:\?[^\s<>"'`]+""", RegexOption.IGNORE_CASE)

    /** Punctuation that ends a sentence rather than a link. */
    private const val TRAILING = ".,;:!?)]}>'\""

    fun find(text: String): List<Found> {
        val hits = (WEB.findAll(text).map { it.range.first to Found.Web(tidy(it.value)) } +
            MAGNET.findAll(text).map { it.range.first to Found.Magnet(tidy(it.value)) })
            .sortedBy { it.first }
            .map { it.second }
        return hits.distinctBy { it.text }.toList()
    }

    private fun tidy(link: String): String {
        var s = link
        while (s.isNotEmpty() && s.last() in TRAILING) {
            // Keep a closing bracket that has its opening one inside the link (Wikipedia-style URLs).
            val c = s.last()
            if (c == ')' && s.count { it == '(' } >= s.count { it == ')' }) break
            s = s.dropLast(1)
        }
        return s
    }
}

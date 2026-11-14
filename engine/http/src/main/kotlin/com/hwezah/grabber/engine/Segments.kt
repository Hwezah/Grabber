package com.hwezah.grabber.engine

/** Bytes [start, end] of the file; [pos] is the next byte to write. Done when pos > end. */
class Segment(val start: Long, @Volatile var end: Long, @Volatile var pos: Long) {
    @Volatile var busy = false
    val remaining get() = (end - pos + 1).coerceAtLeast(0)
    val done get() = pos > end
}

object Segments {
    /** Splits [total] bytes into up to [count] parts of at least [minPart] bytes. */
    fun plan(total: Long, count: Int, minPart: Long): MutableList<Segment> {
        if (total <= 0) return mutableListOf()
        val n = maxOf(1L, minOf(count.toLong(), total / minPart.coerceAtLeast(1))).toInt()
        val size = total / n
        return MutableList(n) { i ->
            val s = i * size
            val e = if (i == n - 1) total - 1 else s + size - 1
            Segment(s, e, s)
        }
    }

    /** "start:end:pos,…" — saved with the download so it resumes after the app is killed. */
    fun encode(list: List<Segment>) = list.joinToString(",") { "${it.start}:${it.end}:${it.pos}" }

    fun decode(text: String?): MutableList<Segment> =
        text.orEmpty().split(',').filter { it.isNotBlank() }.mapNotNull { part ->
            val v = part.split(':').mapNotNull { it.toLongOrNull() }
            if (v.size == 3) Segment(v[0], v[1], v[2]) else null
        }.toMutableList()
}

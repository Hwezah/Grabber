package com.hwezah.grabber.engine

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Where bytes land: positional writes, so many connections can fill one file at once. */
interface Sink : Closeable {
    fun write(position: Long, data: ByteBuffer)
    fun truncate(size: Long)
}

/** A file channel opened for writing (a plain file, or a SAF document opened "rw" on Android). */
class ChannelSink(private val channel: FileChannel) : Sink {
    override fun write(position: Long, data: ByteBuffer) {
        var p = position
        while (data.hasRemaining()) p += channel.write(data, p)
    }

    override fun truncate(size: Long) {
        channel.truncate(size)
    }

    override fun close() = channel.close()
}

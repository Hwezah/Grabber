package com.hwezah.grabber.engine

import java.net.URLDecoder

enum class Category(val folder: String) { Video("Video"), Audio("Audio"), Documents("Documents"), Archives("Archives"), Torrents("Torrents"), Other("Other") }

object Names {
    private val VIDEO = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "flv", "wmv", "3gp", "mpg", "mpeg")
    private val AUDIO = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr")
    private val DOCS = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub", "csv", "odt", "rtf", "md")
    private val ARCHIVES = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "apk", "iso", "zst")

    fun category(name: String, mime: String?): Category {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext == "torrent" || mime == "application/x-bittorrent" -> Category.Torrents
            ext in VIDEO || mime?.startsWith("video/") == true -> Category.Video
            ext in AUDIO || mime?.startsWith("audio/") == true -> Category.Audio
            ext in DOCS || mime?.startsWith("text/") == true || mime == "application/pdf" -> Category.Documents
            ext in ARCHIVES -> Category.Archives
            else -> Category.Other
        }
    }

    /** The server's name (Content-Disposition), else the last part of the URL path, made safe for a file system. */
    fun fileName(url: String, disposition: String?, mime: String?): String {
        val fromHeader = disposition?.let { d ->
            Regex("""filename\*\s*=\s*[^']*'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(d)?.groupValues?.get(1)?.let { decode(it) }
                ?: Regex("""filename\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(d)?.groupValues?.get(1)
                ?: Regex("""filename\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE).find(d)?.groupValues?.get(1)?.trim()
        }
        // The path only: a bare site link ("https://x.com/") has no file name, not "x.com".
        val path = url.substringBefore('#').substringBefore('?').substringAfter("://").substringAfter('/', "")
        val fromUrl = path.trimEnd('/').substringAfterLast('/').let { decode(it) }
        var name = clean(fromHeader ?: fromUrl).ifBlank { "download" }
        if (!name.contains('.')) extensionFor(mime)?.let { name = "$name.$it" }
        return name
    }

    fun clean(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().trim('.').take(180)

    private fun decode(s: String) = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    private fun extensionFor(mime: String?) = when (mime?.substringBefore(';')?.trim()) {
        "video/mp4" -> "mp4"; "video/webm" -> "webm"; "video/x-matroska" -> "mkv"
        "audio/mpeg" -> "mp3"; "audio/mp4" -> "m4a"; "audio/ogg" -> "ogg"
        "application/pdf" -> "pdf"; "application/zip" -> "zip"; "application/x-bittorrent" -> "torrent"
        "text/plain" -> "txt"; "text/html" -> "html"; "image/jpeg" -> "jpg"; "image/png" -> "png"
        else -> null
    }
}

package com.advancedvideoplayer.player

import java.util.Locale

/** Container / delivery format of a stream, as far as ExoPlayer cares. */
enum class StreamKind(val mimeType: String?) {
    HLS("application/x-mpegURL"),
    DASH("application/dash+xml"),
    SMOOTH("application/vnd.ms-sstr+xml"),
    RTSP("application/x-rtsp"),
    /** Progressive file / transport stream: ExoPlayer sniffs the extractor itself. */
    PROGRESSIVE(null);

    val label: String
        get() = when (this) {
            HLS -> "HLS"
            DASH -> "DASH"
            SMOOTH -> "Smooth Streaming"
            RTSP -> "RTSP"
            PROGRESSIVE -> "Progressive"
        }
}

/**
 * Decides which [StreamKind] a URL is. Pure functions – network sniffing lives in
 * [StreamSniffer] and only runs when the URL itself is ambiguous (e.g. "/stream?id=42").
 */
object StreamTypeDetector {

    /** Cheap, offline guess from scheme / extension / well-known path fragments. null = ambiguous. */
    fun fromUrl(url: String): StreamKind? {
        val lower = url.lowercase(Locale.ROOT)
        if (lower.startsWith("rtsp://") || lower.startsWith("rtsps://")) return StreamKind.RTSP
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null // content:// file:// → progressive

        val path = lower.substringBefore('#').substringBefore('?')
        val full = lower.substringBefore('#')
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") || full.contains("format=m3u8") ||
                    full.contains("type=m3u8") || full.contains(".m3u8?") -> StreamKind.HLS
            path.endsWith(".mpd") || full.contains("format=mpd") || full.contains(".mpd?") -> StreamKind.DASH
            path.endsWith(".ism") || path.endsWith(".isml") || path.contains(".ism/manifest") ||
                    path.contains(".isml/manifest") -> StreamKind.SMOOTH
            path.hasProgressiveExtension() -> StreamKind.PROGRESSIVE
            else -> null
        }
    }

    /** Maps an HTTP Content-Type to a kind (null = unknown / generic such as octet-stream). */
    fun fromContentType(contentType: String?): StreamKind? {
        val ct = contentType?.lowercase(Locale.ROOT)?.substringBefore(';')?.trim() ?: return null
        return when (ct) {
            "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl",
            "audio/x-mpegurl", "application/mpegurl" -> StreamKind.HLS
            "application/dash+xml" -> StreamKind.DASH
            "application/vnd.ms-sstr+xml" -> StreamKind.SMOOTH
            "video/mp4", "video/x-matroska", "video/webm", "video/mp2t", "video/mpeg", "video/quicktime",
            "audio/mpeg", "audio/aac", "audio/mp4", "audio/ogg", "audio/flac", "audio/x-wav",
            "audio/webm", "video/x-flv", "video/3gpp" -> StreamKind.PROGRESSIVE
            else -> null
        }
    }

    /** Looks at the first bytes of the body. */
    fun fromBody(head: ByteArray, length: Int = head.size): StreamKind? {
        if (length <= 0) return null
        val text = String(head, 0, minOf(length, 512), Charsets.ISO_8859_1).trimStart('﻿', ' ', '\r', '\n', '\t')
        return when {
            text.startsWith("#EXTM3U") -> StreamKind.HLS
            text.startsWith("<?xml") || text.startsWith("<MPD") -> when {
                text.contains("<MPD", ignoreCase = false) -> StreamKind.DASH
                text.contains("<SmoothStreamingMedia") -> StreamKind.SMOOTH
                else -> null
            }
            text.startsWith("<SmoothStreamingMedia") -> StreamKind.SMOOTH
            // MPEG-TS sync byte every 188 bytes, or MKV/EBML, MP4 'ftyp', FLV, Ogg … → progressive
            looksLikeMpegTs(head, length) -> StreamKind.PROGRESSIVE
            length >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
                    head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() -> StreamKind.PROGRESSIVE
            length >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> StreamKind.PROGRESSIVE
            text.startsWith("FLV") || text.startsWith("OggS") || text.startsWith("fLaC") ||
                    text.startsWith("ID3") || text.startsWith("RIFF") -> StreamKind.PROGRESSIVE
            else -> null
        }
    }

    private fun looksLikeMpegTs(b: ByteArray, len: Int): Boolean {
        if (len < 189 || b[0] != 0x47.toByte()) return false
        return b[188] == 0x47.toByte() && (len < 377 || b[376] == 0x47.toByte())
    }

    private fun String.hasProgressiveExtension(): Boolean {
        val ext = substringAfterLast('.', "")
        return ext in PROGRESSIVE_EXTENSIONS
    }

    private val PROGRESSIVE_EXTENSIONS = setOf(
        "mp4", "m4v", "mkv", "mka", "webm", "ts", "m2ts", "mts", "mov", "avi", "flv", "3gp", "ogv",
        "ogg", "mp3", "aac", "flac", "wav", "m4a", "opus", "mpg", "mpeg", "vob", "wmv"
    )
}

package com.advancedvideoplayer.data

/**
 * Pure helpers (no Android dependencies) that turn raw codec identifiers – FFmpeg names such
 * as "hevc"/"eac3" or Android/ExoPlayer MIME types such as "video/hevc"/"audio/eac3-joc" –
 * into one canonical family key and a human readable label.
 */
object CodecNames {

    /** Canonical key, e.g. "h264", "hevc", "av1", "eac3", "truehd", "dts". "" when unknown. */
    fun family(raw: String): String {
        val c = raw.lowercase().trim()
        if (c.isEmpty()) return ""
        return when {
            // ---- video ----
            c.contains("dolby-vision") || c.startsWith("dvhe") || c.startsWith("dvh1") ||
                    c.startsWith("dav1") || c.startsWith("dvav") || c.startsWith("dva1") -> "dolbyvision"
            c.contains("avc") || c.contains("h264") || c.contains("h.264") -> "h264"
            c.contains("hevc") || c.contains("h265") || c.contains("h.265") ||
                    c.startsWith("hvc1") || c.startsWith("hev1") -> "hevc"
            c.contains("av01") || c == "av1" || c.endsWith("/av01") || c.endsWith("/av1") -> "av1"
            c.contains("vp9") || c.startsWith("vp09") -> "vp9"
            c.contains("vp8") -> "vp8"
            c.contains("mpeg2video") || c.contains("mpeg2") || c == "video/mpeg" -> "mpeg2"
            c.contains("mp4v") || c.contains("mpeg4") -> "mpeg4"
            c.contains("vc1") || c.contains("vc-1") || c.contains("wvc1") -> "vc1"
            c.contains("h263") -> "h263"
            // ---- audio (order matters: uhd before hd before core) ----
            c.contains("dts.uhd") || c.contains("dtsx") || c.contains("dts:x") -> "dts"
            c.contains("dts") || c.startsWith("dtsc") || c.startsWith("dtsh") ||
                    c.startsWith("dtse") || c.startsWith("dtsl") -> "dts"
            c.contains("true-hd") || c.contains("truehd") || c == "mlp" || c.contains("mlpa") ||
                    c.contains("audio/mlp") -> "truehd"
            c.contains("eac3") || c.contains("e-ac-3") || c.contains("ec-3") ||
                    c.contains("ec+3") || c.contains("ec3") -> "eac3"
            c.contains("ac3") || c.contains("ac-3") -> "ac3"
            c.contains("aac") || c.contains("mp4a") -> "aac"
            c.contains("opus") -> "opus"
            c.contains("vorbis") -> "vorbis"
            c.contains("flac") -> "flac"
            c.contains("alac") -> "alac"
            c.contains("mpeg-l2") || c.contains("mp2") -> "mp2"
            c.contains("mpeg") || c.contains("mp3") || c.contains("mpga") -> "mp3"
            c.contains("pcm") || c.contains("raw") || c.contains("wav") -> "pcm"
            c.contains("amr") -> "amr"
            else -> ""
        }
    }

    fun isVideoFamily(family: String) = family in VIDEO_FAMILIES
    fun isAudioFamily(family: String) = family in AUDIO_FAMILIES

    /** Human label for a track, taking detected special flags into account. */
    fun label(track: TrackInfo): String {
        val fam = family(track.codec).ifEmpty { family(track.codecLong) }
        return when (track.type) {
            "video" -> videoLabel(track, fam)
            "audio" -> audioLabel(track, fam)
            else -> subtitleLabel(track.codec)
        }
    }

    private fun videoLabel(t: TrackInfo, fam: String): String {
        val base = when (fam) {
            "h264" -> "H.264 / AVC"
            "hevc" -> "H.265 / HEVC"
            "av1" -> "AV1"
            "vp9" -> "VP9"
            "vp8" -> "VP8"
            "mpeg2" -> "MPEG-2"
            "mpeg4" -> "MPEG-4"
            "vc1" -> "VC-1"
            "h263" -> "H.263"
            "dolbyvision" -> "Dolby Vision"
            else -> t.codecLong.ifBlank { t.codec }.ifBlank { "" }
        }
        return if (t.isDolbyVision && fam != "dolbyvision" && base.isNotEmpty()) "$base (Dolby Vision)" else base
    }

    private fun audioLabel(t: TrackInfo, fam: String): String = when {
        t.isDtsX -> "DTS:X"
        t.isDtsHd -> {
            val p = t.profile.lowercase()
            when {
                Regex("""\bma\b""").containsMatchIn(p) -> "DTS-HD MA"
                p.contains("hra") -> "DTS-HD HRA"
                else -> "DTS-HD"
            }
        }
        fam == "dts" -> "DTS"
        fam == "truehd" -> if (t.isAtmos) "Dolby TrueHD (Atmos)" else "Dolby TrueHD"
        fam == "eac3" -> if (t.isAtmos) "Dolby Digital Plus (Atmos)" else "Dolby Digital Plus"
        fam == "ac3" -> "Dolby Digital"
        fam == "aac" -> "AAC"
        fam == "opus" -> "Opus"
        fam == "vorbis" -> "Vorbis"
        fam == "flac" -> "FLAC"
        fam == "alac" -> "ALAC"
        fam == "mp2" -> "MP2"
        fam == "mp3" -> "MP3"
        fam == "pcm" -> "PCM"
        fam == "amr" -> "AMR"
        else -> t.codecLong.ifBlank { t.codec }
    }

    private fun subtitleLabel(raw: String): String {
        val c = raw.lowercase()
        return when {
            c.contains("subrip") || c.contains("srt") || c.contains("x-subrip") -> "SRT"
            c.contains("ass") || c.contains("ssa") -> "ASS/SSA"
            c.contains("webvtt") || c.contains("vtt") -> "WebVTT"
            c.contains("pgs") || c.contains("hdmv") -> "PGS"
            c.contains("dvd") || c.contains("vobsub") -> "VobSub"
            c.contains("tx3g") || c.contains("mov_text") -> "TX3G"
            c.contains("cea-608") || c.contains("eia_608") || c.contains("608") -> "CEA-608"
            c.contains("cea-708") || c.contains("eia_708") || c.contains("708") -> "CEA-708"
            c.contains("ttml") -> "TTML"
            else -> raw
        }
    }

    /** Container name from a MIME type ("video/x-matroska") or an FFmpeg format list ("matroska,webm"). */
    fun containerLabel(raw: String?): String {
        val c = raw?.lowercase()?.trim().orEmpty()
        return when {
            c.isEmpty() -> ""
            c.contains("matroska") || c.contains("mkv") -> "Matroska (MKV)"
            c.contains("webm") -> "WebM"
            c.contains("mp4") || c.contains("mov") || c.contains("quicktime") || c.contains("isom") -> "MP4 / MOV"
            c.contains("mp2t") || c.contains("mpegts") || c == "ts" -> "MPEG-TS"
            c.contains("mpegurl") || c.contains("m3u8") || c.contains("hls") -> "HLS"
            c.contains("dash") -> "DASH"
            c.contains("flv") -> "FLV"
            c.contains("avi") -> "AVI"
            c.contains("ogg") -> "Ogg"
            c.contains("mpeg") || c.contains("mpegps") -> "MPEG-PS"
            else -> raw.orEmpty()
        }
    }

    fun channelsLabel(channels: Int): String = when (channels) {
        0 -> ""
        1 -> "Mono"
        2 -> "Stereo"
        3 -> "2.1"
        4 -> "4.0"
        5 -> "5.0"
        6 -> "5.1"
        7 -> "6.1"
        8 -> "7.1"
        else -> "${channels}ch"
    }

    fun resolutionLabel(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return ""
        val tier = when {
            height >= 4320 || width >= 7680 -> " (8K)"
            height >= 2160 || width >= 3840 -> " (4K)"
            height >= 1440 -> " (1440p)"
            height >= 1080 || width >= 1920 -> " (1080p)"
            height >= 720 || width >= 1280 -> " (720p)"
            else -> ""
        }
        return "${width}×${height}$tier"
    }

    fun bitrateLabel(bps: Long): String = when {
        bps <= 0 -> ""
        bps >= 1_000_000 -> "%.1f Mbps".format(java.util.Locale.US, bps / 1_000_000.0)
        else -> "${bps / 1000} kbps"
    }

    private val VIDEO_FAMILIES = setOf("h264", "hevc", "av1", "vp9", "vp8", "mpeg2", "mpeg4", "vc1", "h263", "dolbyvision")
    private val AUDIO_FAMILIES = setOf(
        "aac", "ac3", "eac3", "truehd", "dts", "opus", "vorbis", "flac", "alac", "mp2", "mp3", "pcm", "amr"
    )
}

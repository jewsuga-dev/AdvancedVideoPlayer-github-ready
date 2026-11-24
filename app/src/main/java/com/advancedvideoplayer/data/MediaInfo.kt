package com.advancedvideoplayer.data

/**
 * One elementary stream (video / audio / subtitle) as reported by any probe.
 * Unknown numeric values are 0, unknown strings are "" – the UI hides them.
 */
data class TrackInfo(
    val index: Int,
    val type: String,          // video / audio / subtitle
    val codec: String,         // raw codec id: "h264", "hevc", "eac3", "video/avc" …
    val codecLong: String = "",
    val language: String = "",
    val title: String = "",
    val bitrate: Long = 0,
    val channels: Int = 0,
    val sampleRate: Int = 0,
    val channelLayout: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
    val bitDepth: Int = 0,
    val profile: String = "",
    val level: String = "",
    val pixFmt: String = "",
    val colorSpace: String = "",
    val colorTransfer: String = "",
    val colorPrimaries: String = "",
    val isHdr: Boolean = false,
    val isDolbyVision: Boolean = false,
    val dvProfile: String = "",
    val isHdr10Plus: Boolean = false,
    val isAtmos: Boolean = false,
    val isTrueHd: Boolean = false,
    val isDts: Boolean = false,
    val isDtsHd: Boolean = false,
    val isDtsX: Boolean = false,
    val isDolbyDigitalPlus: Boolean = false,
    val isAac: Boolean = false,
    /** True when the device's decoders can play this track (only known for player-sourced tracks). */
    val supported: Boolean? = null,
    val selected: Boolean = false,
    val extra: Map<String, String> = emptyMap()
)

/** Where a [MediaMetadata] came from. Merge order = declaration order of precedence for details. */
enum class MetadataSource { FFPROBE, PLATFORM, PLAYER }

data class MediaMetadata(
    val fileName: String = "",
    /** Title tag (container / ICY / ID3) – falls back to the file name for display. */
    val title: String = "",
    val format: String = "",
    val formatLong: String = "",
    val durationMs: Long = 0,
    val sizeBytes: Long = 0,
    val bitrate: Long = 0,
    val isLive: Boolean = false,
    val videoTracks: List<TrackInfo> = emptyList(),
    val audioTracks: List<TrackInfo> = emptyList(),
    val subtitleTracks: List<TrackInfo> = emptyList(),
    val specialFormats: List<SpecialFormat> = emptyList(),
    val sources: Set<MetadataSource> = emptySet()
) {
    val displayTitle: String get() = title.ifBlank { fileName }

    val isEmpty: Boolean
        get() = videoTracks.isEmpty() && audioTracks.isEmpty() && durationMs <= 0 &&
                bitrate <= 0 && format.isBlank() && title.isBlank()
}

/**
 * Special formats that get a logo badge.
 *
 * [logoKeys] are matched against the files in assets/logos/ after normalising both sides
 * (lower-case, letters+digits only, extension and a trailing "logo" removed), so
 * "Dolby-Atmos-Logo.png", "dolby_atmos.png" and "DolbyAtmos.png" all resolve to DOLBY_ATMOS.
 * No file → the UI falls back to a coloured text badge, so a missing logo is never an error.
 */
enum class SpecialFormat(val displayName: String, val logoKeys: List<String>) {
    DOLBY_VISION("Dolby Vision", listOf("dolbyvision", "dv")),
    DOLBY_ATMOS("Dolby Atmos", listOf("dolbyatmos", "atmos")),
    TRUEHD("Dolby TrueHD", listOf("truehd", "dolbytruehd")),
    DOLBY_DIGITAL("Dolby Digital", listOf("dolbydigital", "dd", "ac3")),
    DOLBY_DIGITAL_PLUS("Dolby Digital Plus", listOf("ddp", "dolbydigitalplus", "eac3")),
    DTS("DTS", listOf("dts")),
    DTS_HD("DTS-HD", listOf("dtshd", "dtshdma")),
    DTS_X("DTS:X", listOf("dtsx")),
    HDR10("HDR10", listOf("hdr10")),
    HDR10_PLUS("HDR10+", listOf("hdr10plus", "hdr10")),
    HLG("HLG", listOf("hlg")),
    AV1("AV1", listOf("av1")),
    HEVC("HEVC / H.265", listOf("hevc", "h265"));

    /** Badge colour family for the text fallback. */
    val family: Family
        get() = when (this) {
            DOLBY_VISION, DOLBY_ATMOS, TRUEHD, DOLBY_DIGITAL, DOLBY_DIGITAL_PLUS -> Family.DOLBY
            DTS, DTS_HD, DTS_X -> Family.DTS
            HDR10, HDR10_PLUS, HLG -> Family.HDR
            AV1, HEVC -> Family.CODEC
        }

    enum class Family { DOLBY, DTS, HDR, CODEC }
}

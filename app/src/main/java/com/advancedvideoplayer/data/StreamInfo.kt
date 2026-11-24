package com.advancedvideoplayer.data

/** The one-line facts the player shows for the current stream. Empty string = not known (hidden). */
data class StreamSummary(
    val title: String = "",
    val resolution: String = "",
    val frameRate: String = "",
    val videoCodec: String = "",
    val audioCodec: String = "",
    val container: String = "",
    val duration: Long = 0L,
    val bitrate: String = "",
    val hdr: String = "",
    val isLive: Boolean = false
) {
    /** "1920×1080 (1080p) • H.264 / AVC • AAC 5.1 • 4.2 Mbps" – only the parts that are known. */
    val subtitleLine: String
        get() = listOf(resolution, videoCodec, hdr, audioCodec, bitrate).filter { it.isNotBlank() }.joinToString("  •  ")
}

/** Everything the UI needs about the current stream, already merged from all sources. */
data class StreamInfo(
    val metadata: MediaMetadata = MediaMetadata(),
    val summary: StreamSummary = StreamSummary(),
    /** Logos for the popup: what is *playing* now (selected audio track, primary video). */
    val logos: List<SpecialFormat> = emptyList(),
    /** True while the optional deep probes are still running. */
    val probing: Boolean = false
) {
    companion object { val EMPTY = StreamInfo() }
}

/**
 * Pure merge of the three independent sources. No source is required: any of them may be null /
 * empty and the result is still the best available picture.
 *
 *  - details (container, duration, bitrate, size, tag title): FFprobe → platform → player
 *  - what is *playing* (resolution, codec names, logos): the player's selected tracks first,
 *    because that is what is really being decoded; probes fill in what the player cannot see
 *    (Dolby Vision RPU, HDR10+, TrueHD-Atmos flags) or when the player has not reported yet.
 */
object StreamInfoMerger {

    fun merge(
        fileName: String,
        sizeBytes: Long,
        ffprobe: MediaMetadata?,
        platform: MediaMetadata?,
        player: MediaMetadata?,
        probing: Boolean
    ): StreamInfo {
        val sources = listOfNotNull(ffprobe, platform, player)

        fun <T> firstNonEmpty(pick: (MediaMetadata) -> T, empty: (T) -> Boolean): T? =
            sources.map(pick).firstOrNull { !empty(it) }

        val title = firstNonEmpty({ it.title.trim() }) { it.isEmpty() }.orEmpty()
        val live = player?.isLive == true
        val duration = if (live) 0L else firstNonEmpty({ it.durationMs }) { it <= 0 } ?: 0L
        val bitrate = firstNonEmpty({ it.bitrate }) { it <= 0 } ?: 0L
        val format = firstNonEmpty({ it.format }) { it.isBlank() }.orEmpty()
        val formatLong = firstNonEmpty({ it.formatLong }) { it.isBlank() }.orEmpty()
        val size = sizeBytes.takeIf { it > 0 } ?: ffprobe?.sizeBytes ?: 0L

        // Track lists for the details panel: richest first.
        val videoTracks = firstNonEmpty({ it.videoTracks }) { it.isEmpty() }.orEmpty()
        val audioTracks = firstNonEmpty({ it.audioTracks }) { it.isEmpty() }.orEmpty()
        val subtitleTracks = firstNonEmpty({ it.subtitleTracks }) { it.isEmpty() }.orEmpty()

        // What is playing.
        val playerVideo = player?.videoTracks.orEmpty()
        val playerAudio = player?.audioTracks.orEmpty()
        val selectedVideo = playerVideo.firstOrNull { it.selected && it.supported != false }
        val selectedAudio = playerAudio.firstOrNull { it.selected && it.supported != false }

        val probeVideos = (ffprobe?.videoTracks.orEmpty()).ifEmpty { platform?.videoTracks.orEmpty() }
        val probeAudios = (ffprobe?.audioTracks.orEmpty()).ifEmpty { platform?.audioTracks.orEmpty() }

        // Merge flags the probes found into the player's track (never lose a detected flag).
        val primaryVideo = (selectedVideo ?: playerVideo.firstOrNull() ?: probeVideos.firstOrNull())
            ?.let { v -> mergeVideoFlags(v, probeVideos) }
        val playingAudio = (selectedAudio ?: probeAudios.firstOrNull() ?: playerAudio.firstOrNull())
            ?.let { a ->
                val m = FormatDetector.matchAudio(probeAudios, a)
                if (m == null || m === a) a else mergeAudioFlags(a, m)
            }

        // Dolby Vision is shown once, in the HDR slot, not also inside the codec name.
        val videoLabel = primaryVideo?.let { CodecNames.label(it.copy(isDolbyVision = false)) }
            .orEmpty().let { if (it == "Dolby Vision") "" else it }
        val audioLabel = playingAudio?.let {
            listOf(CodecNames.label(it), CodecNames.channelsLabel(it.channels)).filter { s -> s.isNotBlank() }.joinToString(" ")
        }.orEmpty()
        val hdrLabel = primaryVideo?.let {
            when {
                it.isDolbyVision -> "Dolby Vision" + if (it.dvProfile.isNotBlank()) " P${it.dvProfile}" else ""
                it.isHdr10Plus -> "HDR10+"
                FormatDetector.isPq(it.colorTransfer.lowercase()) -> "HDR10"
                FormatDetector.isHlg(it.colorTransfer.lowercase()) -> "HLG"
                else -> ""
            }
        }.orEmpty()

        val summaryBitrate = bitrate.takeIf { it > 0 }
            ?: (primaryVideo?.bitrate ?: 0L) + (playingAudio?.bitrate ?: 0L)

        val summary = StreamSummary(
            title = title.ifBlank { fileName },
            resolution = primaryVideo?.let { CodecNames.resolutionLabel(it.width, it.height) }.orEmpty(),
            frameRate = primaryVideo?.frameRate?.takeIf { it > 0f }?.let {
                if (kotlin.math.abs(it - it.toInt()) < 0.01f) "${it.toInt()} fps" else "%.2f fps".format(java.util.Locale.US, it)
            }.orEmpty(),
            videoCodec = videoLabel,
            audioCodec = audioLabel,
            container = formatLong.ifBlank { format },
            duration = duration,
            bitrate = CodecNames.bitrateLabel(summaryBitrate),
            hdr = hdrLabel,
            isLive = live
        )

        // Logos: playing audio + primary video, flags from every source.
        val logoVideos = listOfNotNull(primaryVideo) + videoTracks.filter { it !== primaryVideo }
        val logoAudioPool = probeAudios.ifEmpty { playerAudio }
        val logos = FormatDetector.logoFormats(logoVideos, logoAudioPool, selectedAudio = playingAudio)

        val allVideo = videoTracks.ifEmpty { listOfNotNull(primaryVideo) }
        val allAudio = audioTracks.ifEmpty { listOfNotNull(playingAudio) }

        val metadata = MediaMetadata(
            fileName = fileName,
            title = title,
            format = format,
            formatLong = formatLong,
            durationMs = duration,
            sizeBytes = size,
            bitrate = bitrate,
            isLive = live,
            videoTracks = allVideo,
            audioTracks = allAudio,
            subtitleTracks = subtitleTracks,
            specialFormats = FormatDetector.allFormats(allVideo, allAudio),
            sources = sources.flatMap { it.sources }.toSet()
        )
        return StreamInfo(metadata, summary, logos, probing)
    }

    private fun mergeVideoFlags(v: TrackInfo, probes: List<TrackInfo>): TrackInfo {
        val dv = probes.any { it.isDolbyVision }
        val h10p = probes.any { it.isHdr10Plus }
        val src = probes.firstOrNull { it.isDolbyVision } ?: probes.firstOrNull()
        return FormatDetector.enrichVideo(
            v.copy(
                isDolbyVision = v.isDolbyVision || dv,
                dvProfile = v.dvProfile.ifBlank { probes.firstOrNull { it.isDolbyVision }?.dvProfile.orEmpty() },
                isHdr10Plus = v.isHdr10Plus || h10p,
                colorTransfer = v.colorTransfer.ifBlank { src?.colorTransfer.orEmpty() },
                width = if (v.width > 0) v.width else src?.width ?: 0,
                height = if (v.height > 0) v.height else src?.height ?: 0,
                frameRate = if (v.frameRate > 0f) v.frameRate else src?.frameRate ?: 0f,
                bitrate = if (v.bitrate > 0) v.bitrate else src?.bitrate ?: 0L
            )
        )
    }

    private fun mergeAudioFlags(a: TrackInfo, probe: TrackInfo): TrackInfo = a.copy(
        isAtmos = a.isAtmos || probe.isAtmos,
        isDtsX = a.isDtsX || probe.isDtsX,
        isDtsHd = a.isDtsHd || probe.isDtsHd,
        isTrueHd = a.isTrueHd || probe.isTrueHd,
        isDolbyDigitalPlus = a.isDolbyDigitalPlus || probe.isDolbyDigitalPlus,
        profile = a.profile.ifBlank { probe.profile },
        bitrate = if (a.bitrate > 0) a.bitrate else probe.bitrate,
        channelLayout = a.channelLayout.ifBlank { probe.channelLayout }
    ).let { FormatDetector.enrichAudio(it) }
}

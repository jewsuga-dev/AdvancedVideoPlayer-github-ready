package com.advancedvideoplayer.data

/**
 * Pure format detection (no Android dependencies, unit-tested).
 *
 * Works on [TrackInfo]s from ANY source: FFprobe, MediaExtractor or ExoPlayer. Every source fills
 * the raw fields it knows (codec / MIME, profile, colour transfer, flags) and [enrichVideo] /
 * [enrichAudio] derive the special-format flags from them. Enriching is idempotent and only ever
 * turns flags ON, so a flag found by a richer source (e.g. FFprobe side data) is never lost.
 */
object FormatDetector {

    private val DTS_X_TEXT = Regex("""dts[\s:_\-.]?x\b|dtsx""", RegexOption.IGNORE_CASE)
    private val DTS_HD_TEXT = Regex("""dts[\s_\-.]?hd|\bhra\b|dts[\s_\-]?ma\b|\bma\b""", RegexOption.IGNORE_CASE)
    private val ATMOS_TEXT = Regex("""atmos|\bjoc\b""", RegexOption.IGNORE_CASE)
    private val HDR10PLUS_TEXT = Regex("""hdr10\s?\+|hdr10plus|hdr10\+""", RegexOption.IGNORE_CASE)
    private val DV_TEXT = Regex("""dolby[\s_\-]?vision|\bdovi\b|\bdv\b""", RegexOption.IGNORE_CASE)
    private val DV_CODECS = Regex("""\b(?:dvhe|dvh1|dav1|dvav|dva1)\.(\d{1,2})""", RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------------------------------------
    // Enrichment
    // ------------------------------------------------------------------------------------------

    /**
     * @param hint free text from the surrounding stream (file name / title) used only as a weak
     *             fallback where the bitstream itself does not tell (e.g. HDR10+ in a file
     *             whose probe could not read dynamic metadata).
     */
    fun enrichVideo(t: TrackInfo, hint: String = ""): TrackInfo {
        val fam = CodecNames.family(t.codec).ifEmpty { CodecNames.family(t.codecLong) }
        val transfer = t.colorTransfer.lowercase()
        val identity = "${t.codec} ${t.codecLong} ${t.profile} ${t.extra["codecs"].orEmpty()}"

        val dv = t.isDolbyVision || fam == "dolbyvision" || DV_CODECS.containsMatchIn(identity) ||
                identity.contains("dolby vision", ignoreCase = true) ||
                t.extra["dovi"] == "true" ||
                (t.title.isNotBlank() && DV_TEXT.containsMatchIn(t.title))

        val hdr10Plus = t.isHdr10Plus || t.extra["hdr10plus"] == "true" ||
                (t.title.isNotBlank() && HDR10PLUS_TEXT.containsMatchIn(t.title)) ||
                (hint.isNotBlank() && HDR10PLUS_TEXT.containsMatchIn(hint) && isPq(transfer))

        val hdr = t.isHdr || dv || hdr10Plus || isPq(transfer) || isHlg(transfer)

        val dvProfile = when {
            t.dvProfile.isNotBlank() && t.dvProfile != "Unknown" -> t.dvProfile
            dv -> DV_CODECS.find(identity)?.groupValues?.get(1)?.trimStart('0')?.ifEmpty { "0" } ?: ""
            else -> ""
        }
        return t.copy(isDolbyVision = dv, dvProfile = dvProfile, isHdr10Plus = hdr10Plus, isHdr = hdr)
    }

    fun enrichAudio(t: TrackInfo, hint: String = ""): TrackInfo {
        val fam = CodecNames.family(t.codec).ifEmpty { CodecNames.family(t.codecLong) }
        val mime = t.codec.lowercase()
        val profile = t.profile
        val label = "${t.title} ${t.extra["codecs"].orEmpty()}"
        val channelOk = t.channels == 0 || t.channels >= 6   // Atmos beds are always >= 5.1

        val isDts = fam == "dts"
        val dtsX = t.isDtsX || (isDts && (
                mime.contains("dts.uhd") ||
                        DTS_X_TEXT.containsMatchIn(profile) ||
                        DTS_X_TEXT.containsMatchIn(label) ||
                        (hint.isNotBlank() && DTS_X_TEXT.containsMatchIn(hint) && channelOk)
                ))
        val dtsHd = t.isDtsHd || (isDts && !dtsX && (
                mime.contains("dts.hd") ||
                        DTS_HD_TEXT.containsMatchIn(profile) ||
                        DTS_HD_TEXT.containsMatchIn(label)
                ))
        val trueHd = t.isTrueHd || fam == "truehd"
        val joc = mime.contains("eac3-joc") || t.extra["joc"] == "true"
        val eac3 = fam == "eac3"
        val atmos = t.isAtmos ||
                (eac3 && (joc || ATMOS_TEXT.containsMatchIn(profile) || ATMOS_TEXT.containsMatchIn(label) ||
                        (hint.isNotBlank() && ATMOS_TEXT.containsMatchIn(hint) && channelOk))) ||
                (trueHd && (ATMOS_TEXT.containsMatchIn(profile) || ATMOS_TEXT.containsMatchIn(label) ||
                        (hint.isNotBlank() && ATMOS_TEXT.containsMatchIn(hint) && channelOk)))

        return t.copy(
            isDtsX = dtsX,
            isDtsHd = dtsHd,
            isDts = isDts && !dtsX && !dtsHd,
            isTrueHd = trueHd,
            isAtmos = atmos,
            isDolbyDigitalPlus = t.isDolbyDigitalPlus || eac3,
            isAac = t.isAac || fam == "aac"
        )
    }

    fun isPq(transfer: String) =
        transfer.contains("smpte2084") || transfer.contains("st2084") || transfer == "pq"

    fun isHlg(transfer: String) = transfer.contains("arib-std-b67") || transfer.contains("hlg")

    // ------------------------------------------------------------------------------------------
    // Logo sets
    // ------------------------------------------------------------------------------------------

    /** Logos that belong to one (already enriched) video track. */
    fun videoFormats(t: TrackInfo): Set<SpecialFormat> {
        val out = linkedSetOf<SpecialFormat>()
        val transfer = t.colorTransfer.lowercase()
        when {
            t.isDolbyVision -> out += SpecialFormat.DOLBY_VISION
            t.isHdr10Plus -> out += SpecialFormat.HDR10_PLUS
            isPq(transfer) -> out += SpecialFormat.HDR10
            isHlg(transfer) -> out += SpecialFormat.HLG
        }
        when (CodecNames.family(t.codec).ifEmpty { CodecNames.family(t.codecLong) }) {
            "av1" -> out += SpecialFormat.AV1
            "hevc" -> out += SpecialFormat.HEVC
        }
        return out
    }

    /**
     * Logos that belong to one (already enriched) audio track. Only the most specific logo of a
     * family is returned (Atmos wins over TrueHD/DD+, DTS:X over DTS-HD over DTS).
     */
    fun audioFormats(t: TrackInfo): Set<SpecialFormat> {
        val out = linkedSetOf<SpecialFormat>()
        val fam = CodecNames.family(t.codec).ifEmpty { CodecNames.family(t.codecLong) }
        when {
            t.isDtsX -> out += SpecialFormat.DTS_X
            t.isDtsHd -> out += SpecialFormat.DTS_HD
            t.isDts -> out += SpecialFormat.DTS
            t.isAtmos -> out += SpecialFormat.DOLBY_ATMOS
            t.isTrueHd -> out += SpecialFormat.TRUEHD
            t.isDolbyDigitalPlus || fam == "eac3" -> out += SpecialFormat.DOLBY_DIGITAL_PLUS
            fam == "ac3" -> out += SpecialFormat.DOLBY_DIGITAL
        }
        return out
    }

    /**
     * The logo popup content for the stream that is actually playing.
     *
     * @param selectedAudio the audio track ExoPlayer is currently rendering, if known. Its format
     *        is combined with the richer FFprobe track that matches it (same codec family,
     *        channels, language) so e.g. a TrueHD track probed as "Atmos" shows Atmos.
     */
    fun logoFormats(
        videoTracks: List<TrackInfo>,
        audioTracks: List<TrackInfo>,
        selectedAudio: TrackInfo? = null
    ): List<SpecialFormat> {
        val out = linkedSetOf<SpecialFormat>()

        // Video: the selected (else first) track is the one that is shown.
        val primaryVideo = videoTracks.firstOrNull { it.selected } ?: videoTracks.firstOrNull()
        primaryVideo?.let { out += videoFormats(it) }
        // Dolby Vision / HDR10+ are only visible to a deeper probe, which may list the video
        // track separately from the player's – so honour those flags from any video track.
        videoTracks.forEach { v ->
            if (v.isDolbyVision) out += SpecialFormat.DOLBY_VISION
            if (v.isHdr10Plus) out += SpecialFormat.HDR10_PLUS
        }
        if (SpecialFormat.DOLBY_VISION in out || SpecialFormat.HDR10_PLUS in out) {
            // A more specific HDR flavour replaces the generic one.
            out -= SpecialFormat.HDR10
            if (SpecialFormat.DOLBY_VISION in out) out -= SpecialFormat.HDR10_PLUS
        }

        val audio: List<TrackInfo> = when {
            selectedAudio != null -> listOfNotNull(selectedAudio, matchAudio(audioTracks, selectedAudio))
            else -> listOfNotNull(audioTracks.firstOrNull { it.selected } ?: audioTracks.firstOrNull())
        }
        audio.forEach { out += audioFormats(it) }

        // Several sources may describe the same track at different precision: keep the most specific logo.
        if (SpecialFormat.DOLBY_ATMOS in out) { out -= SpecialFormat.TRUEHD; out -= SpecialFormat.DOLBY_DIGITAL_PLUS; out -= SpecialFormat.DOLBY_DIGITAL }
        if (SpecialFormat.DTS_X in out) { out -= SpecialFormat.DTS_HD; out -= SpecialFormat.DTS }
        if (SpecialFormat.DTS_HD in out) out -= SpecialFormat.DTS

        return sorted(out)
    }

    /** Everything detected anywhere in the file – used by the info panel, not the popup. */
    fun allFormats(videoTracks: List<TrackInfo>, audioTracks: List<TrackInfo>): List<SpecialFormat> {
        val out = linkedSetOf<SpecialFormat>()
        videoTracks.forEach { out += videoFormats(it) }
        audioTracks.forEach { out += audioFormats(it) }
        return sorted(out)
    }

    /** Finds the track in [candidates] describing the same stream as [ref]. */
    fun matchAudio(candidates: List<TrackInfo>, ref: TrackInfo): TrackInfo? {
        val refFam = CodecNames.family(ref.codec).ifEmpty { CodecNames.family(ref.codecLong) }
        val sameCodec = candidates.filter {
            val f = CodecNames.family(it.codec).ifEmpty { CodecNames.family(it.codecLong) }
            f.isNotEmpty() && f == refFam
        }
        if (sameCodec.isEmpty()) return null
        val sameChannels = sameCodec.filter { ref.channels == 0 || it.channels == 0 || it.channels == ref.channels }
        val pool = sameChannels.ifEmpty { return null }
        val sameLang = pool.filter {
            ref.language.isBlank() || it.language.isBlank() || langEq(it.language, ref.language)
        }
        return (sameLang.ifEmpty { pool }).firstOrNull()
    }

    private fun langEq(a: String, b: String) =
        a.equals(b, ignoreCase = true) || a.take(2).equals(b.take(2), ignoreCase = true)

    private val ORDER = listOf(
        SpecialFormat.DOLBY_VISION, SpecialFormat.HDR10_PLUS, SpecialFormat.HDR10, SpecialFormat.HLG,
        SpecialFormat.DOLBY_ATMOS, SpecialFormat.DTS_X, SpecialFormat.TRUEHD, SpecialFormat.DTS_HD,
        SpecialFormat.DOLBY_DIGITAL_PLUS, SpecialFormat.DTS, SpecialFormat.DOLBY_DIGITAL,
        SpecialFormat.AV1, SpecialFormat.HEVC
    )

    private fun sorted(set: Set<SpecialFormat>): List<SpecialFormat> =
        set.sortedBy { ORDER.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
}

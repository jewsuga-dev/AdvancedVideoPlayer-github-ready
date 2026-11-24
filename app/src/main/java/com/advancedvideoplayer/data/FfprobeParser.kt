package com.advancedvideoplayer.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns the JSON that `ffprobe -show_format -show_streams` prints (FFmpegKit's
 * `MediaInformation.getAllProperties()`) into [MediaMetadata]. Pure and tolerant: every field
 * is optional, wrong types are ignored, and a malformed stream entry only drops that entry.
 *
 * Fixes over the previous extractor:
 *  - Dolby Vision / HDR10+ are read from `side_data_list` (an array – it was read as a string
 *    before, so they were never detected),
 *  - HDR no longer means "10-bit" (10-bit SDR is common),
 *  - DTS:X / DTS-HD are decided from the codec profile, not from substrings like "x" or "ma"
 *    that also occur in "hexagonal" or "master",
 *  - audio channels come from `channels`, not from guessing on the layout string,
 *  - cover-art "video" streams (`attached_pic`) are ignored.
 */
object FfprobeParser {

    fun parse(root: JSONObject, fileName: String): MediaMetadata {
        val format = root.optJSONObject("format") ?: JSONObject()
        val formatTags = format.optJSONObject("tags")
        val formatTitle = formatTags?.optStringCI("title").orEmpty()
        val hint = "$fileName $formatTitle"

        val video = mutableListOf<TrackInfo>()
        val audio = mutableListOf<TrackInfo>()
        val subs = mutableListOf<TrackInfo>()

        val streams = root.optJSONArray("streams") ?: JSONArray()
        for (i in 0 until streams.length()) {
            val s = streams.optJSONObject(i) ?: continue
            try {
                when (s.optString("codec_type").lowercase()) {
                    "video" -> if (s.optJSONObject("disposition")?.optInt("attached_pic", 0) != 1) {
                        video += FormatDetector.enrichVideo(parseVideo(i, s), hint)
                    }
                    "audio" -> audio += FormatDetector.enrichAudio(parseAudio(i, s), hint)
                    "subtitle" -> subs += parseSubtitle(i, s)
                }
            } catch (_: Exception) { /* drop only this stream */ }
        }

        var durationMs = ((format.optString("duration").toDoubleOrNull() ?: 0.0) * 1000).toLong()
        if (durationMs <= 0) {
            // Matroska keeps durations per stream tag ("00:42:10.123000000")
            durationMs = streams.objects().mapNotNull { it.optJSONObject("tags")?.optStringCI("duration") }
                .mapNotNull { parseClock(it) }.maxOrNull() ?: 0L
        }

        val formatName = format.optString("format_name")
        return MediaMetadata(
            fileName = fileName,
            title = formatTitle,
            format = CodecNames.containerLabel(formatName).ifEmpty { formatName },
            formatLong = format.optString("format_long_name").ifEmpty { formatName },
            durationMs = durationMs,
            sizeBytes = format.optString("size").toLongOrNull() ?: 0L,
            bitrate = format.optString("bit_rate").toLongOrNull() ?: 0L,
            videoTracks = video,
            audioTracks = audio,
            subtitleTracks = subs,
            specialFormats = FormatDetector.allFormats(video, audio),
            sources = setOf(MetadataSource.FFPROBE)
        )
    }

    private fun parseVideo(index: Int, s: JSONObject): TrackInfo {
        val tags = s.optJSONObject("tags")
        val sideData = s.optJSONArray("side_data_list").objects().toList()
        val sideTypes = sideData.map { it.optString("side_data_type") }

        val dovi = sideData.firstOrNull { it.optString("side_data_type").contains("DOVI", true) ||
                it.optString("side_data_type").contains("Dolby Vision", true) }
        val hdr10Plus = sideTypes.any { it.contains("2094-40") || it.contains("HDR10+", true) || it.contains("HDR Dynamic Metadata", true) }

        val extra = buildMap<String, String> {
            if (dovi != null) put("dovi", "true")
            if (hdr10Plus) put("hdr10plus", "true")
            s.optString("codec_tag_string").takeIf { it.isNotBlank() && !it.startsWith("[") }?.let { put("codecs", it) }
            sideTypes.firstOrNull { it.contains("Mastering display", true) }?.let { put("masteringDisplay", "true") }
        }

        return TrackInfo(
            index = index,
            type = "video",
            codec = s.optString("codec_name").lowercase(),
            codecLong = s.optString("codec_long_name"),
            language = tags?.optStringCI("language").orEmpty().takeIf { it != "und" }.orEmpty(),
            title = tags?.optStringCI("title").orEmpty(),
            bitrate = s.optString("bit_rate").toLongOrNull() ?: tags?.optStringCI("BPS")?.toLongOrNull() ?: 0L,
            width = s.optInt("width", 0),
            height = s.optInt("height", 0),
            frameRate = parseFps(s.optString("avg_frame_rate")).takeIf { it > 0 } ?: parseFps(s.optString("r_frame_rate")),
            bitDepth = s.optString("bits_per_raw_sample").toIntOrNull() ?: 0,
            profile = s.optString("profile"),
            level = s.optString("level"),
            pixFmt = s.optString("pix_fmt"),
            colorSpace = s.optString("color_space"),
            colorTransfer = s.optString("color_transfer"),
            colorPrimaries = s.optString("color_primaries"),
            isDolbyVision = dovi != null,
            dvProfile = dovi?.let { it.opt("dv_profile")?.toString().orEmpty() }.orEmpty(),
            isHdr10Plus = hdr10Plus,
            extra = extra
        )
    }

    private fun parseAudio(index: Int, s: JSONObject): TrackInfo {
        val tags = s.optJSONObject("tags")
        return TrackInfo(
            index = index,
            type = "audio",
            codec = s.optString("codec_name").lowercase(),
            codecLong = s.optString("codec_long_name"),
            language = tags?.optStringCI("language").orEmpty().takeIf { it != "und" }.orEmpty(),
            title = tags?.optStringCI("title").orEmpty(),
            bitrate = s.optString("bit_rate").toLongOrNull() ?: tags?.optStringCI("BPS")?.toLongOrNull() ?: 0L,
            channels = s.optInt("channels", 0),
            sampleRate = s.optString("sample_rate").toIntOrNull() ?: 0,
            channelLayout = s.optString("channel_layout"),
            bitDepth = s.optString("bits_per_raw_sample").toIntOrNull()
                ?: s.optInt("bits_per_sample", 0),
            profile = s.optString("profile"),
            extra = buildMap<String, String> {
                s.optString("codec_tag_string").takeIf { it.isNotBlank() && !it.startsWith("[") }?.let { put("codecs", it) }
            }
        )
    }

    private fun parseSubtitle(index: Int, s: JSONObject): TrackInfo {
        val tags = s.optJSONObject("tags")
        return TrackInfo(
            index = index,
            type = "subtitle",
            codec = s.optString("codec_name").lowercase(),
            codecLong = s.optString("codec_long_name"),
            language = tags?.optStringCI("language").orEmpty().takeIf { it != "und" }.orEmpty(),
            title = tags?.optStringCI("title").orEmpty()
        )
    }

    fun parseFps(rate: String): Float {
        if (rate.isBlank()) return 0f
        return try {
            if (rate.contains("/")) {
                val (n, d) = rate.split("/").map { it.trim().toFloat() }
                if (d == 0f) 0f else n / d
            } else rate.toFloat()
        } catch (_: Exception) { 0f }
    }

    private fun parseClock(text: String): Long? {
        val m = Regex("""(\d+):(\d{2}):(\d{2})(?:\.(\d+))?""").find(text) ?: return null
        val (h, mi, s, frac) = m.destructured
        val ms = frac.take(3).padEnd(3, '0').toLongOrNull() ?: 0L
        return ((h.toLong() * 60 + mi.toLong()) * 60 + s.toLong()) * 1000 + ms
    }

    /** ffprobe writes tag names in the file's own case ("DURATION", "Title", "language"). */
    private fun JSONObject.optStringCI(key: String): String? {
        keys().forEach { k -> if (k.equals(key, ignoreCase = true)) return optString(k) }
        return null
    }

    private fun JSONArray?.objects(): Sequence<JSONObject> =
        if (this == null) emptySequence() else (0 until length()).asSequence().mapNotNull { optJSONObject(it) }
}

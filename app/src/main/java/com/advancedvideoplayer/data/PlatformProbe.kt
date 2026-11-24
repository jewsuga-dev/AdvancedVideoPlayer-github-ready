package com.advancedvideoplayer.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * Metadata from Android's own framework (MediaMetadataRetriever + MediaExtractor). No third-party
 * code, so it works even when the optional FFprobe library is missing, and it never touches
 * playback. Every step is isolated: a failing retriever does not stop the extractor.
 */
object PlatformProbe {

    private const val TIMEOUT_MS = 12_000L

    suspend fun probe(context: Context, uri: Uri, fileName: String): MediaMetadata? =
        Blocking.call(TIMEOUT_MS, "PlatformProbe") { probeBlocking(context, uri, fileName) }

    private fun probeBlocking(context: Context, uri: Uri, fileName: String): MediaMetadata? {
        var title = ""
        var duration = 0L
        var bitrate = 0L
        var container = ""

        val retriever = MediaMetadataRetriever()
        try {
            setSource(retriever, context, uri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty()
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            container = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
        } catch (t: Throwable) {
            android.util.Log.w("PlatformProbe", "Retriever failed: ${t.message}")
        } finally {
            try { retriever.release() } catch (_: Throwable) { }
        }

        val video = mutableListOf<TrackInfo>()
        val audio = mutableListOf<TrackInfo>()
        val subs = mutableListOf<TrackInfo>()
        val extractor = MediaExtractor()
        try {
            if (UriInfo.isNetwork(uri)) extractor.setDataSource(uri.toString(), emptyMap())
            else extractor.setDataSource(context, uri, null)

            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                val lang = f.getString(MediaFormat.KEY_LANGUAGE).orEmpty().takeIf { it != "und" }.orEmpty()
                val trackBitrate = f.intOrNull(MediaFormat.KEY_BIT_RATE)?.toLong() ?: 0L
                if (duration <= 0L) f.longOrNull(MediaFormat.KEY_DURATION)?.let { duration = it / 1000 }
                when {
                    mime.startsWith("video/") -> {
                        val transfer = when (f.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)) {
                            MediaFormat.COLOR_TRANSFER_ST2084 -> "smpte2084"
                            MediaFormat.COLOR_TRANSFER_HLG -> "arib-std-b67"
                            else -> ""
                        }
                        video += FormatDetector.enrichVideo(TrackInfo(
                            index = i, type = "video", codec = mime, language = lang, bitrate = trackBitrate,
                            width = f.intOrNull(MediaFormat.KEY_WIDTH) ?: 0,
                            height = f.intOrNull(MediaFormat.KEY_HEIGHT) ?: 0,
                            frameRate = f.floatOrNull(MediaFormat.KEY_FRAME_RATE) ?: 0f,
                            colorTransfer = transfer
                        ), hint = fileName)
                    }
                    mime.startsWith("audio/") -> audio += FormatDetector.enrichAudio(TrackInfo(
                        index = i, type = "audio", codec = mime, language = lang, bitrate = trackBitrate,
                        channels = f.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 0,
                        sampleRate = f.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 0
                    ), hint = fileName)
                    mime.startsWith("text/") || mime.startsWith("application/") ->
                        subs += TrackInfo(index = i, type = "subtitle", codec = mime, language = lang)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("PlatformProbe", "Extractor failed: ${t.message}")
        } finally {
            try { extractor.release() } catch (_: Throwable) { }
        }

        if (title.isEmpty() && duration <= 0 && bitrate <= 0 && video.isEmpty() && audio.isEmpty()) return null
        return MediaMetadata(
            fileName = fileName,
            title = title,
            format = CodecNames.containerLabel(container),
            durationMs = duration,
            bitrate = bitrate,
            videoTracks = video,
            audioTracks = audio,
            subtitleTracks = subs,
            specialFormats = FormatDetector.allFormats(video, audio),
            sources = setOf(MetadataSource.PLATFORM)
        )
    }

    private fun setSource(r: MediaMetadataRetriever, context: Context, uri: Uri) {
        if (UriInfo.isNetwork(uri)) r.setDataSource(uri.toString(), HashMap<String, String>())
        else r.setDataSource(context, uri)
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) try { getInteger(key) } catch (_: Throwable) { null } else null

    private fun MediaFormat.longOrNull(key: String): Long? =
        if (containsKey(key)) try { getLong(key) } catch (_: Throwable) { null } else null

    private fun MediaFormat.floatOrNull(key: String): Float? {
        if (!containsKey(key)) return null
        return try { getFloat(key) } catch (_: Throwable) {
            try { getInteger(key).toFloat() } catch (_: Throwable) { null }
        }
    }
}

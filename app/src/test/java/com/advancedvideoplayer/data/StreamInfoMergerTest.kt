package com.advancedvideoplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamInfoMergerTest {

    @Test fun player_only_info_is_enough() {
        val player = MediaMetadata(
            videoTracks = listOf(TrackInfo(0, "video", "video/avc", width = 1920, height = 1080, selected = true, supported = true)),
            audioTracks = listOf(TrackInfo(1, "audio", "audio/mp4a-latm", channels = 2, selected = true, supported = true)),
            durationMs = 60_000, bitrate = 4_200_000, sources = setOf(MetadataSource.PLAYER)
        )
        val info = StreamInfoMerger.merge("clip.mp4", 0, null, null, player, probing = false)
        assertEquals("clip.mp4", info.summary.title)
        assertEquals("1920×1080 (1080p)", info.summary.resolution)
        assertEquals("H.264 / AVC", info.summary.videoCodec)
        assertEquals("AAC Stereo", info.summary.audioCodec)
        assertEquals("4.2 Mbps", info.summary.bitrate)
        assertEquals(60_000L, info.summary.duration)
    }

    @Test fun nothing_available_gives_empty_but_valid_info() {
        val info = StreamInfoMerger.merge("x.bin", 0, null, null, null, probing = true)
        assertEquals("x.bin", info.summary.title)
        assertTrue(info.logos.isEmpty()); assertTrue(info.probing)
    }

    @Test fun probe_dolby_vision_reaches_logos_of_player_stream() {
        val player = MediaMetadata(
            videoTracks = listOf(TrackInfo(0, "video", "video/hevc", width = 3840, height = 2160, selected = true, supported = true)),
            sources = setOf(MetadataSource.PLAYER)
        )
        val probe = MediaMetadata(
            videoTracks = listOf(FormatDetector.enrichVideo(TrackInfo(0, "video", "hevc", extra = mapOf("dovi" to "true")))),
            sources = setOf(MetadataSource.FFPROBE)
        )
        val info = StreamInfoMerger.merge("a.mkv", 0, probe, null, player, false)
        assertTrue(SpecialFormat.DOLBY_VISION in info.logos)
    }
}

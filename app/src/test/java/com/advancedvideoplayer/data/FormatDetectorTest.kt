package com.advancedvideoplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatDetectorTest {

    private fun audio(codec: String, profile: String = "", title: String = "", channels: Int = 0, layout: String = "") =
        FormatDetector.enrichAudio(TrackInfo(0, "audio", codec, profile = profile, title = title, channels = channels, channelLayout = layout))

    private fun video(codec: String, transfer: String = "", extra: Map<String, String> = emptyMap(), pixFmt: String = "") =
        FormatDetector.enrichVideo(TrackInfo(0, "video", codec, colorTransfer = transfer, extra = extra, pixFmt = pixFmt))

    @Test fun dtsX_from_profile() {
        val t = audio("dts", profile = "DTS-HD MA + DTS:X")
        assertTrue(t.isDtsX); assertFalse(t.isDtsHd)
        assertEquals(setOf(SpecialFormat.DTS_X), FormatDetector.audioFormats(t))
    }

    @Test fun dtsX_from_android_mime() {
        assertTrue(audio("audio/vnd.dts.uhd;profile=p2").isDtsX)
    }

    @Test fun hexagonal_layout_is_not_dtsX() {
        val t = audio("dts", profile = "DTS", layout = "hexagonal")
        assertFalse(t.isDtsX); assertTrue(t.isDts)
    }

    @Test fun dtsHd_and_plain_dts() {
        assertTrue(audio("dts", profile = "DTS-HD MA").isDtsHd)
        assertTrue(audio("audio/vnd.dts.hd").isDtsHd)
        val core = audio("dts", profile = "DTS")
        assertTrue(core.isDts); assertFalse(core.isDtsHd)
    }

    @Test fun atmos_from_joc_mime() {
        val t = audio("audio/eac3-joc")
        assertTrue(t.isAtmos)
        assertEquals(setOf(SpecialFormat.DOLBY_ATMOS), FormatDetector.audioFormats(t))
    }

    @Test fun trueHd_plain_and_atmos_hint() {
        assertEquals(setOf(SpecialFormat.TRUEHD), FormatDetector.audioFormats(audio("truehd", channels = 8)))
        assertTrue(audio("truehd", title = "TrueHD Atmos 7.1", channels = 8).isAtmos)
    }

    @Test fun ddp_and_dd() {
        assertEquals(setOf(SpecialFormat.DOLBY_DIGITAL_PLUS), FormatDetector.audioFormats(audio("eac3")))
        assertEquals(setOf(SpecialFormat.DOLBY_DIGITAL), FormatDetector.audioFormats(audio("ac3")))
        assertEquals(emptySet<SpecialFormat>(), FormatDetector.audioFormats(audio("aac")))
    }

    @Test fun dolbyVision_from_sidedata_and_codecs() {
        assertEquals(
            setOf(SpecialFormat.DOLBY_VISION, SpecialFormat.HEVC),
            FormatDetector.videoFormats(video("hevc", extra = mapOf("dovi" to "true")))
        )
        val dv = FormatDetector.enrichVideo(TrackInfo(0, "video", "video/dolby-vision", extra = mapOf("codecs" to "dvhe.08.06")))
        assertTrue(dv.isDolbyVision); assertEquals("8", dv.dvProfile)
    }

    @Test fun hdr10_hlg_and_10bit_sdr() {
        assertTrue(FormatDetector.videoFormats(video("hevc", transfer = "smpte2084")).contains(SpecialFormat.HDR10))
        assertTrue(FormatDetector.videoFormats(video("hevc", transfer = "arib-std-b67")).contains(SpecialFormat.HLG))
        val sdr10 = video("hevc", transfer = "bt709", pixFmt = "yuv420p10le")
        assertFalse(sdr10.isHdr)
    }

    @Test fun logos_follow_selected_audio_track() {
        val dts = audio("dts", profile = "DTS-HD MA").copy(language = "en", channels = 8)
        val ac3 = audio("ac3").copy(language = "en", channels = 6, selected = true)
        val logos = FormatDetector.logoFormats(emptyList(), listOf(dts, ac3), selectedAudio = ac3)
        assertEquals(listOf(SpecialFormat.DOLBY_DIGITAL), logos)
    }

    @Test fun probe_flags_merge_into_selected_player_track() {
        val player = audio("audio/true-hd", channels = 8).copy(selected = true)
        val probe = audio("truehd", title = "TrueHD Atmos", channels = 8)
        val logos = FormatDetector.logoFormats(emptyList(), listOf(probe), selectedAudio = player)
        assertTrue(SpecialFormat.DOLBY_ATMOS in logos)
    }
}

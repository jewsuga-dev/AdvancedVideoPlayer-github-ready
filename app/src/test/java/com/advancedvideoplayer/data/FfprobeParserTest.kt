package com.advancedvideoplayer.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FfprobeParserTest {

    private val json = """
    {"streams":[
      {"codec_type":"video","codec_name":"hevc","width":3840,"height":2160,"avg_frame_rate":"24000/1001",
       "color_transfer":"smpte2084","pix_fmt":"yuv420p10le",
       "side_data_list":[{"side_data_type":"DOVI configuration record","dv_profile":8}]},
      {"codec_type":"video","codec_name":"mjpeg","width":600,"height":600,"disposition":{"attached_pic":1}},
      {"codec_type":"audio","codec_name":"eac3","channels":6,"sample_rate":"48000",
       "profile":"Dolby Digital Plus + Dolby Atmos","tags":{"language":"eng"}},
      {"codec_type":"subtitle","codec_name":"subrip","tags":{"language":"eng"}}
    ],
    "format":{"format_name":"matroska,webm","duration":"7261.5","size":"123","bit_rate":"8000000","tags":{"TITLE":"My Movie"}}}
    """

    @Test fun parses_summary_fields() {
        val m = FfprobeParser.parse(JSONObject(json), "movie.mkv")
        assertEquals("My Movie", m.title)
        assertEquals(7_261_500L, m.durationMs)
        assertEquals(8_000_000L, m.bitrate)
        assertEquals("Matroska (MKV)", m.format)
        assertEquals(1, m.videoTracks.size)   // cover art ignored
        assertEquals(1, m.audioTracks.size)
        assertEquals(1, m.subtitleTracks.size)
        assertEquals(23.976f, m.videoTracks[0].frameRate, 0.01f)
    }

    @Test fun detects_dv_hdr_atmos() {
        val m = FfprobeParser.parse(JSONObject(json), "movie.mkv")
        val v = m.videoTracks[0]
        assertTrue(v.isDolbyVision); assertEquals("8", v.dvProfile); assertTrue(v.isHdr)
        assertTrue(m.audioTracks[0].isAtmos)
        assertTrue(SpecialFormat.DOLBY_VISION in m.specialFormats)
        assertTrue(SpecialFormat.DOLBY_ATMOS in m.specialFormats)
    }

    @Test fun empty_json_does_not_throw() {
        val m = FfprobeParser.parse(JSONObject("{}"), "x")
        assertTrue(m.videoTracks.isEmpty() && m.audioTracks.isEmpty())
    }
}

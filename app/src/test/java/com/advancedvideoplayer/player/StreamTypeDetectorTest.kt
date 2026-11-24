package com.advancedvideoplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamTypeDetectorTest {

    @Test fun by_url() {
        assertEquals(StreamKind.HLS, StreamTypeDetector.fromUrl("https://h/live/index.m3u8?token=1"))
        assertEquals(StreamKind.DASH, StreamTypeDetector.fromUrl("https://h/a/manifest.mpd"))
        assertEquals(StreamKind.SMOOTH, StreamTypeDetector.fromUrl("https://h/a.ism/manifest"))
        assertEquals(StreamKind.RTSP, StreamTypeDetector.fromUrl("rtsp://cam/stream"))
        assertEquals(StreamKind.PROGRESSIVE, StreamTypeDetector.fromUrl("https://h/v.mp4?x=1"))
        assertNull(StreamTypeDetector.fromUrl("https://h/live?id=3"))
        assertNull(StreamTypeDetector.fromUrl("content://media/42"))
    }

    @Test fun by_content_type() {
        assertEquals(StreamKind.HLS, StreamTypeDetector.fromContentType("application/vnd.apple.mpegurl; charset=utf-8"))
        assertEquals(StreamKind.DASH, StreamTypeDetector.fromContentType("application/dash+xml"))
        assertNull(StreamTypeDetector.fromContentType("application/octet-stream"))
        assertNull(StreamTypeDetector.fromContentType(null))
    }

    @Test fun by_body() {
        assertEquals(StreamKind.HLS, StreamTypeDetector.fromBody("#EXTM3U\n#EXT-X-VERSION:3".toByteArray()))
        assertEquals(StreamKind.DASH, StreamTypeDetector.fromBody("<?xml version=\"1.0\"?><MPD xmlns=\"x\">".toByteArray()))
        val ts = ByteArray(400).also { it[0] = 0x47; it[188] = 0x47; it[376] = 0x47 }
        assertEquals(StreamKind.PROGRESSIVE, StreamTypeDetector.fromBody(ts))
        assertNull(StreamTypeDetector.fromBody("<html>nope</html>".toByteArray()))
    }
}

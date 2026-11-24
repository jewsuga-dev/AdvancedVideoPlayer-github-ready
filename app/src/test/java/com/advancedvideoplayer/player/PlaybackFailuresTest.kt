package com.advancedvideoplayer.player

import androidx.media3.common.PlaybackException
import com.advancedvideoplayer.player.PlaybackFailure.Category as C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFailuresTest {
    @Test fun http_status_mapping() {
        val bad = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        assertEquals(C.NOT_FOUND, PlaybackFailures.classify(bad, httpStatus = 404).category)
        assertEquals(C.ACCESS_DENIED, PlaybackFailures.classify(bad, httpStatus = 403).category)
        assertTrue(PlaybackFailures.classify(bad, httpStatus = 503).autoRetry)
        assertFalse(PlaybackFailures.classify(bad, httpStatus = 404).autoRetry)
    }

    @Test fun network_is_retried_but_format_problems_are_not() {
        assertTrue(PlaybackFailures.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED).autoRetry)
        val unsupported = PlaybackFailures.classify(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)
        assertEquals(C.UNSUPPORTED_FORMAT, unsupported.category)
        assertFalse(unsupported.autoRetry)
        assertEquals(C.MALFORMED, PlaybackFailures.classify(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED).category)
    }

    @Test fun decoder_codes_allow_track_dropping() {
        assertTrue(PlaybackFailures.isRendererDecodeProblem(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertFalse(PlaybackFailures.isRendererDecodeProblem(PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        assertTrue(PlaybackFailures.isFormatDetectionProblem(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
    }

    @Test fun unknown_code_still_gives_message() {
        val f = PlaybackFailures.classify(9999)
        assertEquals(C.UNKNOWN, f.category); assertTrue(f.detail.isNotBlank())
    }
}

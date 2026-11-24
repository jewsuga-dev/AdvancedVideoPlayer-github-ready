package com.advancedvideoplayer.player

import androidx.media3.common.PlaybackException

/**
 * A playback problem translated into something a person can act on.
 * Produced by [PlaybackFailures.classify]; the UI only ever shows [title] / [detail].
 */
data class PlaybackFailure(
    val category: Category,
    val title: String,
    val detail: String,
    val errorCode: Int = PlaybackException.ERROR_CODE_UNSPECIFIED,
    val codeName: String = "",
    /** Transient (network / 5xx / timeout): the player may retry by itself with back-off. */
    val autoRetry: Boolean = false
) {
    enum class Category {
        NETWORK, TIMEOUT, NOT_FOUND, ACCESS_DENIED, UNSUPPORTED_FORMAT, MALFORMED, DECODER, AUDIO_OUTPUT, DRM, UNKNOWN
    }
}

/** Pure mapping from Media3 error codes to [PlaybackFailure] (no Android classes → unit-tested). */
object PlaybackFailures {

    /**
     * @param httpStatus HTTP status when the failure was a bad HTTP response (from the cause chain), else null.
     * @param formatLabel codec/MIME of the failing renderer when known, e.g. "audio/true-hd".
     */
    fun classify(
        code: Int,
        codeName: String = "",
        httpStatus: Int? = null,
        formatLabel: String? = null
    ): PlaybackFailure {
        val what = formatLabel?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        fun f(
            c: PlaybackFailure.Category,
            title: String,
            detail: String,
            retry: Boolean = false
        ) = PlaybackFailure(c, title, detail, code, codeName, retry)

        return when (code) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> when (httpStatus) {
                401, 403 -> f(PlaybackFailure.Category.ACCESS_DENIED, "Access denied",
                    "The server refused the request (HTTP $httpStatus). The link may have expired or needs a login.")
                404, 410 -> f(PlaybackFailure.Category.NOT_FOUND, "Stream not found",
                    "The server says this stream does not exist (HTTP $httpStatus).")
                408, 429, 502, 503, 504 -> f(PlaybackFailure.Category.NETWORK, "Server busy",
                    "The server is temporarily unavailable (HTTP $httpStatus).", retry = true)
                in 500..599 -> f(PlaybackFailure.Category.NETWORK, "Server error",
                    "The server failed to deliver the stream (HTTP $httpStatus).", retry = true)
                else -> f(PlaybackFailure.Category.NETWORK, "Server error",
                    "The server answered with an unexpected status${httpStatus?.let { " (HTTP $it)" } ?: ""}.")
            }
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                f(PlaybackFailure.Category.NETWORK, "Network problem",
                    "Could not connect to the stream. Check your internet connection.", retry = true)
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackException.ERROR_CODE_TIMEOUT ->
                f(PlaybackFailure.Category.TIMEOUT, "Connection timed out",
                    "The stream did not respond in time.", retry = true)
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED ->
                f(PlaybackFailure.Category.NETWORK, "Read error",
                    "The stream could not be read; it may have been interrupted.", retry = true)
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                f(PlaybackFailure.Category.NOT_FOUND, "File not found",
                    "The file no longer exists or was moved.")
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                f(PlaybackFailure.Category.ACCESS_DENIED, "No permission",
                    "The app is not allowed to read this file. Re-open it with the file picker.")
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
                f(PlaybackFailure.Category.ACCESS_DENIED, "Insecure link blocked",
                    "Plain-HTTP traffic is blocked on this device. Try the https:// version of the link.")
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
                f(PlaybackFailure.Category.UNSUPPORTED_FORMAT, "Not a media stream",
                    "The link returned a web page or data that is not video or audio.")
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ->
                f(PlaybackFailure.Category.MALFORMED, "Seek failed",
                    "The requested position is outside the stream.")
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                f(PlaybackFailure.Category.UNSUPPORTED_FORMAT, "Unsupported format",
                    "This container or stream type is not supported on this device.")
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                f(PlaybackFailure.Category.MALFORMED, "Damaged or invalid stream",
                    "The stream data is corrupt or incomplete and could not be read.")
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
                f(PlaybackFailure.Category.DECODER, "Decoder unavailable",
                    "This device could not start a decoder$what.")
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                f(PlaybackFailure.Category.DECODER, "Codec not supported",
                    "This device cannot decode$what.")
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                f(PlaybackFailure.Category.DECODER, "Format too demanding",
                    "This stream$what exceeds what the device can decode (resolution, frame rate or bit depth).")
            PlaybackException.ERROR_CODE_DECODING_FAILED ->
                f(PlaybackFailure.Category.DECODER, "Decoding failed",
                    "The decoder stopped while playing$what. The stream may be damaged.")
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ->
                f(PlaybackFailure.Category.AUDIO_OUTPUT, "Audio output problem",
                    "The audio output rejected this stream$what.")
            in 6000..6999 ->
                f(PlaybackFailure.Category.DRM, "Protected content",
                    "This stream is DRM-protected and cannot be played here.")
            else ->
                f(PlaybackFailure.Category.UNKNOWN, "Playback error",
                    "Something went wrong while playing this stream.")
        }
    }

    /** Codes for which dropping one track type (audio or video) and carrying on makes sense. */
    fun isRendererDecodeProblem(code: Int): Boolean = when (code) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> true
        else -> false
    }

    /** Codes where re-trying with a different stream type (HLS ↔ progressive) can help. */
    fun isFormatDetectionProblem(code: Int): Boolean = when (code) {
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> true
        else -> false
    }

    fun isTransient(code: Int, httpStatus: Int?): Boolean = classify(code, "", httpStatus).autoRetry
}

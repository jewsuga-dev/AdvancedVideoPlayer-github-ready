package com.advancedvideoplayer.player

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves the [StreamKind] of a URI. Local files and URLs with a recognisable extension are
 * answered instantly without touching the network; only ambiguous http(s) URLs
 * (e.g. "https://host/live?id=7") are probed with one short ranged request.
 *
 * Never throws: on any failure the answer is [StreamKind.PROGRESSIVE], i.e. "let ExoPlayer sniff".
 */
object StreamSniffer {

    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 6_000
    private const val SNIFF_BYTES = 1024

    data class Result(val kind: StreamKind, val contentType: String? = null, val sniffed: Boolean = false)

    suspend fun detect(uri: Uri, userAgent: String): Result {
        val url = uri.toString()
        StreamTypeDetector.fromUrl(url)?.let { return Result(it) }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Result(StreamKind.PROGRESSIVE)

        return withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Range", "bytes=0-${SNIFF_BYTES - 1}")
                    setRequestProperty("Accept", "*/*")
                }
                val contentType = conn.contentType
                StreamTypeDetector.fromContentType(contentType)?.let {
                    return@withContext Result(it, contentType, sniffed = true)
                }
                // Generic type (octet-stream, text/plain, missing) → look at the body.
                val buf = ByteArray(SNIFF_BYTES)
                var read = 0
                conn.inputStream.use { input ->
                    while (read < buf.size) {
                        val n = input.read(buf, read, buf.size - read)
                        if (n <= 0) break
                        read += n
                    }
                }
                val kind = StreamTypeDetector.fromBody(buf, read) ?: StreamKind.PROGRESSIVE
                Result(kind, contentType, sniffed = true)
            } catch (e: Exception) {
                // Includes cancellation-unrelated IO errors; ExoPlayer will report the real problem.
                Result(StreamKind.PROGRESSIVE)
            } finally {
                try { conn?.disconnect() } catch (_: Exception) { }
            }
        }
    }
}

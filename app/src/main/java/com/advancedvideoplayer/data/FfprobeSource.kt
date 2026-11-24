package com.advancedvideoplayer.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * Optional deep probe through FFmpegKit's FFprobe. Accessed by reflection so the app compiles,
 * installs and plays whether or not an FFmpegKit build is bundled (the original Maven artifact
 * `com.arthenica:ffmpeg-kit-full:6.0-2` has been removed from Maven Central).
 *
 * Add any FFmpegKit AAR that keeps the `com.arthenica.ffmpegkit` package and this source
 * starts contributing automatically; otherwise [probe] returns null and the player-side and
 * platform metadata are used.
 *
 * Content URIs are read in place through FFmpegKit's SAF bridge – no copy of the video is made.
 * Only if that bridge is missing, the first [HEAD_COPY_BYTES] are copied to a temp file that is
 * deleted in a `finally`.
 */
object FfprobeSource {

    private const val TAG = "FfprobeSource"
    private const val TIMEOUT_MS = 20_000L
    private const val HEAD_COPY_BYTES = 24L * 1024 * 1024

    private const val PROBE_CLASS = "com.arthenica.ffmpegkit.FFprobeKit"
    private const val CONFIG_CLASS = "com.arthenica.ffmpegkit.FFmpegKitConfig"
    private const val KIT_CLASS = "com.arthenica.ffmpegkit.FFmpegKit"

    val isAvailable: Boolean by lazy {
        try { Class.forName(PROBE_CLASS); true } catch (_: Throwable) { false }
    }

    suspend fun probe(context: Context, uri: Uri, fileName: String): MediaMetadata? {
        if (!isAvailable) return null
        return Blocking.call(TIMEOUT_MS, TAG, onAbandon = { cancelNative() }) {
            probeBlocking(context, uri, fileName)
        }
    }

    private fun probeBlocking(context: Context, uri: Uri, fileName: String): MediaMetadata? {
        val scheme = uri.scheme?.lowercase()
        return when {
            UriInfo.isNetwork(uri) -> runProbe(uri.toString())?.let { FfprobeParser.parse(it, fileName) }
            scheme == "file" -> uri.path?.let { runProbe(it) }?.let { FfprobeParser.parse(it, fileName) }
            else -> {
                val saf = safPath(context, uri)
                val json = if (saf != null) runProbe(saf) else null
                (json ?: TempFiles.withHeadCopy(context, uri, HEAD_COPY_BYTES) { runProbe(it.absolutePath) })
                    ?.let { FfprobeParser.parse(it, fileName) }
            }
        }
    }

    /** `FFmpegKitConfig.getSafParameterForRead(context, uri)` → "saf:12.mkv" */
    private fun safPath(context: Context, uri: Uri): String? = try {
        val cfg = Class.forName(CONFIG_CLASS)
        cfg.getMethod("getSafParameterForRead", Context::class.java, Uri::class.java)
            .invoke(null, context, uri) as? String
    } catch (t: Throwable) {
        android.util.Log.i(TAG, "SAF bridge unavailable: ${t.javaClass.simpleName}")
        null
    }

    /** `FFprobeKit.getMediaInformation(path).getMediaInformation().getAllProperties()` */
    private fun runProbe(path: String): JSONObject? = try {
        val probe = Class.forName(PROBE_CLASS)
        val session = probe.getMethod("getMediaInformation", String::class.java).invoke(null, path)
            ?: return null
        val info = session.javaClass.getMethod("getMediaInformation").invoke(session) ?: return null
        info.javaClass.getMethod("getAllProperties").invoke(info) as? JSONObject
    } catch (t: Throwable) {
        android.util.Log.w(TAG, "FFprobe failed: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    private fun cancelNative() {
        try {
            Class.forName(KIT_CLASS).getMethod("cancel").invoke(null)
        } catch (_: Throwable) { }
    }
}

package com.advancedvideoplayer.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Every temporary file the app creates goes through here so none can leak:
 *  - [sweep] removes leftovers of earlier runs (crash, process death) at start-up and exit,
 *  - [withHeadCopy] always deletes its file in `finally`,
 *  - [deleteAll] is called when playback screen is disposed.
 *
 * Nothing in the app copies a whole media file any more – the old extractor copied the *entire*
 * video into the cache just to probe it, which could fill the disk and hang for minutes.
 */
object TempFiles {

    private const val PREFIX = "avp_probe_"
    private val live = ConcurrentHashMap.newKeySet<File>()

    /** Deletes stale probe files (and the legacy "ffprobe_temp_*" ones of earlier versions). */
    fun sweep(context: Context) {
        try {
            context.cacheDir.listFiles()?.forEach { f ->
                if (f.isFile && (f.name.startsWith(PREFIX) || f.name.startsWith("ffprobe_temp_"))) f.delete()
            }
        } catch (_: Throwable) { }
    }

    fun deleteAll() {
        live.toList().forEach { runCatching { it.delete() }; live.remove(it) }
    }

    /**
     * Copies at most [maxBytes] from the start of [uri] into a temp file, runs [block] with it and
     * deletes it again – whatever happens. Enough for container headers (MP4 moov-at-front, MKV, TS).
     */
    inline fun <T> withHeadCopy(context: Context, uri: Uri, maxBytes: Long, block: (File) -> T): T? {
        val file = createHeadCopy(context, uri, maxBytes) ?: return null
        try {
            return block(file)
        } finally {
            release(file)
        }
    }

    @PublishedApi
    internal fun createHeadCopy(context: Context, uri: Uri, maxBytes: Long): File? {
        val file = File(context.cacheDir, PREFIX + System.nanoTime())
        live += file
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (total < maxBytes) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), maxBytes - total).toInt())
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        total += n
                    }
                }
                file
            } ?: run { release(file); null }
        } catch (t: Throwable) {
            release(file)
            null
        }
    }

    @PublishedApi
    internal fun release(file: File) {
        try { file.delete() } catch (_: Throwable) { }
        live.remove(file)
    }
}

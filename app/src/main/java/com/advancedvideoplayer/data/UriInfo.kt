package com.advancedvideoplayer.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.net.URLDecoder

/** Name / size / locality of a media URI. Never throws. */
object UriInfo {

    fun isNetwork(uri: Uri): Boolean = when (uri.scheme?.lowercase()) {
        "http", "https", "rtsp", "rtsps", "rtmp" -> true
        else -> false
    }

    fun displayName(context: Context, uri: Uri): String {
        var name: String? = null
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0) name = c.getString(i)
                    }
                }
            } catch (_: Throwable) { }
        }
        if (name.isNullOrBlank()) {
            val seg = uri.lastPathSegment?.substringAfterLast('/')?.substringBefore('?')
            name = seg?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        }
        if (name.isNullOrBlank()) name = uri.host
        return name?.takeIf { it.isNotBlank() } ?: "Unknown"
    }

    fun sizeBytes(context: Context, uri: Uri): Long {
        if (uri.scheme == "file") return uri.path?.let { java.io.File(it).length() } ?: 0L
        if (uri.scheme != "content") return 0L
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.SIZE)
                    if (i >= 0 && !c.isNull(i)) c.getLong(i) else 0L
                } else 0L
            } ?: 0L
        } catch (_: Throwable) { 0L }
    }
}

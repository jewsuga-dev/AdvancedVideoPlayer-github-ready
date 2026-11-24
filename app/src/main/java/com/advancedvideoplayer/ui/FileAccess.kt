package com.advancedvideoplayer.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/** Everything about "can this app read all files, and how do we ask for it". */
object FileAccess {

    /** True when the whole shared storage can be browsed (All-files access on 11+, read permission below). */
    fun hasFullAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED

    /** Settings screen for "All files access" (Android 11+), or null below 11 (use the runtime permission). */
    fun settingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val pkg = Uri.parse("package:${context.packageName}")
        val specific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)
        val generic = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        return if (specific.resolveActivity(context.packageManager) != null) specific else generic
    }

    /** Starting folders for the browser: internal storage first, then SD cards / USB drives under /storage. */
    fun roots(): List<File> {
        val out = linkedSetOf<File>()
        runCatching { Environment.getExternalStorageDirectory() }.getOrNull()?.let { if (it.canRead()) out += it }
        File("/storage").listFiles()?.forEach { f ->
            if (f.isDirectory && f.canRead() && f.name != "self" && f.name != "emulated") out += f
        }
        return out.toList()
    }

    val MEDIA_EXTENSIONS = setOf(
        "mp4", "m4v", "mkv", "mka", "webm", "ts", "m2ts", "mts", "mov", "avi", "flv", "3gp", "ogv", "wmv",
        "mpg", "mpeg", "vob", "m3u8", "mpd", "mp3", "aac", "flac", "wav", "m4a", "opus", "ogg", "dts", "ac3",
        "eac3", "thd", "mka", "iso"
    )

    fun isMedia(f: File) = f.extension.lowercase() in MEDIA_EXTENSIONS

    /** Folders first, then files, each alphabetically (natural-ish), hidden entries skipped. */
    fun list(dir: File, mediaOnly: Boolean): List<File> {
        val all = try { dir.listFiles()?.toList() } catch (_: SecurityException) { null } ?: return emptyList()
        return all.filter { !it.name.startsWith(".") && (it.isDirectory || !mediaOnly || isMedia(it)) }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
    }
}

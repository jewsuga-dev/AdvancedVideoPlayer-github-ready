package com.advancedvideoplayer.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import java.io.File

/**
 * Full-screen browser over the whole shared storage (needs All-files access).
 * Picks any file – including .mkv/.ts/.dts that the system picker often greys out because of a
 * wrong MIME type – and returns a file:// Uri.
 */
@Composable
fun FileBrowser(onPick: (Uri) -> Unit, onDismiss: () -> Unit) {
    val roots = remember { FileAccess.roots() }
    var current by remember { mutableStateOf<File?>(null) }   // null = list of storage roots
    var mediaOnly by remember { mutableStateOf(true) }

    fun up() {
        val c = current ?: return
        val parent = c.parentFile
        current = if (parent == null || roots.any { it == c }) null else parent
    }
    BackHandler { if (current == null) onDismiss() else up() }

    Column(Modifier.fillMaxSize().background(Color(0xFF0B0B0B)).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (current == null) onDismiss() else up() }) {
                Icon(if (current == null) Icons.Default.Close else Icons.Default.ArrowBack, "Back", tint = Color.White)
            }
            Text(
                current?.absolutePath ?: "Storage", color = Color.White, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Checkbox(checked = !mediaOnly, onCheckedChange = { mediaOnly = !it })
            Text("All files", color = Color.White.copy(0.8f), fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
        }

        val dir = current
        val entries: List<File> = if (dir == null) roots else FileAccess.list(dir, mediaOnly)

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (dir == null) "No readable storage found.\nGrant All-files access first."
                    else "Nothing here" + if (mediaOnly) " (media only – tick “All files” to see everything)" else "",
                    color = Color.Gray, modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.absolutePath }) { f ->
                    val isDir = f.isDirectory
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { if (isDir) current = f else onPick(Uri.fromFile(f)) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Icon(
                            when {
                                dir == null -> Icons.Default.SdStorage
                                isDir -> Icons.Default.Folder
                                FileAccess.isMedia(f) -> Icons.Default.Movie
                                else -> Icons.Default.InsertDriveFile
                            }, null,
                            tint = if (isDir || dir == null) Color(0xFFFFC107) else Color(0xFFFF4D6D),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (dir == null) f.absolutePath else f.name, color = Color.White, fontSize = 14.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            if (!isDir) Text(sizeText(f.length()), color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun sizeText(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(java.util.Locale.US, b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> "%.1f MB".format(java.util.Locale.US, b / (1024.0 * 1024))
    else -> "${b / 1024} KB"
}

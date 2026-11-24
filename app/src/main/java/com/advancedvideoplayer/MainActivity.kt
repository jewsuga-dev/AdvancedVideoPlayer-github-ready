package com.advancedvideoplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.advancedvideoplayer.data.TempFiles
import com.advancedvideoplayer.ui.FileAccess
import com.advancedvideoplayer.ui.PlayerScreen
import com.advancedvideoplayer.ui.theme.AdvancedVideoPlayerTheme

class MainActivity : ComponentActivity() {

    private var currentUri by mutableStateOf<Uri?>(null)
    private var hasFullAccess by mutableStateOf(false)

    private val pickMedia = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            // Persisting the grant is a convenience; some providers refuse it and that must not crash.
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            } catch (_: UnsupportedOperationException) {
            }
            currentUri = it
        }
    }

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* handled in UI */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Leftovers of earlier runs (crash / process death). Screen-on is handled by PlayerView
        // only while actually playing, instead of a permanent FLAG_KEEP_SCREEN_ON.
        TempFiles.sweep(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            AdvancedVideoPlayerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    PlayerScreen(
                        videoUri = currentUri,
                        onOpenFile = { openFilePicker() },
                        onOpenUrl = { openUrl(it) },
                        hasFullAccess = hasFullAccess,
                        onGrantAccess = { requestFullAccess() },
                        onOpenUri = { currentUri = it }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have just flipped the switch in Settings.
        hasFullAccess = FileAccess.hasFullAccess(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        if (isFinishing) TempFiles.sweep(applicationContext)
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { currentUri = it }
        }
    }

    private fun openFilePicker() {
        try {
            pickMedia.launch(arrayOf("*/*")) // MKV/TS/DTS files are often mis-typed; never grey them out
        } catch (e: Exception) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openUrl(raw: String) {
        val text = raw.trim()
        val withScheme = if (text.contains("://")) text else "https://$text"
        val uri = Uri.parse(withScheme)
        when (uri.scheme?.lowercase()) {
            "http", "https", "rtsp", "rtsps" -> if (!uri.host.isNullOrBlank()) currentUri = uri
            else -> Toast.makeText(this, "Enter an http(s):// or rtsp:// link", Toast.LENGTH_SHORT).show()
        }
    }

    /** Android 11+: opens the "All files access" switch for this app; below: runtime read permission. */
    private fun requestFullAccess() {
        val intent = FileAccess.settingsIntent(this)
        if (intent != null) {
            try {
                startActivity(intent)
                Toast.makeText(this, "Turn on “Allow access to manage all files”, then come back", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Open Settings › Apps › Special access › All files access", Toast.LENGTH_LONG).show()
            }
        } else {
            requestStoragePermission()
        }
    }

    private fun requestStoragePermission() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermission.launch(permissions)
    }

    fun hasStoragePermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }
}

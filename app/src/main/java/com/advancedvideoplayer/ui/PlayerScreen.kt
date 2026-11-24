package com.advancedvideoplayer.ui

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.advancedvideoplayer.data.CodecNames
import com.advancedvideoplayer.data.StreamInfo
import com.advancedvideoplayer.data.StreamInfoCoordinator
import com.advancedvideoplayer.data.TrackInfo
import com.advancedvideoplayer.player.PlaybackFailure
import com.advancedvideoplayer.player.PlayerManager
import kotlinx.coroutines.delay
import kotlin.math.abs

private val Accent = Color(0xFFFF4D6D)
private const val LOGO_POPUP_MS = 6000L

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    videoUri: Uri?,
    onOpenFile: () -> Unit,
    onOpenUrl: (String) -> Unit,
    hasFullAccess: Boolean,
    onGrantAccess: () -> Unit,
    onOpenUri: (Uri) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val playerManager = remember { PlayerManager(context) }
    val streamInfo = remember { StreamInfoCoordinator(context, playerManager.playerInfo) }
    val exoPlayer = playerManager.player

    var controlsVisible by remember { mutableStateOf(true) }
    var showMetadataPanel by remember { mutableStateOf(false) }
    var showAudioTracks by remember { mutableStateOf(false) }
    var showSubtitleTracks by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showBrowser by remember { mutableStateOf(false) }
    var gestureText by remember { mutableStateOf<String?>(null) }
    var logoPopupVisible by remember { mutableStateOf(false) }
    var logoTrigger by remember { mutableIntStateOf(0) }
    var wasPlaying by remember { mutableStateOf(false) }

    val isPlaying by playerManager.isPlaying.collectAsState()
    val isBuffering by playerManager.isBuffering.collectAsState()
    val failure by playerManager.failure.collectAsState()
    val warnings by playerManager.warnings.collectAsState()
    val position by playerManager.position.collectAsState()
    val bufferedPosition by playerManager.bufferedPosition.collectAsState()
    val duration by playerManager.duration.collectAsState()
    val speed by playerManager.playbackSpeed.collectAsState()
    val audioTracks by playerManager.audioTracks.collectAsState()
    val subtitleTracks by playerManager.subtitleTracks.collectAsState()
    val selectedAudioId by playerManager.selectedAudioId.collectAsState()
    val selectedSubtitleId by playerManager.selectedSubtitleId.collectAsState()
    val repeatMode by playerManager.repeatMode.collectAsState()
    val resizeMode by playerManager.videoResizeMode.collectAsState()
    val info by streamInfo.info.collectAsState()

    // ---- Load: playback and metadata are started independently; neither waits for the other. ----
    LaunchedEffect(videoUri) {
        if (videoUri != null) {
            streamInfo.start(videoUri)
            playerManager.load(videoUri)
        } else {
            streamInfo.clear()
        }
    }

    // ---- Logo popup: ONE effect owns visibility. ----
    // Re-runs whenever the detected logo set changes (new stream, probe finished, audio track
    // switched) or a re-show is requested (pause / menu). No flags to get out of sync.
    val logos = info.logos
    LaunchedEffect(logos, logoTrigger) {
        if (logos.isEmpty()) {
            logoPopupVisible = false
        } else {
            logoPopupVisible = true
            delay(LOGO_POPUP_MS)
            logoPopupVisible = false
        }
    }
    // Show again when the user pauses a healthy stream.
    LaunchedEffect(isPlaying) {
        if (wasPlaying && !isPlaying && failure == null && !isBuffering) logoTrigger++
        wasPlaying = isPlaying
    }

    // ---- Controls ----
    LaunchedEffect(failure) { if (failure != null) controlsVisible = true }
    LaunchedEffect(controlsVisible, isPlaying, failure) {
        if (controlsVisible && isPlaying && failure == null) {
            delay(4000)
            controlsVisible = false
        }
    }

    // ---- Lifecycle: pause when leaving the screen, release everything when disposed. ----
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) playerManager.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose {
            streamInfo.release()
            playerManager.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { offset ->
                        val width = size.width
                        when {
                            offset.x < width / 3 -> {
                                playerManager.seekBy(-10000)
                                gestureText = "« 10s"
                            }
                            offset.x > width * 2 / 3 -> {
                                playerManager.seekBy(10000)
                                gestureText = "10s »"
                            }
                            else -> playerManager.togglePlayPause()
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    if (abs(dragAmount) > 5) {
                        gestureText = if (dragAmount < 0) "Brightness ↑" else "Brightness ↓"
                    }
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    if (abs(dragAmount) > 15) {
                        playerManager.seekBy((dragAmount * 50).toLong())
                        gestureText = if (dragAmount > 0) "Seeking »" else "« Seeking"
                    }
                }
            }
    ) {
        if (videoUri != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                update = { view ->
                    view.resizeMode = when (resizeMode) {
                        PlayerManager.ResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        PlayerManager.ResizeMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
                        PlayerManager.ResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        PlayerManager.ResizeMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    }
                },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            EmptyState(
                onOpenFile = onOpenFile,
                onOpenUrl = { showUrlDialog = true },
                hasFullAccess = hasFullAccess,
                onGrantAccess = onGrantAccess,
                onBrowse = { if (hasFullAccess) showBrowser = true else onGrantAccess() }
            )
        }

        // Gesture feedback
        AnimatedVisibility(
            visible = gestureText != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(color = Color.Black.copy(0.7f), shape = RoundedCornerShape(12.dp)) {
                Text(
                    gestureText ?: "",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
        }
        LaunchedEffect(gestureText) {
            if (gestureText != null) {
                delay(800)
                gestureText = null
            }
        }

        // Format logo popup (stream-based)
        AnimatedVisibility(
            visible = logoPopupVisible && logos.isNotEmpty() && failure == null,
            enter = fadeIn() + slideInHorizontally { it },
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 96.dp, end = 12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
                logos.forEach { FormatBadge(it) }
            }
        }

        // Non-fatal warnings ("playing without sound", "reconnecting…") stay visible with the controls hidden.
        if (warnings.isNotEmpty() && failure == null) {
            Column(
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 64.dp, start = 12.dp, end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                warnings.forEach { WarningChip(it) }
            }
        }

        // Top bar
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopBar(
                title = info.summary.title.ifBlank { "Advanced Video Player" },
                subtitle = if (videoUri != null) info.summary.subtitleLine else "",
                onOpenFile = onOpenFile,
                onToggleMetadata = { showMetadataPanel = !showMetadataPanel },
                onMore = { showMoreMenu = true }
            )
        }

        // Bottom controls
        AnimatedVisibility(
            visible = controlsVisible && videoUri != null,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            BottomControls(
                isPlaying = isPlaying,
                position = position,
                bufferedPosition = bufferedPosition,
                duration = duration,
                isLive = info.summary.isLive,
                speed = speed,
                repeatMode = repeatMode,
                resizeLabel = when (resizeMode) {
                    PlayerManager.ResizeMode.FIT -> "Fit"
                    PlayerManager.ResizeMode.FILL -> "Fill"
                    PlayerManager.ResizeMode.ZOOM -> "Zoom"
                    PlayerManager.ResizeMode.STRETCH -> "Stretch"
                },
                onPlayPause = { playerManager.togglePlayPause() },
                onSeek = { playerManager.seekTo(it) },
                onSeekBack = { playerManager.seekBy(-10000) },
                onSeekForward = { playerManager.seekBy(10000) },
                onSpeed = { showSpeedMenu = true },
                onAudio = { showAudioTracks = true },
                onSubtitle = { showSubtitleTracks = true },
                onRepeat = { playerManager.cycleRepeatMode() },
                onResize = { playerManager.cycleResizeMode() }
            )
        }

        if (showAudioTracks) {
            TrackSelectionSheet(
                title = "Audio Tracks",
                emptyText = "No audio tracks found in this stream.",
                tracks = audioTracks.map { TrackRow(it.id, it.name, it.isSupported) },
                selectedId = selectedAudioId,
                onSelect = { id -> if (id != null) playerManager.selectAudioTrack(id); showAudioTracks = false },
                onDismiss = { showAudioTracks = false }
            )
        }

        if (showSubtitleTracks) {
            TrackSelectionSheet(
                title = "Subtitles",
                emptyText = null,
                tracks = listOf(TrackRow(null, "Off", true)) + subtitleTracks.map { TrackRow(it.id, it.name, it.isSupported) },
                selectedId = selectedSubtitleId,
                onSelect = { playerManager.selectSubtitleTrack(it); showSubtitleTracks = false },
                onDismiss = { showSubtitleTracks = false }
            )
        }

        if (showSpeedMenu) {
            SpeedSheet(
                current = speed,
                onSelect = { playerManager.setSpeed(it); showSpeedMenu = false },
                onDismiss = { showSpeedMenu = false }
            )
        }

        if (showMoreMenu) {
            MoreMenu(
                onDismiss = { showMoreMenu = false },
                onToggleLogos = {
                    showMoreMenu = false
                    if (logoPopupVisible) logoPopupVisible = false else logoTrigger++
                },
                onToggleMetadata = { showMoreMenu = false; showMetadataPanel = true },
                onOpenUrl = { showMoreMenu = false; showUrlDialog = true },
                onBrowse = { showMoreMenu = false; if (hasFullAccess) showBrowser = true else onGrantAccess() }
            )
        }

        if (showBrowser) {
            FileBrowser(
                onPick = { showBrowser = false; onOpenUri(it) },
                onDismiss = { showBrowser = false }
            )
        }

        if (showUrlDialog) {
            OpenUrlDialog(
                onDismiss = { showUrlDialog = false },
                onOpen = { showUrlDialog = false; onOpenUrl(it) }
            )
        }

        AnimatedVisibility(
            visible = showMetadataPanel,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            MetadataPanel(info = info, onClose = { showMetadataPanel = false })
        }

        // Spinner: strictly tied to the player's real buffering state. Never shown with an error,
        // never driven by metadata extraction.
        if (isBuffering && failure == null && videoUri != null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(48.dp),
                color = Accent,
                strokeWidth = 3.dp
            )
        }

        failure?.let { f ->
            ErrorOverlay(
                failure = f,
                onRetry = { playerManager.retry() },
                onOpenFile = onOpenFile,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun EmptyState(
    onOpenFile: () -> Unit,
    onOpenUrl: () -> Unit,
    hasFullAccess: Boolean,
    onGrantAccess: () -> Unit,
    onBrowse: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Rounded.VideoLibrary, null, modifier = Modifier.size(96.dp), tint = Color.White.copy(0.3f))
        Spacer(modifier = Modifier.height(20.dp))
        Text("Open a video, audio file or stream", color = Color.White.copy(0.7f), fontSize = 18.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Text("MKV • MP4 • TS • HLS • DASH • DTS:X • Atmos • TrueHD • DV", color = Color.White.copy(0.4f), fontSize = 13.sp)
        Spacer(modifier = Modifier.height(28.dp))
        Button(
            onClick = onOpenFile,
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.FolderOpen, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Open File")
        }
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(onClick = onBrowse, shape = RoundedCornerShape(12.dp)) {
            Icon(Icons.Default.FolderOpen, null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Browse All Files", color = Color.White)
        }
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(onClick = onOpenUrl, shape = RoundedCornerShape(12.dp)) {
            Icon(Icons.Default.Link, null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Open Network Stream", color = Color.White)
        }
        if (!hasFullAccess) {
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onGrantAccess) {
                Text("Grant full file access", color = Accent)
            }
        }
    }
}

@Composable
private fun ErrorOverlay(
    failure: PlaybackFailure,
    onRetry: () -> Unit,
    onOpenFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.padding(24.dp).widthIn(max = 420.dp),
        color = Color(0xF2121212),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.ErrorOutline, null, tint = Accent, modifier = Modifier.size(40.dp))
            Text(failure.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(failure.detail, color = Color.White.copy(0.75f), fontSize = 14.sp)
            if (failure.codeName.isNotBlank()) {
                Text(failure.codeName, color = Color.White.copy(0.35f), fontSize = 11.sp)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                    Icon(Icons.Default.Refresh, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Retry")
                }
                OutlinedButton(onClick = onOpenFile) { Text("Open other", color = Color.White) }
            }
        }
    }
}

@Composable
private fun WarningChip(text: String) {
    Surface(color = Color(0xE63A2A00), shape = RoundedCornerShape(10.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.WarningAmber, null, tint = Color(0xFFFFC107), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, color = Color.White, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    subtitle: String,
    onOpenFile: () -> Unit,
    onToggleMetadata: () -> Unit,
    onMore: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(0.8f), Color.Transparent)))
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle, color = Color.White.copy(0.7f), fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onToggleMetadata) { Icon(Icons.Default.Info, "Stream info", tint = Color.White) }
        IconButton(onClick = onOpenFile) { Icon(Icons.Default.FolderOpen, "Open file", tint = Color.White) }
        IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, "More", tint = Color.White) }
    }
}

@Composable
private fun BottomControls(
    isPlaying: Boolean, position: Long, bufferedPosition: Long, duration: Long, isLive: Boolean,
    speed: Float, repeatMode: Int, resizeLabel: String,
    onPlayPause: () -> Unit, onSeek: (Long) -> Unit, onSeekBack: () -> Unit, onSeekForward: () -> Unit,
    onSpeed: () -> Unit, onAudio: () -> Unit, onSubtitle: () -> Unit, onRepeat: () -> Unit, onResize: () -> Unit
) {
    // While dragging, the thumb follows the finger; the player is only asked to seek on release.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val seekable = duration > 0 && !isLive
    val fraction = dragFraction ?: if (seekable) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.85f))))
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatTime(if (dragFraction != null) (fraction * duration).toLong() else position),
                color = Color.White.copy(0.8f), fontSize = 11.sp
            )
            Slider(
                value = fraction,
                onValueChange = { dragFraction = it },
                onValueChangeFinished = {
                    dragFraction?.let { onSeek((it * duration).toLong()) }
                    dragFraction = null
                },
                enabled = seekable,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                    inactiveTrackColor = Color.White.copy(0.25f),
                    disabledThumbColor = Color.White.copy(0.4f),
                    disabledActiveTrackColor = Color.White.copy(0.3f),
                    disabledInactiveTrackColor = Color.White.copy(0.15f)
                )
            )
            Text(
                if (isLive) "LIVE" else formatTime(duration),
                color = if (isLive) Accent else Color.White.copy(0.8f), fontSize = 11.sp,
                fontWeight = if (isLive) FontWeight.Bold else FontWeight.Normal
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onRepeat) {
                Icon(
                    if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    "Repeat",
                    tint = if (repeatMode != Player.REPEAT_MODE_OFF) Accent else Color.White
                )
            }
            IconButton(onClick = onSeekBack) {
                Icon(Icons.Default.Replay10, "Back 10 seconds", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            FilledIconButton(
                onClick = onPlayPause, modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White)
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (isPlaying) "Pause" else "Play", tint = Color.Black, modifier = Modifier.size(32.dp)
                )
            }
            IconButton(onClick = onSeekForward) {
                Icon(Icons.Default.Forward10, "Forward 10 seconds", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            TextButton(onClick = onSpeed) {
                Text("${speed}x", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = onAudio) {
                Icon(Icons.Default.Audiotrack, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Audio", color = Color.White, fontSize = 12.sp)
            }
            TextButton(onClick = onSubtitle) {
                Icon(Icons.Default.ClosedCaption, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Subs", color = Color.White, fontSize = 12.sp)
            }
            TextButton(onClick = onResize) {
                Icon(Icons.Default.AspectRatio, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(resizeLabel, color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

private data class TrackRow(val id: String?, val name: String, val enabled: Boolean)

@Composable
private fun TrackSelectionSheet(
    title: String,
    emptyText: String?,
    tracks: List<TrackRow>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            if (tracks.isEmpty() && emptyText != null) {
                Text(emptyText)
            } else {
                LazyColumn {
                    items(tracks.size) { i ->
                        val row = tracks[i]
                        val alpha = if (row.enabled) 1f else 0.4f
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = row.enabled) { onSelect(row.id) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = row.id == selectedId, onClick = { onSelect(row.id) }, enabled = row.enabled)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(row.name, color = MaterialTheme.colorScheme.onSurface.copy(alpha))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun SpeedSheet(current: Float, onSelect: (Float) -> Unit, onDismiss: () -> Unit) {
    val speeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playback Speed") },
        text = {
            LazyColumn {
                items(speeds) { s ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(s) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = s == current, onClick = { onSelect(s) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (s == 1.0f) "Normal" else "${s}x")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun MoreMenu(
    onDismiss: () -> Unit,
    onToggleLogos: () -> Unit,
    onToggleMetadata: () -> Unit,
    onOpenUrl: () -> Unit,
    onBrowse: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("More Options") },
        text = {
            Column {
                TextButton(onClick = onToggleLogos, modifier = Modifier.fillMaxWidth()) { Text("Show / hide format logos") }
                TextButton(onClick = onToggleMetadata, modifier = Modifier.fillMaxWidth()) { Text("Media information") }
                TextButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) { Text("Browse all files…") }
                TextButton(onClick = onOpenUrl, modifier = Modifier.fillMaxWidth()) { Text("Open network stream…") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun OpenUrlDialog(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open network stream") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("https://example.com/stream.m3u8") }
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onOpen(text.trim()) }) { Text("Play") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ------------------------------------------------------------------------------------------
// Stream information panel
// ------------------------------------------------------------------------------------------

@Composable
private fun MetadataPanel(info: StreamInfo, onClose: () -> Unit) {
    val md = info.metadata
    val s = info.summary
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(0.92f)
            .widthIn(max = 340.dp)
            .padding(top = 48.dp, bottom = 12.dp, end = 8.dp),
        color = Color(0xFF121212).copy(alpha = 0.96f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Stream Info", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
            }
            if (info.probing) {
                // Non-blocking: details already shown stay visible; this only says more is coming.
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Color.White.copy(0.1f))
            } else {
                HorizontalDivider(color = Color.White.copy(0.1f))
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (md.isEmpty && s.title.isBlank() && s.videoCodec.isBlank() && s.audioCodec.isBlank()) {
                Text(
                    if (info.probing) "Reading stream details…" else "No stream information available yet.",
                    color = Color.Gray
                )
                return@Column
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    SectionTitle("Stream")
                    MetaLine("Title", s.title)
                    if (md.fileName.isNotBlank() && md.fileName != s.title) MetaLine("File", md.fileName)
                    MetaLine("Container", s.container)
                    MetaLine("Duration", if (s.isLive) "Live" else if (s.duration > 0) formatTime(s.duration) else "")
                    MetaLine("Bitrate", s.bitrate)
                    MetaLine("Size", formatSize(md.sizeBytes))
                    MetaLine("Resolution", s.resolution)
                    MetaLine("Frame rate", s.frameRate)
                    MetaLine("Video codec", s.videoCodec)
                    MetaLine("HDR", s.hdr)
                    MetaLine("Audio codec", s.audioCodec)
                }
                if (md.specialFormats.isNotEmpty()) {
                    item {
                        SectionTitle("Special formats")
                        md.specialFormats.forEach {
                            Text("★ ${it.displayName}", color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                }
                items(md.videoTracks) { t -> VideoTrackBlock(t) }
                items(md.audioTracks) { t -> AudioTrackBlock(t) }
                if (md.subtitleTracks.isNotEmpty()) {
                    item {
                        SectionTitle("Subtitles")
                        md.subtitleTracks.forEach { t ->
                            val line = listOf(t.language.uppercase(), CodecNames.label(t), t.title)
                                .filter { it.isNotBlank() }.joinToString(" • ")
                            Text(line.ifEmpty { "Track ${t.index}" }, color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoTrackBlock(t: TrackInfo) {
    Column {
        SectionTitle("Video" + if (t.selected) "  ▶" else "")
        MetaLine("Codec", CodecNames.label(t).ifEmpty { t.codec })
        MetaLine("Resolution", CodecNames.resolutionLabel(t.width, t.height))
        MetaLine("Frame rate", if (t.frameRate > 0) "%.3f fps".format(java.util.Locale.US, t.frameRate) else "")
        MetaLine("Bitrate", CodecNames.bitrateLabel(t.bitrate))
        MetaLine("Bit depth", if (t.bitDepth > 0) "${t.bitDepth}-bit" else "")
        MetaLine("Profile", t.profile)
        MetaLine("Colour", listOf(t.colorSpace, t.colorTransfer, t.colorPrimaries).filter { it.isNotBlank() }.joinToString(" / "))
        if (t.isDolbyVision) MetaLine("Dolby Vision", if (t.dvProfile.isNotBlank()) "Profile ${t.dvProfile}" else "Yes")
        if (t.isHdr10Plus) MetaLine("HDR10+", "Yes")
        if (t.isHdr) MetaLine("HDR", "Yes")
        MetaLine("Device", t.extra["support"].orEmpty())
    }
}

@Composable
private fun AudioTrackBlock(t: TrackInfo) {
    Column {
        SectionTitle("Audio" + if (t.selected) "  ▶" else "")
        MetaLine("Codec", CodecNames.label(t).ifEmpty { t.codec })
        MetaLine("Channels", listOf(CodecNames.channelsLabel(t.channels), t.channelLayout).filter { it.isNotBlank() }.joinToString(" "))
        MetaLine("Sample rate", if (t.sampleRate > 0) "${t.sampleRate} Hz" else "")
        MetaLine("Bit depth", if (t.bitDepth > 0) "${t.bitDepth}-bit" else "")
        MetaLine("Bitrate", CodecNames.bitrateLabel(t.bitrate))
        MetaLine("Language", t.language.uppercase())
        MetaLine("Title", t.title)
        MetaLine("Profile", t.profile)
        if (t.isAtmos) Text("★ Dolby Atmos", color = Color(0xFF00B4E4), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        if (t.isDtsX) Text("★ DTS:X", color = Color(0xFFE31C25), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        if (t.isTrueHd) Text("★ TrueHD", color = Color(0xFF00B4E4), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        MetaLine("Device", t.extra["support"].orEmpty())
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(bottom = 2.dp))
}

/** Hidden when [value] is blank, so unknown fields never show "0×0" or "—". */
@Composable
private fun MetaLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text(label, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.width(92.dp))
        Text(value, color = Color.White, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes / 1024.0 / 1024.0
    val gb = mb / 1024.0
    return if (gb >= 1) "%.2f GB".format(gb) else "%.1f MB".format(mb)
}

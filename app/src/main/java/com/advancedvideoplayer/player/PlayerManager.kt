package com.advancedvideoplayer.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.advancedvideoplayer.data.CodecNames
import com.advancedvideoplayer.data.FormatDetector
import com.advancedvideoplayer.data.MediaMetadata
import com.advancedvideoplayer.data.MetadataSource
import com.advancedvideoplayer.data.TrackInfo as ProbeTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Owns the ExoPlayer instance and everything that can go wrong around it.
 *
 * What it guarantees
 *  - **No crash on bad input.** Every player callback is wrapped; unsupported codecs, malformed
 *    containers, dead links and decoder failures end in a [PlaybackFailure] the UI can show.
 *  - **The spinner always ends.** [isBuffering] is true only while the player really is in
 *    STATE_BUFFERING without an error. A watchdog turns a stalled start / re-buffer into a
 *    timeout failure after [Config.startupTimeoutMs] / [Config.rebufferTimeoutMs].
 *  - **Graceful degradation.** If only the audio *or* only the video codec is unsupported, the
 *    other half plays and a warning is raised instead of failing the whole file.
 *  - **Transient network errors retry** with back-off (1 s, 2 s, 4 s …) before failing.
 *  - **Format detection.** HLS / DASH / Smooth / RTSP / progressive are told apart (by URL, by
 *    Content-Type, by the first bytes) and, if a guess is wrong, the other types are tried once.
 *  - **Clean shutdown.** [release] cancels timers, coroutines and listeners, then the player.
 *
 * Must be created and used on the main thread (as ExoPlayer requires).
 */
@OptIn(UnstableApi::class)
class PlayerManager(private val context: Context, private val config: Config = Config()) {

    data class Config(
        /** Max time from prepare() until first READY. */
        val startupTimeoutMs: Long = 25_000,
        /** Max time a re-buffer / seek may stall mid-playback. */
        val rebufferTimeoutMs: Long = 30_000,
        val maxAutoRetries: Int = 3,
        val connectTimeoutMs: Int = 10_000,
        val readTimeoutMs: Int = 12_000,
        val minBufferMs: Int = 15_000,
        val maxBufferMs: Int = 50_000,
        val bufferForPlaybackMs: Int = 2_500,
        val bufferForPlaybackAfterRebufferMs: Int = 5_000
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val userAgent = Util.getUserAgent(context, "AdvancedVideoPlayer")

    @Volatile private var released = false

    // ------------------------------------------------------------------------------------------
    // Media3 configuration
    // ------------------------------------------------------------------------------------------

    private val trackSelector = DefaultTrackSelector(context).also { applyDefaultTrackParameters(it) }

    val player: ExoPlayer = buildPlayer()

    private fun applyDefaultTrackParameters(selector: DefaultTrackSelector) {
        val lang = Locale.getDefault().language.ifEmpty { "en" }
        selector.setParameters(
            selector.buildUponParameters()
                .clearOverrides()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setPreferredAudioLanguage(lang)
                .setPreferredTextLanguage(lang)
                // Prefer something that plays over the "best" track the device cannot decode.
                .setExceedRendererCapabilitiesIfNecessary(true)
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setTunnelingEnabled(false)
        )
    }

    private fun buildPlayer(): ExoPlayer {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setConnectTimeoutMs(config.connectTimeoutMs)
            .setReadTimeoutMs(config.readTimeoutMs)
            .setAllowCrossProtocolRedirects(true)

        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)

        // Tolerant container parsing: broken / headerless TS and CBR streams still play and seek.
        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
                        DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractors)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(3))

        // Hardware decoder first, software decoder as fallback; vendor extension renderers if present.
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                config.minBufferMs,
                config.maxBufferMs,
                config.bufferForPlaybackMs,
                config.bufferForPlaybackAfterRebufferMs
            )
            .build()

        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true // handle audio focus
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    // ------------------------------------------------------------------------------------------
    // Public state
    // ------------------------------------------------------------------------------------------

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _bufferedPosition = MutableStateFlow(0L)
    val bufferedPosition: StateFlow<Long> = _bufferedPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<TrackInfo>>(emptyList())
    val audioTracks: StateFlow<List<TrackInfo>> = _audioTracks.asStateFlow()

    private val _subtitleTracks = MutableStateFlow<List<TrackInfo>>(emptyList())
    val subtitleTracks: StateFlow<List<TrackInfo>> = _subtitleTracks.asStateFlow()

    private val _selectedAudioId = MutableStateFlow<String?>(null)
    val selectedAudioId: StateFlow<String?> = _selectedAudioId.asStateFlow()

    private val _selectedSubtitleId = MutableStateFlow<String?>(null)
    val selectedSubtitleId: StateFlow<String?> = _selectedSubtitleId.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _videoResizeMode = MutableStateFlow(ResizeMode.FIT)
    val videoResizeMode: StateFlow<ResizeMode> = _videoResizeMode.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    /** True only while loading / re-buffering and no error is pending. Drives the spinner. */
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _failure = MutableStateFlow<PlaybackFailure?>(null)
    /** Non-null = playback stopped because of this problem. Cleared by [load] / [retry]. */
    val failure: StateFlow<PlaybackFailure?> = _failure.asStateFlow()

    private val warningMap = LinkedHashMap<String, String>()
    private val _warnings = MutableStateFlow<List<String>>(emptyList())
    /** Non-fatal notes: "playing without sound", "reconnecting (2/3)" … */
    val warnings: StateFlow<List<String>> = _warnings.asStateFlow()

    private val _streamKind = MutableStateFlow(StreamKind.PROGRESSIVE)
    val streamKind: StateFlow<StreamKind> = _streamKind.asStateFlow()

    private val _playerInfo = MutableStateFlow(MediaMetadata(sources = setOf(MetadataSource.PLAYER)))
    /**
     * What ExoPlayer itself knows about the stream (tracks, formats, capabilities, ICY/ID3 title).
     * Available as soon as the container header is parsed and independent of every other probe.
     */
    val playerInfo: StateFlow<MediaMetadata> = _playerInfo.asStateFlow()

    /** UI-facing track entry. [id] is "<groupIndex>:<trackIndex>" – unique even when format ids repeat. */
    data class TrackInfo(
        val id: String,
        val name: String,
        val language: String,
        val codec: String = "",
        val channels: Int = 0,
        val isSelected: Boolean = false,
        val isSupported: Boolean = true
    )

    enum class ResizeMode { FIT, FILL, ZOOM, STRETCH }

    // ------------------------------------------------------------------------------------------
    // Internal bookkeeping
    // ------------------------------------------------------------------------------------------

    private var currentUri: Uri? = null
    private var currentKind: StreamKind = StreamKind.PROGRESSIVE
    private val triedKinds = mutableSetOf<StreamKind>()
    private var hasBeenReady = false
    private var autoRetryCount = 0
    private val droppedTrackTypes = mutableSetOf<Int>()
    private var sniffJob: Job? = null
    private var originalError: PlaybackException? = null
    private var tickerRunning = false

    private val watchdog = Runnable { onBufferingTimeout() }
    private val retryRunnable = Runnable { performAutoRetry() }
    private val ticker = object : Runnable {
        override fun run() {
            if (released) { tickerRunning = false; return }
            updatePosition()
            val state = player.playbackState
            if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) {
                mainHandler.postDelayed(this, TICK_MS)
            } else {
                tickerRunning = false
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Player listener
    // ------------------------------------------------------------------------------------------

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onPlaybackStateChanged(playbackState: Int) = guarded("onPlaybackStateChanged") {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    _isBuffering.value = _failure.value == null
                    armWatchdog(if (hasBeenReady) config.rebufferTimeoutMs else config.startupTimeoutMs)
                    startTicker()
                }
                Player.STATE_READY -> {
                    disarmWatchdog()
                    _isBuffering.value = false
                    hasBeenReady = true
                    autoRetryCount = 0
                    setWarning(KEY_RETRY, null)
                    updateDuration()
                    refreshPlayerInfo()
                    startTicker()
                }
                Player.STATE_ENDED, Player.STATE_IDLE -> {
                    disarmWatchdog()
                    _isBuffering.value = false
                    updatePosition()
                }
            }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) =
            guarded("onTimelineChanged") { updateDuration(); refreshPlayerInfo() }

        override fun onTracksChanged(tracks: Tracks) = guarded("onTracksChanged") {
            updateTrackLists(tracks)
            evaluateSupport(tracks)
            refreshPlayerInfo()
        }

        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) =
            guarded("onMediaMetadataChanged") { refreshPlayerInfo() }

        override fun onVideoSizeChanged(videoSize: VideoSize) =
            guarded("onVideoSizeChanged") { refreshPlayerInfo() }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            _playbackSpeed.value = playbackParameters.speed
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeatMode.value = repeatMode
        }

        override fun onPlayerError(error: PlaybackException) = guarded("onPlayerError") {
            handlePlayerError(error)
        }
    }

    init {
        player.addListener(listener)
    }

    /** Runs [block]; anything it throws is logged, never propagated into the player thread. */
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Unexpected failure in $what", t)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Loading & controls
    // ------------------------------------------------------------------------------------------

    /** Opens a local file, content:// URI or network stream. Safe to call repeatedly. */
    fun load(uri: Uri) {
        if (released) return
        resetForNewMedia()
        currentUri = uri
        _isBuffering.value = true
        sniffJob = scope.launch {
            val result = try {
                StreamSniffer.detect(uri, userAgent)
            } catch (t: Throwable) {
                StreamSniffer.Result(StreamKind.PROGRESSIVE)
            }
            if (released || currentUri != uri) return@launch
            startPlayback(uri, result.kind, startPositionMs = 0L)
        }
    }

    /** User-initiated retry after a [failure]; resumes near where playback stopped. */
    fun retry() {
        val uri = currentUri ?: return
        if (released) return
        val resume = if (hasBeenReady) player.currentPosition.coerceAtLeast(0) else 0L
        cancelTimers()
        autoRetryCount = 0
        triedKinds.clear()
        droppedTrackTypes.clear()
        failedAudioKeys.clear()
        originalError = null
        applyDefaultTrackParameters(trackSelector)
        _failure.value = null
        clearWarnings()
        _isBuffering.value = true
        startPlayback(uri, currentKind, resume)
    }

    private fun startPlayback(uri: Uri, kind: StreamKind, startPositionMs: Long) {
        if (released) return
        try {
            currentKind = kind
            triedKinds += kind
            _streamKind.value = kind
            val item = MediaItem.Builder().setUri(uri)
                .apply { kind.mimeType?.let { setMimeType(it) } }
                .build()
            if (startPositionMs > 0) player.setMediaItem(item, startPositionMs) else player.setMediaItem(item)
            player.prepare()
            player.playWhenReady = true
            armWatchdog(config.startupTimeoutMs)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Failed to start playback", t)
            fail(PlaybackFailure(
                PlaybackFailure.Category.UNKNOWN, "Cannot open this stream",
                t.message ?: "The player could not be started."
            ))
        }
    }

    private fun resetForNewMedia() {
        cancelTimers()
        sniffJob?.cancel()
        triedKinds.clear()
        droppedTrackTypes.clear()
        failedAudioKeys.clear()
        originalError = null
        hasBeenReady = false
        autoRetryCount = 0
        applyDefaultTrackParameters(trackSelector)
        try { player.stop(); player.clearMediaItems() } catch (_: Throwable) { }
        _failure.value = null
        clearWarnings()
        _isBuffering.value = false
        _position.value = 0L
        _bufferedPosition.value = 0L
        _duration.value = 0L
        _audioTracks.value = emptyList()
        _subtitleTracks.value = emptyList()
        _selectedAudioId.value = null
        _selectedSubtitleId.value = null
        _playerInfo.value = MediaMetadata(sources = setOf(MetadataSource.PLAYER))
    }

    fun play() { if (!released) { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() } }
    fun pause() { if (!released) player.pause() }
    fun togglePlayPause() {
        if (released) return
        if (player.isPlaying || player.playWhenReady && player.playbackState == Player.STATE_BUFFERING) pause() else play()
    }

    fun seekTo(positionMs: Long) {
        if (released) return
        player.seekTo(positionMs.coerceAtLeast(0))
        updatePosition()
    }

    fun seekBy(deltaMs: Long) {
        if (released) return
        val dur = player.duration
        var target = player.currentPosition + deltaMs
        if (dur != C.TIME_UNSET && dur > 0) target = target.coerceAtMost(dur)
        seekTo(target)
    }

    fun setSpeed(speed: Float) {
        if (released) return
        player.playbackParameters = PlaybackParameters(speed)
        _playbackSpeed.value = speed
    }

    fun cycleSpeed() {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val current = _playbackSpeed.value
        val idx = speeds.indexOfFirst { it > current + 0.001f }
        setSpeed(if (idx >= 0) speeds[idx] else speeds.first())
    }

    fun setRepeatMode(mode: Int) { if (!released) player.repeatMode = mode }

    fun cycleRepeatMode() {
        if (released) return
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun setResizeMode(mode: ResizeMode) { _videoResizeMode.value = mode }

    fun cycleResizeMode() {
        val values = ResizeMode.entries
        _videoResizeMode.value = values[(_videoResizeMode.value.ordinal + 1) % values.size]
    }

    fun updatePosition() {
        if (released) return
        _position.value = player.currentPosition.coerceAtLeast(0)
        _bufferedPosition.value = player.bufferedPosition.coerceAtLeast(0)
        updateDuration()
    }

    private fun updateDuration() {
        val d = player.duration
        _duration.value = if (d == C.TIME_UNSET || d < 0) 0L else d
    }

    private fun startTicker() {
        if (tickerRunning || released) return
        tickerRunning = true
        mainHandler.post(ticker)
    }

    // ------------------------------------------------------------------------------------------
    // Track selection
    // ------------------------------------------------------------------------------------------

    /** @return false when the track is unknown or cannot be decoded on this device. */
    fun selectAudioTrack(trackId: String): Boolean {
        if (released) return false
        val (group, index) = findTrack(C.TRACK_TYPE_AUDIO, trackId) ?: return false
        if (!group.isTrackSupported(index, true)) {
            setWarning(KEY_SELECT, "That audio track uses a codec this device cannot decode.")
            return false
        }
        setWarning(KEY_SELECT, null)
        droppedTrackTypes.remove(C.TRACK_TYPE_AUDIO)
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
        )
        _selectedAudioId.value = trackId
        return true
    }

    fun selectSubtitleTrack(trackId: String?) {
        if (released) return
        if (trackId == null) {
            trackSelector.setParameters(
                trackSelector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            )
            _selectedSubtitleId.value = null
            return
        }
        val (group, index) = findTrack(C.TRACK_TYPE_TEXT, trackId) ?: return
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
        )
        _selectedSubtitleId.value = trackId
    }

    private fun findTrack(type: Int, id: String): Pair<Tracks.Group, Int>? {
        val parts = id.split(':')
        val gi = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val ti = parts.getOrNull(1)?.toIntOrNull() ?: return null
        val groups = player.currentTracks.groups
        val group = groups.getOrNull(gi) ?: return null
        if (group.type != type || ti !in 0 until group.length) return null
        return group to ti
    }

    private fun updateTrackLists(tracks: Tracks) {
        val audioList = mutableListOf<TrackInfo>()
        val subList = mutableListOf<TrackInfo>()
        var selAudio: String? = null
        var selSub: String? = null

        tracks.groups.forEachIndexed { gi, group ->
            for (i in 0 until group.length) {
                val id = "$gi:$i"
                val format = group.getTrackFormat(i)
                val selected = group.isTrackSelected(i)
                val supported = group.isTrackSupported(i, true)
                when (group.type) {
                    C.TRACK_TYPE_AUDIO -> {
                        val probe = toProbeTrack(group, gi, i)
                        val lang = languageName(format.language)
                        val name = listOf(
                            lang.ifEmpty { format.label.orEmpty() },
                            CodecNames.label(probe),
                            CodecNames.channelsLabel(format.channelCount),
                            if (!supported) "unsupported" else ""
                        ).filter { it.isNotBlank() }.joinToString(" • ").ifEmpty { "Track ${audioList.size + 1}" }
                        audioList += TrackInfo(id, name, format.language.orEmpty(),
                            codec = probe.codec, channels = format.channelCount,
                            isSelected = selected, isSupported = supported)
                        if (selected) selAudio = id
                    }
                    C.TRACK_TYPE_TEXT -> {
                        val lang = languageName(format.language)
                        val name = when {
                            !format.label.isNullOrEmpty() -> format.label!!
                            lang.isNotEmpty() -> lang
                            else -> "Subtitle ${subList.size + 1}"
                        }
                        subList += TrackInfo(id, name, format.language.orEmpty(),
                            codec = format.sampleMimeType.orEmpty(), isSelected = selected, isSupported = supported)
                        if (selected) selSub = id
                    }
                }
            }
        }
        _audioTracks.value = audioList
        _subtitleTracks.value = subList
        _selectedAudioId.value = selAudio
        _selectedSubtitleId.value = selSub
    }

    private fun languageName(code: String?): String {
        if (code.isNullOrBlank() || code == C.LANGUAGE_UNDETERMINED) return ""
        return try {
            Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
                .ifBlank { code.uppercase() }.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        } catch (_: Exception) { code.uppercase() }
    }

    // ------------------------------------------------------------------------------------------
    // Capability checks
    // ------------------------------------------------------------------------------------------

    /**
     * Compares what the stream contains with what this device can decode, *before* anything crashes.
     * Half-playable streams get a warning; fully unplayable ones fail with a clear message.
     */
    private fun evaluateSupport(tracks: Tracks) {
        if (tracks.groups.isEmpty()) return
        fun scan(type: Int): Pair<Boolean, Boolean> { // (present, anySupported)
            val groups = tracks.groups.filter { it.type == type }
            return groups.isNotEmpty() to groups.any { g -> (0 until g.length).any { g.isTrackSupported(it, true) } }
        }
        val (hasVideo, videoOk) = scan(C.TRACK_TYPE_VIDEO)
        val (hasAudio, audioOk) = scan(C.TRACK_TYPE_AUDIO)

        fun describe(type: Int): String {
            val f = tracks.groups.firstOrNull { it.type == type }?.takeIf { it.length > 0 }?.getTrackFormat(0)
            return f?.let { formatLabel(it) }.orEmpty()
        }

        val videoBad = hasVideo && !videoOk
        val audioBad = hasAudio && !audioOk

        setWarning(KEY_NO_VIDEO, if (videoBad && !audioBad && hasAudio)
            "Video codec ${describe(C.TRACK_TYPE_VIDEO).ifEmpty { "" }} is not supported on this device – playing audio only." else null)
        setWarning(KEY_NO_AUDIO, if (audioBad && !videoBad && hasVideo)
            "Audio codec ${describe(C.TRACK_TYPE_AUDIO).ifEmpty { "" }} is not supported on this device – playing without sound." else null)

        // DTS / DTS-HD / DTS:X the device cannot decode: say exactly what happens instead.
        val unsupportedDts = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { g ->
            (0 until g.length).filter { !g.isTrackSupported(it, true) && CodecNames.family(g.getTrackFormat(it).sampleMimeType.orEmpty()) == "dts" }
                .map { g.getTrackFormat(it) }
        }
        if (unsupportedDts.isNotEmpty()) {
            val dtsName = formatLabel(unsupportedDts.first())
            val playingLabel = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.firstNotNullOfOrNull { g ->
                (0 until g.length).firstOrNull { g.isTrackSelected(it) && g.isTrackSupported(it, true) }
                    ?.let { formatLabel(g.getTrackFormat(it)) }
            }
            setWarning(KEY_DTS, if (playingLabel != null)
                "$dtsName has no decoder on this device – playing $playingLabel instead. " +
                        "(Add the FFmpeg decoder, see README, to play the DTS core of this track.)"
            else
                "$dtsName has no decoder on this device and the file has no other audio track. " +
                        "Playing without sound – add the FFmpeg decoder (see README) to play the DTS core.")
        } else setWarning(KEY_DTS, null)

        val nothingPlayable = (hasVideo || hasAudio) && (!hasVideo || videoBad) && (!hasAudio || audioBad)
        if (nothingPlayable && _failure.value == null) {
            val parts = listOfNotNull(
                if (hasVideo) "video ${describe(C.TRACK_TYPE_VIDEO)}".trim() else null,
                if (hasAudio) "audio ${describe(C.TRACK_TYPE_AUDIO)}".trim() else null
            ).joinToString(" and ")
            fail(PlaybackFailure(
                PlaybackFailure.Category.UNSUPPORTED_FORMAT, "Codec not supported",
                "This device cannot decode the $parts in this stream."
            ), stop = true)
        }
    }

    private fun formatLabel(f: Format): String {
        val raw = f.sampleMimeType ?: f.codecs ?: return ""
        val t = ProbeTrack(0, if (raw.startsWith("audio")) "audio" else "video", raw,
            extra = mapOf("codecs" to f.codecs.orEmpty()))
        val enriched = if (t.type == "audio") FormatDetector.enrichAudio(t) else FormatDetector.enrichVideo(t)
        return CodecNames.label(enriched).ifEmpty { raw }
    }

    // ------------------------------------------------------------------------------------------
    // Error handling
    // ------------------------------------------------------------------------------------------

    private fun handlePlayerError(error: PlaybackException) {
        val code = error.errorCode
        android.util.Log.w(TAG, "Player error ${error.errorCodeName}: ${error.message}", error)
        disarmWatchdog()
        _isBuffering.value = false

        // 1. Live stream fell out of its window → jump to live edge.
        if (code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            _isBuffering.value = true
            armWatchdog(config.startupTimeoutMs)
            return
        }

        val exo = error as? ExoPlaybackException
        val rendererFormat: Format? =
            if (exo != null && exo.type == ExoPlaybackException.TYPE_RENDERER) exo.rendererFormat else null

        // 2. One renderer (audio or video) cannot decode → keep the other one alive.
        if (exo != null && exo.type == ExoPlaybackException.TYPE_RENDERER &&
            PlaybackFailures.isRendererDecodeProblem(code) &&
            dropFailingRenderer(exo, rendererFormat)
        ) return

        // 3. Wrong container / manifest guess → try the other stream types once each.
        val formatProblem = PlaybackFailures.isFormatDetectionProblem(code)
        if (formatProblem) {
            if (originalError == null) originalError = error
            if (retryWithAnotherKind()) return
        }

        // Report the FIRST format problem, not the last guess's (a DASH parser complaining about an
        // MP4 file would be misleading).
        val reported = if (formatProblem) (originalError ?: error) else error
        val httpStatus = findHttpStatus(reported)
        val failure = PlaybackFailures.classify(
            reported.errorCode, reported.errorCodeName, httpStatus, rendererFormat?.let { formatLabel(it) }
        )

        // 4. Transient network trouble → back-off retry.
        if (failure.autoRetry && autoRetryCount < config.maxAutoRetries) {
            autoRetryCount++
            val delayMs = 1_000L shl (autoRetryCount - 1)
            setWarning(KEY_RETRY, "Connection problem – retrying (${autoRetryCount}/${config.maxAutoRetries})…")
            _isBuffering.value = true
            mainHandler.removeCallbacks(retryRunnable)
            mainHandler.postDelayed(retryRunnable, delayMs)
            return
        }

        fail(failure)
    }

    private fun performAutoRetry() {
        if (released || _failure.value != null) return
        try {
            player.prepare() // keeps the current position
            player.playWhenReady = true
            armWatchdog(config.startupTimeoutMs)
        } catch (t: Throwable) {
            fail(PlaybackFailures.classify(PlaybackException.ERROR_CODE_UNSPECIFIED, "", null))
        }
    }

    private fun dropFailingRenderer(exo: ExoPlaybackException, format: Format?): Boolean {
        val type = try { player.getRendererType(exo.rendererIndex) } catch (_: Throwable) { return false }
        if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_VIDEO) return false
        if (type in droppedTrackTypes) return false
        val other = if (type == C.TRACK_TYPE_AUDIO) C.TRACK_TYPE_VIDEO else C.TRACK_TYPE_AUDIO
        val otherUsable = player.currentTracks.groups.any { g ->
            g.type == other && (0 until g.length).any { g.isTrackSupported(it, true) }
        }
        if (!otherUsable) return false

        droppedTrackTypes += type
        val label = format?.let { formatLabel(it) }.orEmpty()
        trackSelector.setParameters(trackSelector.buildUponParameters().setTrackTypeDisabled(type, true))
        if (type == C.TRACK_TYPE_AUDIO) {
            setWarning(KEY_NO_AUDIO, "Audio ${label.ifEmpty { "codec" }} failed to decode – playing without sound.")
        } else {
            setWarning(KEY_NO_VIDEO, "Video ${label.ifEmpty { "codec" }} failed to decode – playing audio only.")
        }
        player.prepare()
        player.playWhenReady = true
        _isBuffering.value = true
        armWatchdog(config.startupTimeoutMs)
        return true
    }

    private val failedAudioKeys = mutableSetOf<String>()

    private fun audioKey(f: Format) = "${f.sampleMimeType}|${f.channelCount}|${f.language}"

    /**
     * An audio decoder failed at runtime (typical: DTS-HD MA / DTS:X / TrueHD with no working
     * decoder). Before giving up on sound, switch to another audio track of the file that the
     * device CAN decode – preferring the same language – and keep playing from the same position.
     *
     * Note: the "core" inside a DTS-HD MA / DTS:X stream is lossy (DTS 5.1 at ~1.5 Mbps), not
     * lossless – the lossless part is the extension. ExoPlayer plays that core only when the
     * device has a DTS decoder or passthrough; otherwise a separate AC-3/AAC track is the fallback.
     */
    private fun switchToAlternateAudio(failed: Format?): Boolean {
        failed?.let { failedAudioKeys += audioKey(it) }
        val groups = player.currentTracks.groups
        var best: Triple<Tracks.Group, Int, Int>? = null // group, track, score
        groups.forEach { g ->
            if (g.type != C.TRACK_TYPE_AUDIO) return@forEach
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                if (!g.isTrackSupported(i, true) || audioKey(f) in failedAudioKeys) continue
                var score = 0
                if (failed != null && f.language == failed.language) score += 4
                if (CodecNames.family(f.sampleMimeType.orEmpty()) != "dts") score += 2
                if (f.channelCount in 2..6) score += 1
                if (best == null || score > best!!.third) best = Triple(g, i, score)
            }
        }
        val pick = best ?: return false
        val (group, index, _) = pick
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
        )
        val from = failed?.let { formatLabel(it) }.orEmpty().ifEmpty { "audio" }
        val to = formatLabel(group.getTrackFormat(index)).ifEmpty { "another audio track" }
        setWarning(KEY_NO_AUDIO, "$from failed to decode – switched to $to.")
        player.prepare()
        player.playWhenReady = true
        _isBuffering.value = true
        armWatchdog(config.startupTimeoutMs)
        return true
    }

    private fun retryWithAnotherKind(): Boolean {
        val uri = currentUri ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val next = listOf(StreamKind.HLS, StreamKind.PROGRESSIVE, StreamKind.DASH).firstOrNull { it !in triedKinds }
            ?: return false
        android.util.Log.i(TAG, "Retrying ${uri} as ${next.label}")
        _isBuffering.value = true
        startPlayback(uri, next, if (hasBeenReady) player.currentPosition else 0L)
        return true
    }

    private fun findHttpStatus(error: Throwable): Int? {
        var c: Throwable? = error
        var depth = 0
        while (c != null && depth++ < 10) {
            if (c is HttpDataSource.InvalidResponseCodeException) return c.responseCode
            c = c.cause
        }
        return null
    }

    private fun onBufferingTimeout() {
        if (released || _failure.value != null) return
        val secs = (if (hasBeenReady) config.rebufferTimeoutMs else config.startupTimeoutMs) / 1000
        fail(PlaybackFailure(
            PlaybackFailure.Category.TIMEOUT,
            if (hasBeenReady) "Stream stalled" else "Stream is not responding",
            if (hasBeenReady) "No new data arrived for $secs seconds. The connection may have dropped."
            else "The stream did not start within $secs seconds. The link may be offline or too slow."
        ), stop = true)
    }

    /** Terminal state: stop the player, stop every timer, stop the spinner, show [failure]. */
    private fun fail(failure: PlaybackFailure, stop: Boolean = true) {
        if (released) return
        cancelTimers()
        _isBuffering.value = false
        _isPlaying.value = false
        _failure.value = failure
        if (stop) try { player.stop() } catch (_: Throwable) { }
    }

    // ------------------------------------------------------------------------------------------
    // Warnings & timers
    // ------------------------------------------------------------------------------------------

    private fun setWarning(key: String, text: String?) {
        if (text == null) warningMap.remove(key) else warningMap[key] = text.replace("  ", " ")
        _warnings.value = warningMap.values.toList()
    }

    private fun clearWarnings() {
        warningMap.clear()
        _warnings.value = emptyList()
    }

    private fun armWatchdog(ms: Long) {
        mainHandler.removeCallbacks(watchdog)
        mainHandler.postDelayed(watchdog, ms)
    }

    private fun disarmWatchdog() = mainHandler.removeCallbacks(watchdog)

    private fun cancelTimers() {
        mainHandler.removeCallbacks(watchdog)
        mainHandler.removeCallbacks(retryRunnable)
    }

    // ------------------------------------------------------------------------------------------
    // Player-side metadata (independent of FFprobe / MediaExtractor)
    // ------------------------------------------------------------------------------------------

    private fun toProbeTrack(group: Tracks.Group, groupIndex: Int, i: Int): ProbeTrack {
        val f = group.getTrackFormat(i)
        val type = when (group.type) {
            C.TRACK_TYPE_VIDEO -> "video"
            C.TRACK_TYPE_AUDIO -> "audio"
            C.TRACK_TYPE_TEXT -> "subtitle"
            else -> "other"
        }
        val transfer = when (f.colorInfo?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> "smpte2084"
            C.COLOR_TRANSFER_HLG -> "arib-std-b67"
            C.COLOR_TRANSFER_SDR -> "bt709"
            else -> ""
        }
        val bitrate = when {
            f.averageBitrate > 0 -> f.averageBitrate.toLong()
            f.peakBitrate > 0 -> f.peakBitrate.toLong()
            f.bitrate > 0 -> f.bitrate.toLong()
            else -> 0L
        }
        val base = ProbeTrack(
            index = groupIndex * 100 + i,
            type = type,
            codec = f.sampleMimeType ?: f.codecs.orEmpty(),
            language = f.language?.takeIf { it != C.LANGUAGE_UNDETERMINED }.orEmpty(),
            title = f.label.orEmpty(),
            bitrate = bitrate,
            channels = f.channelCount.takeIf { it > 0 } ?: 0,
            sampleRate = f.sampleRate.takeIf { it > 0 } ?: 0,
            width = f.width.takeIf { it > 0 } ?: 0,
            height = f.height.takeIf { it > 0 } ?: 0,
            frameRate = f.frameRate.takeIf { it > 0f } ?: 0f,
            bitDepth = f.colorInfo?.lumaBitdepth?.takeIf { it > 0 } ?: 0,
            colorTransfer = transfer,
            supported = group.isTrackSupported(i, true),
            selected = group.isTrackSelected(i),
            extra = buildMap<String, String> {
                f.codecs?.let { put("codecs", it) }
                put("support", supportLabel(group.getTrackSupport(i)))
            }
        )
        return when (type) {
            "video" -> FormatDetector.enrichVideo(base)
            "audio" -> FormatDetector.enrichAudio(base)
            else -> base
        }
    }

    private fun supportLabel(s: Int) = when (s) {
        C.FORMAT_HANDLED -> "supported"
        C.FORMAT_EXCEEDS_CAPABILITIES -> "exceeds device capabilities"
        C.FORMAT_UNSUPPORTED_DRM -> "DRM not supported"
        C.FORMAT_UNSUPPORTED_SUBTYPE -> "codec profile not supported"
        else -> "not supported"
    }

    private fun refreshPlayerInfo() {
        if (released) return
        val tracks = player.currentTracks
        val video = mutableListOf<ProbeTrack>()
        val audio = mutableListOf<ProbeTrack>()
        val subs = mutableListOf<ProbeTrack>()
        var container: String? = null
        tracks.groups.forEachIndexed { gi, g ->
            for (i in 0 until g.length) {
                val t = toProbeTrack(g, gi, i)
                when (g.type) {
                    C.TRACK_TYPE_VIDEO -> video += t
                    C.TRACK_TYPE_AUDIO -> audio += t
                    C.TRACK_TYPE_TEXT -> subs += t
                }
                if (container == null) container = g.getTrackFormat(i).containerMimeType
            }
        }
        val md = player.mediaMetadata
        val title = (md.title ?: md.displayTitle)?.toString().orEmpty()
        val selectedBitrate = (video.firstOrNull { it.selected } ?: video.firstOrNull())?.bitrate.orZero() +
                (audio.firstOrNull { it.selected } ?: audio.firstOrNull())?.bitrate.orZero()
        val live = try { player.isCurrentMediaItemLive } catch (_: Throwable) { false }
        val dur = player.duration

        _playerInfo.value = MediaMetadata(
            title = title,
            format = CodecNames.containerLabel(container).ifEmpty { _streamKind.value.label },
            durationMs = if (live || dur == C.TIME_UNSET || dur < 0) 0L else dur,
            bitrate = selectedBitrate,
            isLive = live,
            videoTracks = video,
            audioTracks = audio,
            subtitleTracks = subs,
            specialFormats = FormatDetector.allFormats(video, audio),
            sources = setOf(MetadataSource.PLAYER)
        )
    }

    private fun Long?.orZero() = this ?: 0L

    // ------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------

    /** Releases the player and everything attached to it. Idempotent. */
    fun release() {
        if (released) return
        released = true
        cancelTimers()
        mainHandler.removeCallbacks(ticker)
        sniffJob?.cancel()
        scope.cancel()
        try { player.removeListener(listener) } catch (_: Throwable) { }
        try { player.release() } catch (_: Throwable) { }
        _isPlaying.value = false
        _isBuffering.value = false
    }

    private companion object {
        const val TAG = "PlayerManager"
        const val TICK_MS = 250L
        const val KEY_RETRY = "retry"
        const val KEY_NO_AUDIO = "noAudio"
        const val KEY_NO_VIDEO = "noVideo"
        const val KEY_SELECT = "select"
        const val KEY_DTS = "dts"
    }
}

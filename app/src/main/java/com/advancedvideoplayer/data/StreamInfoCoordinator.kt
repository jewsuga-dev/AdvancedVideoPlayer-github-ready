package com.advancedvideoplayer.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Collects stream information from independent sources and publishes one merged [StreamInfo].
 *
 *   player (ExoPlayer formats, ICY/ID3 title)   → [playerInfo], pushed by PlayerManager
 *   platform (MediaMetadataRetriever/Extractor) → run here, own timeout
 *   FFprobe (optional, deep)                    → run here, own timeout
 *
 * The sources run in separate coroutines under a SupervisorJob, each wrapped so that a failure
 * (or a hang that hits its timeout) affects only that source. The player never waits for any
 * of this; the UI shows whatever is available and fills in as more arrives.
 *
 * To avoid opening extra connections to a live provider that allows a single one, the probes
 * that need their own connection are skipped for network streams unless [probeNetworkStreams];
 * the player's own tracks/ICY metadata cover those.
 */
class StreamInfoCoordinator(
    private val context: Context,
    playerInfo: StateFlow<MediaMetadata>,
    private val probeNetworkStreams: Boolean = false
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val generation = AtomicInteger(0)

    private data class FileMeta(val name: String = "", val size: Long = 0L)

    private val fileMeta = MutableStateFlow(FileMeta())
    private val platform = MutableStateFlow<MediaMetadata?>(null)
    private val ffprobe = MutableStateFlow<MediaMetadata?>(null)
    private val probing = MutableStateFlow(false)

    val info: StateFlow<StreamInfo> = combine(fileMeta, platform, ffprobe, playerInfo, probing) { f, pl, ff, pi, busy ->
        try {
            StreamInfoMerger.merge(f.name, f.size, ff, pl, pi, busy)
        } catch (t: Throwable) {
            android.util.Log.e("StreamInfo", "Merge failed", t)
            StreamInfo.EMPTY.copy(metadata = MediaMetadata(fileName = f.name))
        }
    }.stateIn(scope, SharingStarted.Eagerly, StreamInfo.EMPTY)

    /** Starts (or restarts) probing for [uri]. Clears everything known about the previous stream first. */
    fun start(uri: Uri) {
        job?.cancel()
        val gen = generation.incrementAndGet()
        platform.value = null
        ffprobe.value = null
        fileMeta.value = FileMeta()
        probing.value = true

        job = scope.launch {
            try {
                val (name, size) = withContext(Dispatchers.IO) {
                    UriInfo.displayName(context, uri) to UriInfo.sizeBytes(context, uri)
                }
                if (gen != generation.get()) return@launch
                fileMeta.value = FileMeta(name, size)

                if (UriInfo.isNetwork(uri) && !probeNetworkStreams) return@launch

                joinAll(
                    launch {
                        val r = isolated("platform") { PlatformProbe.probe(context, uri, name) }
                        if (gen == generation.get()) platform.value = r
                    },
                    launch {
                        val r = isolated("ffprobe") { FfprobeSource.probe(context, uri, name) }
                        if (gen == generation.get()) ffprobe.value = r
                    }
                )
            } finally {
                // A newer start() owns the flag now; only the latest job may clear it.
                if (gen == generation.get()) probing.value = false
            }
        }
    }

    /** Forgets the current stream (no media loaded). */
    fun clear() {
        generation.incrementAndGet()
        job?.cancel()
        platform.value = null
        ffprobe.value = null
        fileMeta.value = FileMeta()
        probing.value = false
    }

    /** Stops all work and removes any temp file. Idempotent. */
    fun release() {
        job?.cancel()
        scope.cancel()
        TempFiles.deleteAll()
    }

    private suspend fun isolated(name: String, block: suspend () -> MediaMetadata?): MediaMetadata? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        android.util.Log.w("StreamInfo", "$name source failed: ${t.message}")
        null
    }
}

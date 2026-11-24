package com.advancedvideoplayer.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs *blocking, non-cancellable* platform calls (MediaMetadataRetriever, MediaExtractor,
 * FFprobe) with a hard deadline.
 *
 * `withTimeout { withContext(IO) { blocking() } }` does NOT work: the coroutine cannot be
 * interrupted inside blocking code, so the "timeout" would only fire after the call returned.
 * Here the blocking work runs in a detached job; the caller stops waiting at the deadline (or on
 * cancellation), [onAbandon] is invoked so the work can be told to stop, and the result of a
 * late finisher is discarded.
 */
internal object Blocking {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** @return the block's result, or null on timeout, cancellation of the work, or any exception. */
    suspend fun <T : Any> call(
        timeoutMs: Long,
        tag: String,
        onAbandon: () -> Unit = {},
        block: () -> T?
    ): T? {
        val job = scope.async {
            try {
                block()
            } catch (t: Throwable) {
                android.util.Log.w("Blocking", "$tag failed: ${t.javaClass.simpleName}: ${t.message}")
                null
            }
        }
        try {
            val result = withTimeoutOrNull(timeoutMs) { job.await() }
            if (result == null && !job.isCompleted) {
                android.util.Log.w("Blocking", "$tag timed out after ${timeoutMs}ms")
            }
            return result
        } finally {
            if (!job.isCompleted) {
                job.cancel()
                try { onAbandon() } catch (_: Throwable) { }
            }
        }
    }
}

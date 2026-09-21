package com.crsmthw.lyra.ui.ilyra

import android.content.Context
import android.util.Log
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Warms Coil's DISK cache with the liked songs' covers so Cover Flow never waits on the network
 * for a tile (Checkpoint C, 2026-09-21). Runs on the app's one `ImageLoader`, whose network
 * fetcher is the token-free image client (`AppContainer.imageOkHttpClient`) — CDN art is not a
 * Web API call and never carries the bearer token.
 *
 * Rules (Cris, 2026-09-20): a CACHE PROBE before any fetch (`imageLoader.diskCache` snapshot by the
 * url — the key `AsyncImage(url)` writes — then the memory cache), BOUNDED concurrency
 * ([MAX_CONCURRENT] in flight), and a bounded decode ([DECODE_PX], memory-cache write disabled: the
 * point is the bytes on disk, not a bitmap). Fetch order is nearest-first around [setFocus] (the
 * Cover Flow highlight), then outward, so what is about to scroll into view arrives first.
 *
 * **Disk cache key (verified from Coil 3.6.2 sources):**
 * `StringMapper` converts a `String` url to a `Uri`; `UriKeyer.key()` returns `uri.toString()`;
 * `NetworkFetcher.diskCacheKey` = `options.diskCacheKey ?: url`, and no code sets `diskCacheKey`.
 * So the disk cache key is the URL string itself (round-tripped through `toUri().toString()`, which
 * is identity for an HTTPS URL). The probe uses the URL string directly.
 *
 * Scoped to the iLyra session: the ViewModel constructs it with `viewModelScope` and it dies with it.
 *
 * CONTRACT (frozen for the round): the public surface below. The data lane fills in the body.
 */
class CoverArtPrefetcher(
    private val imageLoader: ImageLoader,
    private val context: Context,
    private val scope: CoroutineScope,
) {
    /** [ready] covers of [total] distinct urls are on disk (probed or fetched); failures count as done. */
    data class Progress(val ready: Int = 0, val total: Int = 0)

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    // ── Internal state ───────────────────────────────────────────────────────

    private enum class UrlState { PENDING, IN_FLIGHT, READY, FAILED }

    /** Ordered map url -> state; insertion order = list order (the Cover Flow order). */
    private val urlStates = LinkedHashMap<String, UrlState>()

    /** The urls list as an indexable array, kept in sync with [urlStates]. */
    private var urlList = emptyList<String>()

    /** The focus index into [urlList], set by the Cover Flow highlight. */
    @Volatile
    private var focusIndex = 0

    /** Guards the [urlStates] map and [urlList]. */
    private val lock = Any()

    /** The worker job that manages the parallel worker coroutines. */
    private var workerJob: Job? = null

    /**
     * Bumped by [setUrls] after new work lands. A worker that finds nothing PENDING parks on this
     * instead of exiting: an exiting worker races [setUrls] (the job still reads as active, the
     * relaunch is skipped, and the new urls sit unprocessed until the NEXT setUrls). Parked
     * workers cost nothing and die with the scope.
     */
    private val wake = MutableStateFlow(0)

    private var cancelled = false

    /**
     * The covers to have on disk, in list order (blanks skipped, duplicates collapsed). Replaces the
     * previous list; urls already probed or fetched keep their state, so a growing liked list costs
     * only its new entries.
     */
    fun setUrls(urls: List<String>) {
        val distinct = urls.filter { it.isNotBlank() }.distinct()
        synchronized(lock) {
            // Remove urls no longer in the list.
            val newSet = distinct.toSet()
            urlStates.keys.retainAll(newSet)
            // Add new urls as PENDING, keeping known states.
            for (url in distinct) {
                urlStates.putIfAbsent(url, UrlState.PENDING)
            }
            urlList = distinct
        }
        updateProgress()
        ensureWorkerRunning()
        wake.value++
    }

    /** Fetch nearest-first around this index of the last [setUrls] list. Cheap; called per detent. */
    fun setFocus(index: Int) {
        focusIndex = index.coerceIn(0, (urlList.size - 1).coerceAtLeast(0))
        // The workers re-evaluate priority on each pick, so no restart needed — each will naturally
        // pick the nearest PENDING url next time it finishes the current one.
    }

    /** Stops every worker. The object is inert afterwards (called from `onCleared`, belt-and-braces to the scope). */
    fun cancel() {
        cancelled = true
        workerJob?.cancel()
        workerJob = null
    }

    // ── Worker ───────────────────────────────────────────────────────────────

    private fun ensureWorkerRunning() {
        if (cancelled) return
        if (workerJob?.isActive == true) return
        workerJob = scope.launch {
            processUrls()
        }
    }

    /**
     * Launches [MAX_CONCURRENT] parallel workers, once. Each worker loops: claim the nearest PENDING
     * url (atomically marking it IN_FLIGHT so no other worker picks it), probe the disk cache, fetch
     * on a miss, then mark READY or FAILED. With nothing PENDING a worker PARKS on [wake] until
     * [setUrls] adds work — it never exits (see [wake]).
     */
    private suspend fun processUrls() = coroutineScope {
        repeat(MAX_CONCURRENT) {
            launch {
                var seen = wake.value
                while (true) {
                    val url = claimNextPending()
                    if (url == null) {
                        wake.first { it != seen }
                        seen = wake.value
                        continue
                    }
                    processUrl(url)
                }
            }
        }
    }

    /**
     * Atomically claims the PENDING url nearest to [focusIndex], marking it IN_FLIGHT so no other
     * worker picks it. On ties, the later one (scrolling forward) wins: if focus=10 and urls at 9
     * and 11 are both pending, 11 is returned.
     */
    private fun claimNextPending(): String? {
        synchronized(lock) {
            if (urlList.isEmpty()) return null
            val focus = focusIndex.coerceIn(urlList.indices)
            var bestUrl: String? = null
            var bestIdx = -1
            var bestDist = Int.MAX_VALUE

            for (i in urlList.indices) {
                val url = urlList[i]
                if (urlStates[url] != UrlState.PENDING) continue
                val dist = kotlin.math.abs(i - focus)
                // Prefer smaller distance; on equal distance, prefer higher index (forward bias).
                if (dist < bestDist || (dist == bestDist && i > bestIdx)) {
                    bestDist = dist
                    bestUrl = url
                    bestIdx = i
                }
            }
            // Mark IN_FLIGHT so no other worker claims it.
            if (bestUrl != null) {
                urlStates[bestUrl] = UrlState.IN_FLIGHT
            }
            return bestUrl
        }
    }

    private suspend fun processUrl(url: String) {
        // 1. Probe disk cache on IO.
        val onDisk = withContext(Dispatchers.IO) {
            try {
                imageLoader.diskCache?.openSnapshot(url)?.use { true } == true
            } catch (_: Exception) {
                false
            }
        }

        if (onDisk) {
            markState(url, UrlState.READY)
            return
        }

        // 2. Not on disk — fetch (bounded decode, memory cache disabled: we want the bytes on disk).
        val result = withContext(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(Size(DECODE_PX, DECODE_PX))
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .build()
                imageLoader.execute(request)
            } catch (_: Exception) {
                null
            }
        }

        if (result is SuccessResult) {
            markState(url, UrlState.READY)
        } else {
            markState(url, UrlState.FAILED)
        }
    }

    private fun markState(url: String, state: UrlState) {
        synchronized(lock) {
            if (urlStates.containsKey(url)) {
                urlStates[url] = state
            }
        }
        updateProgress()
    }

    private fun updateProgress() {
        val (ready, total) = synchronized(lock) {
            val readyCount = urlStates.values.count { it == UrlState.READY || it == UrlState.FAILED }
            readyCount to urlStates.size
        }
        _progress.value = Progress(ready = ready, total = total)
        if (ready % LOG_INTERVAL == 0 && ready > 0) {
            Log.d(TAG, "Art prefetch: $ready/$total ready")
        }
    }

    companion object {
        const val MAX_CONCURRENT = 4
        /** Decode bound for a prefetch: the disk keeps the CDN bytes; the bitmap is thrown away. */
        const val DECODE_PX = 64
        private const val TAG = "CoverArtPrefetcher"
        private const val LOG_INTERVAL = 50
    }
}

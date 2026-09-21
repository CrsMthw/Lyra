package com.crsmthw.lyra.ui.ipod

import android.content.Context
import coil3.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * Scoped to the iPod session: the ViewModel constructs it with `viewModelScope` and it dies with it.
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

    /**
     * The covers to have on disk, in list order (blanks skipped, duplicates collapsed). Replaces the
     * previous list; urls already probed or fetched keep their state, so a growing liked list costs
     * only its new entries.
     */
    fun setUrls(urls: List<String>) {
        // Skeleton — implemented by the Checkpoint C data lane.
    }

    /** Fetch nearest-first around this index of the last [setUrls] list. Cheap; called per detent. */
    fun setFocus(index: Int) {
        // Skeleton — implemented by the Checkpoint C data lane.
    }

    /** Stops every worker. The object is inert afterwards (called from `onCleared`, belt-and-braces to the scope). */
    fun cancel() {
        // Skeleton — implemented by the Checkpoint C data lane.
    }

    companion object {
        const val MAX_CONCURRENT = 4
        /** Decode bound for a prefetch: the disk keeps the CDN bytes; the bitmap is thrown away. */
        const val DECODE_PX = 64
    }
}

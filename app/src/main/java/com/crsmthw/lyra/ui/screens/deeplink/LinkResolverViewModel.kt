package com.crsmthw.lyra.ui.screens.deeplink

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crsmthw.lyra.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** What the resolver decided about the incoming link. Null while it is still working. */
sealed interface LinkResolution {
    /** The link names something Lyra has a screen (or a playback path) for. */
    data class Resolved(val link: SpotifyLink) : LinkResolution

    /** A search URL, a user profile, a dead short link, a mangled id — anything Lyra can't open. */
    data object Unsupported : LinkResolution
}

/**
 * Turns one raw incoming URL into a [LinkResolution].
 *
 * Plain `open.spotify.com` URLs and `spotify:` URIs resolve synchronously, in the first emission,
 * so those never show the spinner for longer than a frame. A Branch short link
 * (`https://spotify.link/<code>`, which is what Spotify's own share sheet produces today) has to
 * be followed over the network first.
 */
class LinkResolverViewModel(private val client: OkHttpClient) : ViewModel() {

    private val _state = MutableStateFlow<LinkResolution?>(null)
    val state: StateFlow<LinkResolution?> = _state.asStateFlow()

    private var started = false

    /** Idempotent: the screen calls this from a `LaunchedEffect`, which re-runs on a config change. */
    fun resolve(rawUrl: String) {
        if (started) return
        started = true
        viewModelScope.launch {
            parseSpotifyLink(rawUrl)?.let {
                _state.value = LinkResolution.Resolved(it)
                return@launch
            }
            val host     = rawUrl.toHttpUrlOrNull()?.host?.lowercase().orEmpty()
            val expanded = if (host in SPOTIFY_SHORT_LINK_HOSTS) expandShortLink(rawUrl) else null
            val link     = expanded?.let { parseSpotifyLink(it) }
            _state.value = if (link != null) LinkResolution.Resolved(link) else LinkResolution.Unsupported
        }
    }

    /**
     * Follow a Branch short link to the `open.spotify.com` URL behind it.
     *
     * Redirects are followed BY HAND (the client is built with `followRedirects = false`, see
     * [AppContainer.linkResolverClient]) so the hop count is capped and every `Location` is
     * inspected — with OkHttp's own following left on, `header("Location")` reads null at every
     * hop and the body-scrape fallback below would silently become the only path.
     *
     * Branch does not always answer with a 30x: for some user agents it serves an HTML
     * interstitial that carries the destination in `og:url` / a canonical link / a JS redirect,
     * so when a hop produces no `Location` its body is scraped for the first `open.spotify.com`
     * URL (unescaping `\/`, which is how it appears inside Branch's embedded JSON).
     *
     * This client NEVER carries the Spotify bearer token — that is the whole reason it is a
     * separate OkHttpClient from the API one.
     */
    private suspend fun expandShortLink(rawUrl: String): String? = withContext(Dispatchers.IO) {
        var current = rawUrl.toHttpUrlOrNull() ?: return@withContext null
        repeat(MAX_REDIRECTS) {
            val headHop = fetch(current, useHead = true)
            val location = headHop?.location ?: fetch(current, useHead = false).let { getHop ->
                // No redirect at all: this hop IS the interstitial, so read it and stop.
                getHop?.location ?: return@withContext getHop?.body?.let(::scrapeForWebUrl)
            }
            val next = current.resolve(location) ?: return@withContext null
            if (next.host.lowercase() in SPOTIFY_WEB_HOSTS) return@withContext next.toString()
            current = next
        }
        null   // hop limit reached without landing on a Spotify web host
    }

    private fun scrapeForWebUrl(html: String): String? =
        SPOTIFY_WEB_URL_PATTERN.find(html.replace("\\/", "/"))?.value

    /** One hop. Returns null when the request itself failed (offline, timeout, TLS, …). */
    private fun fetch(url: HttpUrl, useHead: Boolean): Hop? = runCatching {
        val request = Request.Builder().url(url).apply { if (useHead) head() }.build()
        client.newCall(request).execute().use { response ->
            Hop(
                location = response.header("Location")?.takeIf { it.isNotBlank() },
                // peekBody caps what is buffered; an interstitial is a few KB.
                body     = if (useHead) null
                           else runCatching { response.peekBody(MAX_BODY_BYTES).string() }.getOrNull(),
            )
        }
    }.getOrNull()

    private data class Hop(val location: String?, val body: String?)

    private companion object {
        const val MAX_REDIRECTS  = 5
        const val MAX_BODY_BYTES = 256L * 1024L
    }
}

class LinkResolverViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        LinkResolverViewModel(container.linkResolverClient) as T
}

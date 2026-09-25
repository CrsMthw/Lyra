package com.crsmthw.lyra.data.remote

import android.content.Context
import com.crsmthw.lyra.data.auth.SpotifyAuthManager
import com.crsmthw.lyra.data.local.EncryptedPrefs
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Manages the Spotify App Remote connection.
 */
class SpotifyRemoteManager(
    private val context       : Context,
    private val encryptedPrefs: EncryptedPrefs,
) {
    private val _connected   = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _connecting  = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    private var _appRemote: SpotifyAppRemote? = null

    suspend fun connectAndPlay(uri: String): Boolean {
        if (!connectSuspend()) return false
        play(uri)
        return true
    }

    /**
     * True only when the SDK itself says the bind is live. `_connected` alone is not enough: it is
     * our own mirror, and a device pass (2026-09-25) saw the fast path taken with Spotify
     * force-stopped, so the IPC `play` went to a dead remote and Spotify was only started by a
     * later call. `SpotifyAppRemote.isConnected()` (spotify-app-remote 0.8.0 — `mIsConnected`,
     * cleared by the SDK's own connection-terminated handler) is the authority.
     */
    private fun liveRemote(): SpotifyAppRemote? = _appRemote?.takeIf { it.isConnected }

    /**
     * Drops a remote the SDK no longer reports as connected, so the next connect starts clean
     * instead of short-circuiting on a stale `_connected`. `disconnect` releases the old binding;
     * it is wrapped because the remote is already dead by definition here.
     */
    private fun dropStaleRemote() {
        val stale = _appRemote ?: run { _connected.value = false; return }
        if (stale.isConnected) return
        runCatching { SpotifyAppRemote.disconnect(stale) }
        _appRemote = null
        _connected.value = false
    }

    // showAuthView(true): one-time Spotify auth dialog on first use; silent thereafter.
    /**
     * Suspends until the App Remote is bound. Short-circuits ONLY when the SDK reports the current
     * remote as connected ([liveRemote]); otherwise a stale remote is dropped and a real connect
     * runs, which resumes after Spotify's `onConnected` — i.e. after a cold Spotify has booted
     * (up to ~30 s). [connecting] is true for exactly that real connect, so it now also fires on
     * the paths that used to short-circuit on a stale flag (a skip / shuffle with Spotify dead).
     */
    suspend fun connectSuspend(): Boolean {
        if (liveRemote() != null) {
            _connected.value = true
            return true
        }
        dropStaleRemote()
        _connecting.value = true
        return try {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val params = ConnectionParams.Builder(encryptedPrefs.clientId)
                        .setRedirectUri(SpotifyAuthManager.REDIRECT_URI)
                        .showAuthView(true)
                        .build()
                    SpotifyAppRemote.connect(context, params, trackingListener(
                        onConnected = { if (cont.isActive) cont.resume(true) },
                        onFailure   = { if (cont.isActive) cont.resume(false) },
                    ))
                }
            }
        } finally {
            _connecting.value = false
        }
    }

    fun connect(onConnected: () -> Unit, onFailure: (Throwable) -> Unit) {
        if (liveRemote() != null) {
            _connected.value = true
            onConnected()
            return
        }
        dropStaleRemote()
        val connectionParams = ConnectionParams.Builder(encryptedPrefs.clientId)
            .setRedirectUri(SpotifyAuthManager.REDIRECT_URI)
            .showAuthView(false)
            .build()
        SpotifyAppRemote.connect(context, connectionParams, trackingListener(
            onConnected = { onConnected() },
            onFailure   = onFailure,
        ))
    }

    /**
     * The one ConnectionListener shape both connects use. The SDK calls `onFailure` again LATER —
     * with `SpotifyConnectionTerminatedException` — when an established bind dies (Spotify
     * force-stopped), so a failure clears the remote only if it is still the one this listener
     * installed; a newer connect's remote is never nulled by an older listener.
     */
    private fun trackingListener(
        onConnected: () -> Unit,
        onFailure  : (Throwable) -> Unit,
    ): Connector.ConnectionListener = object : Connector.ConnectionListener {
        private var installed: SpotifyAppRemote? = null
        override fun onConnected(appRemote: SpotifyAppRemote) {
            installed = appRemote
            _appRemote = appRemote
            _connected.value = true
            onConnected()
        }
        override fun onFailure(throwable: Throwable) {
            val mine = installed
            if (mine != null && _appRemote === mine) _appRemote = null
            if (liveRemote() == null) _connected.value = false
            onFailure(throwable)
        }
    }

    fun play(uri: String) {
        _appRemote?.playerApi?.play(uri)
    }

    /**
     * Seek whatever the App Remote is currently playing to [positionMs] (`PlayerApi.seekTo(long)`).
     *
     * Exists for the 404 fallback: `playerApi.play(uri)` always starts an episode at 0:00, where
     * `me/player/play` resumes it from Spotify's own server-side position. Same
     * `connectSuspend()`-first shape as [skipNext] so it wakes Spotify if the bind has gone away.
     *
     * Fire-and-forget like [play] — the SDK's `CallResult` is discarded, so returning true means
     * the IPC call was dispatched, not that the seek landed. A seek issued before a freshly started
     * item has loaded is dropped, so the caller must let it settle first (see
     * `PlayerViewModel.playTrack`).
     */
    suspend fun seekTo(positionMs: Long): Boolean {
        if (!connectSuspend()) return false
        _appRemote?.playerApi?.seekTo(positionMs)
        return true
    }

    fun pause() {
        _appRemote?.playerApi?.pause()
    }

    fun resume() {
        _appRemote?.playerApi?.resume()
    }

    suspend fun skipNext(): Boolean {
        if (!connectSuspend()) return false
        _appRemote?.playerApi?.skipNext()
        return true
    }

    suspend fun skipPrevious(): Boolean {
        if (!connectSuspend()) return false
        _appRemote?.playerApi?.skipPrevious()
        return true
    }

    suspend fun setShuffle(enabled: Boolean): Boolean {
        if (!connectSuspend()) return false
        _appRemote?.playerApi?.setShuffle(enabled)
        return true
    }

    suspend fun setRepeat(repeatMode: Int): Boolean {
        if (!connectSuspend()) return false
        _appRemote?.playerApi?.setRepeat(repeatMode)
        return true
    }

    fun subscribeToPlayerState(callback: (isPlaying: Boolean, trackUri: String, progressMs: Long) -> Unit) {
        _appRemote?.playerApi?.subscribeToPlayerState()?.setEventCallback { playerState ->
            callback(
                !playerState.isPaused,
                playerState.track?.uri ?: "",
                playerState.playbackPosition
            )
        }
    }

    fun disconnect() {
        SpotifyAppRemote.disconnect(_appRemote)
        _connected.value = false
    }

}

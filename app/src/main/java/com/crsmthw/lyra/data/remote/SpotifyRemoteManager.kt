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

    // showAuthView(true): one-time Spotify auth dialog on first use; silent thereafter.
    suspend fun connectSuspend(): Boolean {
        if (_connected.value && _appRemote != null) return true
        _connecting.value = true
        return try {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val params = ConnectionParams.Builder(encryptedPrefs.clientId)
                        .setRedirectUri(SpotifyAuthManager.REDIRECT_URI)
                        .showAuthView(true)
                        .build()
                    SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
                        override fun onConnected(appRemote: SpotifyAppRemote) {
                            _appRemote = appRemote
                            _connected.value = true
                            if (cont.isActive) cont.resume(true)
                        }
                        override fun onFailure(throwable: Throwable) {
                            _connected.value = false
                            if (cont.isActive) cont.resume(false)
                        }
                    })
                }
            }
        } finally {
            _connecting.value = false
        }
    }

    fun connect(onConnected: () -> Unit, onFailure: (Throwable) -> Unit) {
        val connectionParams = ConnectionParams.Builder(encryptedPrefs.clientId)
            .setRedirectUri(SpotifyAuthManager.REDIRECT_URI)
            .showAuthView(false)
            .build()
        SpotifyAppRemote.connect(context, connectionParams, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                _appRemote = appRemote
                _connected.value = true
                onConnected()
            }
            override fun onFailure(throwable: Throwable) {
                _connected.value = false
                onFailure(throwable)
            }
        })
    }

    fun play(uri: String) {
        _appRemote?.playerApi?.play(uri)
    }

    fun skipToIndex(contextUri: String, index: Int) {
        _appRemote?.playerApi?.skipToIndex(contextUri, index)
    }

    suspend fun connectAndSkipToIndex(contextUri: String, index: Int): Boolean {
        if (!connectSuspend()) return false
        skipToIndex(contextUri, index)
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

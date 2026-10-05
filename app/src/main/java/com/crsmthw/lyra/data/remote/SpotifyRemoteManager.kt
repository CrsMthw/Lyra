package com.crsmthw.lyra.data.remote

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.crsmthw.lyra.data.auth.SpotifyAuthManager
import com.crsmthw.lyra.data.local.EncryptedPrefs
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.PlayerContext
import com.spotify.protocol.types.PlayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "SpotifyRemote"

/**
 * What the LOCAL Spotify app says it is playing, from the App Remote (2026-10-04) — the player-state
 * subscription's events plus an on-demand `PlayerApi.getPlayerState()` read
 * (`SpotifyRemoteManager.refreshLocalState`). [atElapsedMs] is `SystemClock.elapsedRealtime()` when the state arrived, so
 * two snapshots tell whether the position really ADVANCED (`sdkReportsAudible`).
 *
 * Built from `com.spotify.protocol.types.PlayerState` (spotify-app-remote 0.8.0, fields verified
 * with javap): `track.uri / name / artist(s).name+uri / album.name+uri / imageUri.raw / duration /
 * isEpisode`, `isPaused`, `playbackPosition`, `playbackOptions.isShuffling / repeatMode`. Pure data —
 * the mirror mapping (`mirrorFromSdk`, data/player/SdkMirror.kt) is unit-tested from it.
 *
 * [imageUri] is the SDK's raw `spotify:image:<id>`, kept for logging only: nothing in the SDK
 * documents a mapping to an https url (the AAR names no image host), so the mirror never derives
 * art from it.
 */
data class LocalPlayerSnapshot(
    val trackUri   : String?,
    val name       : String?             = null,
    /** `track.artists` (falling back to `track.artist`), as (uri, name) pairs; either may be null. */
    val artists    : List<Pair<String?, String?>> = emptyList(),
    val albumUri   : String?             = null,
    val albumName  : String?             = null,
    val imageUri   : String?             = null,
    val isEpisode  : Boolean             = false,
    val isPaused   : Boolean,
    val positionMs : Long,
    val durationMs : Long,
    /** `playbackOptions.isShuffling` — null when the SDK sent no options. */
    val isShuffling: Boolean?            = null,
    /** `playbackOptions.repeatMode` — 0 off, 1 context, 2 track (the SDK's `Repeat`); null when absent. */
    val repeatMode : Int?                = null,
    val atElapsedMs: Long,
) {
    val artistNames: List<String> get() = artists.mapNotNull { it.second?.takeIf { n -> n.isNotBlank() } }
}

/**
 * What the LOCAL Spotify app reports as its playback CONTEXT (2026-10-04 evening follow-up) — the
 * App Remote's `PlayerApi.subscribeToPlayerContext()` events (`com.spotify.protocol.types.PlayerContext`,
 * spotify-app-remote 0.8.0, verified with javap: public final `uri` / `title` / `subtitle` / `type`
 * Strings; there is NO `getPlayerContext()`, so the subscription is the only source and nothing reads
 * it on demand). Next / previous on the SDK mirror source use the local app's own skip only when this
 * is a real context (`sdkSkipAllowed`, data/player/SdkMirror.kt); a blank uri, or a single track /
 * episode, is a LONE uri and the skip goes through the neighbour restore instead.
 * [atElapsedMs] is `SystemClock.elapsedRealtime()` when the event arrived.
 */
data class LocalPlayerContext(
    val uri        : String?,
    val title      : String?,
    val type       : String?,
    val atElapsedMs: Long,
)

/**
 * The SDK `PlayerState` → [LocalPlayerSnapshot]. Every field read defensively: the SDK's types are
 * Gson-deserialised over IPC exactly like the Web API's, so a "final" field can be null.
 */
@Suppress("SENSELESS_COMPARISON", "UNNECESSARY_SAFE_CALL")
private fun PlayerState.toSnapshot(atElapsedMs: Long): LocalPlayerSnapshot {
    val t = track
    val artistList = t?.artists?.filterNotNull()?.takeIf { it.isNotEmpty() }
        ?: listOfNotNull(t?.artist)
    val options = playbackOptions
    return LocalPlayerSnapshot(
        trackUri    = t?.uri?.takeIf { it.isNotBlank() },
        name        = t?.name,
        artists     = artistList.map { it.uri to it.name },
        albumUri    = t?.album?.uri?.takeIf { it.isNotBlank() },
        albumName   = t?.album?.name,
        imageUri    = t?.imageUri?.raw,
        isEpisode   = t?.isEpisode == true,
        isPaused    = isPaused,
        positionMs  = playbackPosition,
        durationMs  = t?.duration ?: 0L,
        isShuffling = options?.isShuffling,
        repeatMode  = options?.repeatMode,
        atElapsedMs = atElapsedMs,
    )
}

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

    private val _localState = MutableStateFlow<LocalPlayerSnapshot?>(null)
    /** The local Spotify app's own player state while the App Remote is bound; null otherwise. */
    val localState: StateFlow<LocalPlayerSnapshot?> = _localState.asStateFlow()
    private var localStateSubscription: Subscription<PlayerState>? = null

    private val _localContext = MutableStateFlow<LocalPlayerContext?>(null)
    /**
     * The local Spotify app's playback context while the App Remote is bound ([LocalPlayerContext]);
     * null while unbound and until the subscription's first event. Same lifecycle as [localState]:
     * armed in [subscribeLocalState] on every connect, cancelled and nulled in [cancelLocalState]
     * (a failure of our own remote, a dropped stale remote, [disconnect]).
     */
    val localContext: StateFlow<LocalPlayerContext?> = _localContext.asStateFlow()
    private var localContextSubscription: Subscription<PlayerContext>? = null
    private var contextSubscribedAtElapsed = 0L

    /**
     * True from a single-item SDK [play] (a track / an episode uri) until the local app's next
     * context event: the event that reports the lone item can lag the dispatch, and until it lands
     * [localContext] still shows the context the local app had BEFORE — which it has already
     * replaced. Such a play really does leave the local app with one item until something else loads
     * a context, and loading one is a context change, which reports — so the flag can never stick on
     * a real context. The `loneUriFlag` of `sdkSkipAllowed`.
     */
    @Volatile var lonePlaySinceContext: Boolean = false
        private set

    /**
     * Re-armed on every connect (`trackingListener.onConnected`); the [Subscription] is KEPT so
     * [cancelLocalState] can end it on a failure, a dropped stale remote and [disconnect].
     * `setErrorCallback` is `PendingResultBase`'s and returns a `PendingResult`, so it is a separate
     * statement on the held subscription, never chained into the assignment.
     */
    private fun subscribeLocalState(remote: SpotifyAppRemote) {
        cancelLocalState()
        localStateSubscription = runCatching {
            val sub: Subscription<PlayerState> = remote.playerApi.subscribeToPlayerState()
                .setEventCallback { ps -> publishLocalState(ps) }
            sub.setErrorCallback { e -> Log.w(TAG, "player-state subscription error: ${e.message}") }
            sub
        }.onFailure { Log.w(TAG, "player-state subscription failed: ${it.message}") }.getOrNull()
        contextSubscribedAtElapsed = SystemClock.elapsedRealtime()
        localContextSubscription = runCatching {
            val sub: Subscription<PlayerContext> = remote.playerApi.subscribeToPlayerContext()
                .setEventCallback { pc -> publishLocalContext(pc) }
            sub.setErrorCallback { e -> Log.w(TAG, "player-context subscription error: ${e.message}") }
            sub
        }.onFailure { Log.w(TAG, "player-context subscription failed: ${it.message}") }.getOrNull()
    }

    /**
     * One context event → [localContext]. Every event is logged (uri / type / title / time since the
     * subscribe): what 9.1.88 reports after an SDK `play(trackUri)` — a blank uri, or the track's own —
     * is not documented anywhere, and the skip rule reads exactly that. The fields are read
     * defensively: the SDK's types arrive Gson-deserialised over IPC, so a "final" field can be null.
     */
    @Suppress("SENSELESS_COMPARISON", "UNNECESSARY_SAFE_CALL")
    private fun publishLocalContext(pc: PlayerContext?) {
        if (pc == null) return
        val now = SystemClock.elapsedRealtime()
        val ctx = runCatching {
            LocalPlayerContext(
                uri         = pc.uri?.takeIf { it.isNotBlank() },
                title       = pc.title,
                type        = pc.type,
                atElapsedMs = now,
            )
        }.onFailure { Log.w(TAG, "player-context unreadable: ${it.message}") }.getOrNull() ?: return
        lonePlaySinceContext = false
        _localContext.value = ctx
        Log.d(TAG, "local context → uri=${ctx.uri ?: "(blank)"} type=${ctx.type} title=${ctx.title} " +
                   "(${now - contextSubscribedAtElapsed} ms after the subscribe)")
    }

    private fun publishLocalState(ps: PlayerState?): LocalPlayerSnapshot? {
        if (ps == null) return null
        val snap = runCatching { ps.toSnapshot(SystemClock.elapsedRealtime()) }
            .onFailure { Log.w(TAG, "player-state unreadable: ${it.message}") }.getOrNull() ?: return null
        _localState.value = snap
        return snap
    }

    /**
     * One on-demand read of the local app's player state (`PlayerApi.getPlayerState()`), published
     * to [localState] — the subscription fires on CHANGES (a track, a pause, a seek), not as the
     * position runs, so the SDK mirror and the "audible" check read a fresh position this way.
     * Only over a LIVE bind: a read never connects (that would start a dead Spotify). Null when
     * unbound, on an SDK error, or after [timeoutMs].
     */
    suspend fun refreshLocalState(timeoutMs: Long = 1_000L): LocalPlayerSnapshot? {
        val remote = liveRemote() ?: return null
        val ps = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<PlayerState?> { cont ->
                runCatching {
                    val call = remote.playerApi.playerState
                    call.setResultCallback { r -> if (cont.isActive) cont.resume(r) }
                    call.setErrorCallback { e ->
                        Log.d(TAG, "getPlayerState failed: ${e.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        } ?: return null
        return publishLocalState(ps)
    }

    private fun cancelLocalState() {
        localStateSubscription?.let { sub -> runCatching { sub.cancel() } }
        localStateSubscription = null
        _localState.value = null
        localContextSubscription?.let { sub -> runCatching { sub.cancel() } }
        localContextSubscription = null
        _localContext.value = null
        lonePlaySinceContext = false
    }

    /** True when the SDK reports a live bind — the play button's SDK-resume branch needs one. */
    fun hasLiveRemote(): Boolean = liveRemote() != null

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
        cancelLocalState()
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
            Log.d(TAG, "connectSuspend: live remote (SDK isConnected) — short-circuit")
            _connected.value = true
            return true
        }
        Log.d(TAG, "connectSuspend: " + when {
            _appRemote != null -> "stale remote dropped (SDK isConnected=false, flag=${_connected.value}) — real connect"
            else               -> "no remote — real connect"
        })
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
            subscribeLocalState(appRemote)
            onConnected()
        }
        override fun onFailure(throwable: Throwable) {
            val mine = installed
            if (mine != null && _appRemote === mine) {
                _appRemote = null
                cancelLocalState()
            }
            if (liveRemote() == null) _connected.value = false
            onFailure(throwable)
        }
    }

    fun play(uri: String) {
        val api = _appRemote?.playerApi ?: return
        // Before the dispatch, so a context event that reports the lone item clears it.
        if (uri.startsWith("spotify:track:") || uri.startsWith("spotify:episode:")) lonePlaySinceContext = true
        api.play(uri)
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

    fun disconnect() {
        cancelLocalState()
        SpotifyAppRemote.disconnect(_appRemote)
        _connected.value = false
    }

}

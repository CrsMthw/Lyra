package com.crsmthw.lyra.data.player

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.util.Log
import com.crsmthw.lyra.data.local.PlaybackOriginStore
import com.crsmthw.lyra.data.remote.LocalPlayerSnapshot
import com.crsmthw.lyra.data.remote.SpotifyRemoteManager
import com.crsmthw.lyra.data.remote.httpStatus
import com.crsmthw.lyra.data.remote.isHttp
import com.crsmthw.lyra.data.remote.retryAfterSeconds
import com.crsmthw.lyra.data.remote.model.PlayerStateResponse
import com.crsmthw.lyra.data.remote.model.SpotifyDevice
import com.crsmthw.lyra.data.remote.model.describe
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import com.crsmthw.lyra.data.repository.SpotifyRepository
import com.crsmthw.lyra.service.LyraForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * After Lyra itself records a play's origin, a context change the poll reports within this window
 * is NOT recorded: a poll already in flight when the play went out still describes the PREVIOUS
 * playback and would overwrite the origin Lyra just wrote (a Liked play replaced by the playlist
 * that was playing a second earlier).
 */
private const val ORIGIN_POLL_QUIET_MS = 6_000L
/** Quick 1 s retries of a poll that failed on the network itself (see `pollQuickRetries`). */
private const val POLL_QUICK_RETRIES = 3
/** The first poll waits this long at most for Android to report this uid's network allowed. */
private const val NETWORK_ALLOWED_WAIT_MS = 3_000L

/** The longest a single 429 may gate the PLAYER family for, whatever `Retry-After` says (30 min). */
private const val MAX_PLAYER_BACKOFF_MS = 30L * 60L * 1_000L
/** The LIBRARY family honours the header up to 6 h: retrying into a ban only extends it. */
private const val MAX_LIBRARY_BACKOFF_MS = 6L * 60L * 60L * 1_000L
/** A failed SDK-mirror catalog lookup (`GET tracks/{id}`) is not retried for that uri before this. */
private const val SDK_LOOKUP_RETRY_MS = 60_000L

/** The play button's SDK resume waits this long for `me/player` to report a playing item (2026-10-04). */
private const val SDK_RESUME_CONFIRM_MS = 5_000L

/** How long one EMPTY-after-uris observation keeps uris bodies suspect (see `noteUrisBodyEmptied`). */
private const val URIS_SUSPECT_MS = 6L * 60L * 60L * 1_000L

/**
 * Spotify rate-limits PER ENDPOINT FAMILY, not per app (device pass 2026-09-25 evening: `me/tracks`
 * banned with `Retry-After=13657` s while every `me/player` call kept working). One shared gate
 * therefore froze the player for a library ban. [PLAYER] = `me/player/…`; [LIBRARY] = `me/tracks`,
 * `me/albums`, `me/playlists`, `me/shows`, `me` — the indexer's and iLyra's browse calls.
 */
enum class RateLimitFamily { PLAYER, LIBRARY }

data class PlayerState(
    val isPlaying             : Boolean        = false,
    val currentTrack          : SpotifyTrack?  = null,
    val progressMs            : Long           = 0L,
    val durationMs            : Long           = 0L,
    val shuffleEnabled        : Boolean        = false,
    val repeatState           : String         = "off",
    /**
     * Does the current playback have a CONTEXT (playlist / album / artist / show), as opposed to a
     * bare `uris` list? `QueueViewModel` needs it because `me/player/queue` echoes the playing item
     * back as the queue head only in the context-less case.
     *
     * Unlike [repeatState] it has no optimistic lock of its own — nothing in the app mutates it
     * locally — but it DOES follow the mid-transfer track lock: `item` and `context` go null
     * together while Spotify switches devices, and taking it raw there would read "no context" for
     * a poll window while [currentTrack] is still held at the previous track.
     */
    val hasContext            : Boolean        = false,
    /**
     * The playback context's uri (`response.context?.uri`) — what the play button's wake restore
     * rebuilds the queue from (`planWakeRestore`). Locked with the track exactly like
     * [hasContext], and — like every field but `isPlaying` — left alone by a 204, so it survives
     * Spotify dying, which is the whole point of reading it. Null for a bare `uris` play.
     */
    val contextUri            : String?        = null,
    val sleepTimerMinutes     : Int            = 0,
    val sleepTimerTotalMinutes: Int            = 0,
    val currentDevice         : SpotifyDevice? = null,
)

class PlayerStateManager(
    private val context      : Context,
    private val repository   : SpotifyRepository,
    private val remoteManager: SpotifyRemoteManager,
    private val originStore  : PlaybackOriginStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private var pollJob        : Job? = null

    /** The poll's last answer shape — logged once per CHANGE, so a device test reads a history, not 3 s spam. */
    private var lastPollShape: String? = null
    private fun notePollShape(shape: String) {
        if (shape == lastPollShape) return
        lastPollShape = shape
        Log.d("PlayerStateManager", "poll shape → $shape")
    }

    // ── SDK mirror (2026-10-04 evening, docs/PLAYER.md → SDK mirror) ──────────

    private val _mirrorSource = MutableStateFlow(MirrorSource.WEB_API)
    /**
     * Which source the mirror reads: the Web API, or — while `me/player` is BLIND ([webViewBlind]:
     * a 204, or an item-less 200 on THIS phone) and the App Remote is bound with a fresh local
     * snapshot — the local Spotify app's own player state (Spotify Android 9.1.88 drops its Connect
     * session mid-playback, register A3). The Queue screen shows a quiet note while it is [MirrorSource.SDK].
     */
    val mirrorSource: StateFlow<MirrorSource> = _mirrorSource
    private fun noteMirrorSource(source: MirrorSource, why: String) {
        if (_mirrorSource.value == source) return
        _mirrorSource.value = source
        // Each SDK stretch confirms audio afresh — never against a base or a confirmation from before.
        sdkPlayingGate.reset()
        if (source == MirrorSource.WEB_API) dropoutLatched.value = false
        Log.d("PlayerStateManager", when (source) {
            MirrorSource.SDK     -> "mirror: SDK source ($why)"
            MirrorSource.WEB_API -> "mirror: Web API source ($why)"
        })
    }

    /**
     * A latched "Connect dropped while the local app PLAYED" flag (Cris, device pass 2026-10-04
     * 23:2x): set the first time the SDK source mirrors a non-paused local report, cleared when the
     * Web API source returns. While it holds, the dropout line and the player's "Open Spotify"
     * hint STAY through a pause (the un-latched predicate alone hid them the moment the user
     * paused); an idle 204 with the local app paused never sets it — Connect merely idle.
     */
    private val dropoutLatched = MutableStateFlow(false)

    /**
     * The Queue screen's dropout line ([connectDropoutNoteShown]): the SDK source AND the local app
     * reporting an item NOT paused — a real dropout during playback, never Connect merely idle with
     * the App Remote bound (2026-10-04 evening follow-up). The source itself is unchanged by this.
     */
    val connectDropout: StateFlow<Boolean> =
        combine(_mirrorSource, remoteManager.localState, dropoutLatched) { source, local, latched ->
            connectDropoutNoteShown(source, local, latched)
        }.stateIn(scope, SharingStarted.Eagerly, false)

    /** One path per tap: while the mirror reads the SDK (and the bind is live) the controls go to the SDK only. */
    private fun sdkSourceActive(): Boolean =
        _mirrorSource.value == MirrorSource.SDK && remoteManager.hasLiveRemote()

    /**
     * The catalog copy of the track the local app reports (`GET tracks/{id}`, one per uri, display
     * only — its art): the SDK's own `spotify:image:` uri has no documented https form, so the
     * mirror never derives art from it, and a lookup is the only verified way to show the right art
     * for a song the mirror did not already hold. Never a `me/player` call (that family is blind).
     */
    private val sdkTrackDetails = object : LinkedHashMap<String, SpotifyTrack>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SpotifyTrack>?) = size > 200
    }
    /** Lookups in flight, and per-uri "don't retry before" stamps for a failed one (60 s; a 429 backs off globally). */
    private val sdkLookupInFlight = HashSet<String>()
    private val sdkLookupFailedUntil = HashMap<String, Long>()
    @Volatile private var sdkLookupBackoffUntil = 0L


    /**
     * The mirror's audible gate ([SdkPlayingGate], review 2026-10-04 evening): a single
     * `isPaused == false` report (a buffering / stalled load) never turns the SDK mirror PLAYING —
     * the position must be seen advancing first. Reset on a switch to the Web API and on sign-out.
     */
    private val sdkPlayingGate = SdkPlayingGate()

    /**
     * Bumped by [resetForSignOut] (review 2026-10-04 evening). A `me/player` answer (or a local read,
     * or a catalog lookup) that started under an older epoch is DROPPED: a poll in flight when the
     * user logged out must not write the old account's playback back into the mirror, re-save its
     * context to the origin file, or flip the source back to the SDK.
     */
    @Volatile private var signOutEpoch = 0L

    /** "Does this 200's reporting device equal THIS phone" for [webViewBlind]. */
    private fun localDeviceCheck(device: SpotifyDevice?): (String) -> Boolean = { id ->
        device != null && device.id == id && isLocalDevice?.invoke(device) == true
    }

    /**
     * A local snapshot may drive the mirror unless a play Lyra issued is still pending on ANOTHER
     * item: the pending hold (incl. a kept "didn't report" hold) owns the screen until its own item
     * is reported, and right after a uris play the local app still reports the stopped prior song.
     */
    private fun sdkMayMirror(snap: LocalPlayerSnapshot): Boolean {
        val h = pendingHold ?: return true
        if (!holdIsLive(System.currentTimeMillis())) return true
        return snap.trackUri != null && snap.trackUri == h.uri
    }

    /**
     * Writes the SDK-derived mirror, honouring the same optimistic locks as the poll. The snapshot
     * is offered to [sdkPlayingGate] first, whatever the locks say (the gate must stay warm under a
     * lock). The SOURCE is re-checked INSIDE the update (review 2026-10-04 evening): the poll flips
     * it to the Web API before writing the Web API's state, so an apply that passed its caller's
     * check a moment earlier either lands before that write (which then overwrites it) or re-runs,
     * sees the Web API source and leaves the state alone. [epoch] (a caller's captured
     * [signOutEpoch]) drops the write once a sign-out has happened. Returns whether it wrote.
     */
    private fun applySdkMirror(snap: LocalPlayerSnapshot, device: SpotifyDevice?, epoch: Long = signOutEpoch): Boolean {
        val now = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val audible = sdkPlayingGate.offer(snap)
        var applied = false
        _state.update { prev ->
            if (_mirrorSource.value != MirrorSource.SDK || signOutEpoch != epoch) {
                applied = false
                return@update prev
            }
            applied = true
            val known = snap.trackUri?.let { u -> synchronized(sdkTrackDetails) { sdkTrackDetails[u] } }
            val m = mirrorFromSdk(snap, prev, known, nowElapsed, audible)
            m.copy(
                isPlaying      = if (now < isPlayingLockUntil) prev.isPlaying else m.isPlaying,
                shuffleEnabled = if (now < shuffleLockUntil) prev.shuffleEnabled else m.shuffleEnabled,
                repeatState    = if (now < repeatLockUntil) prev.repeatState else m.repeatState,
                currentDevice  = device ?: prev.currentDevice,
            )
        }
        if (!applied) return false
        if (snap.trackUri != null && !snap.isPaused) dropoutLatched.value = true
        if (_state.value.isPlaying) {
            if (progressTickJob?.isActive != true) startProgressTick()
        } else {
            progressTickJob?.cancel()
        }
        maybeStartService()
        maybeLookUpSdkTrack(snap, epoch)
        return true
    }

    /**
     * One catalog lookup per uri the local app reports, kept in [sdkTrackDetails] (an LRU of 200,
     * by uri) so a song the mirror has ALREADY looked up gets its art back at once when the local
     * app returns to it — the first build kept a single "last looked-up" track plus a set of seen
     * uris, so Previous during a dropout showed the song with no art for good (Cris, 2026-10-04
     * 23:2x). A failed lookup is retried for that uri after 60 s; a 429 backs every lookup off.
     */
    private fun maybeLookUpSdkTrack(snap: LocalPlayerSnapshot, epoch: Long) {
        val uri = snap.trackUri ?: return
        if (!uri.startsWith("spotify:track:")) return
        val shown = _state.value.currentTrack
        if (shown != null && shown.uri == uri && shown.artUrl.isNotEmpty()) return
        val now = System.currentTimeMillis()
        if (now < sdkLookupBackoffUntil) return
        synchronized(sdkTrackDetails) {
            if (sdkTrackDetails.containsKey(uri)) return
            if ((sdkLookupFailedUntil[uri] ?: 0L) > now) return
            if (!sdkLookupInFlight.add(uri)) return
        }
        scope.launch {
            repository.getTrack(uri.substringAfterLast(':')).fold(
                onSuccess = { t ->
                    synchronized(sdkTrackDetails) {
                        sdkLookupInFlight.remove(uri)
                        // Stored under the REQUESTED uri even on a mismatch (the mirror then ignores
                        // it, and the uri is not looked up again every report).
                        if (signOutEpoch == epoch) sdkTrackDetails[uri] = t
                    }
                    if (t.uri != uri || signOutEpoch != epoch) return@fold
                    if (_mirrorSource.value == MirrorSource.SDK) {
                        _state.update { s -> if (s.currentTrack?.uri == uri) s.copy(currentTrack = t) else s }
                    }
                },
                onFailure = { e ->
                    synchronized(sdkTrackDetails) {
                        sdkLookupInFlight.remove(uri)
                        if (sdkLookupFailedUntil.size > 200) sdkLookupFailedUntil.clear()
                        sdkLookupFailedUntil[uri] = System.currentTimeMillis() + SDK_LOOKUP_RETRY_MS
                    }
                    if (e.isHttp(429)) {
                        sdkLookupBackoffUntil = System.currentTimeMillis() +
                            ((e.retryAfterSeconds() ?: 0L) * 1_000L).coerceIn(60_000L, MAX_LIBRARY_BACKOFF_MS)
                    }
                    Log.d("PlayerStateManager", "mirror: track lookup for $uri failed — ${e.message?.take(120)}")
                },
            )
        }
    }

    /**
     * The blind poll's SDK branch: with a live bind, read the local app once
     * (`SpotifyRemoteManager.refreshLocalState`; the subscription's last event otherwise) and, when that snapshot is fresh
     * and may drive the mirror, write it. Returns false when the Web API's view must stand.
     */
    private suspend fun mirrorFromLocalApp(device: SpotifyDevice?, epoch: Long): Boolean {
        if (!remoteManager.hasLiveRemote()) return false
        val snap = remoteManager.refreshLocalState() ?: remoteManager.localState.value ?: return false
        // The read can take up to a second: a sign-out meanwhile drops it (the source stays WEB_API).
        if (signOutEpoch != epoch) return false
        if (!sdkSnapshotFresh(snap, SystemClock.elapsedRealtime())) return false
        if (!sdkMayMirror(snap)) return false
        noteMirrorSource(MirrorSource.SDK, "me/player blind, the App Remote reports ${snap.trackUri} " +
                         if (snap.isPaused) "paused" else "playing")
        if (!applySdkMirror(snap, device, epoch) && signOutEpoch != epoch) {
            // A sign-out landed between the flip and the write: hand the source back.
            noteMirrorSource(MirrorSource.WEB_API, "signed out")
            return false
        }
        return true
    }


    // ── Pending play hold + poll bookkeeping (2026-10-04) ─────────────────────

    /**
     * The play Lyra just issued, held in the mirror while Spotify has not reported it yet: a poll
     * with `item: null` (Spotify Android 9.1.88 answers an accepted `uris` body with exactly that,
     * then plays nothing) must not blank the player to "Nothing playing" under the waking
     * spinner. Live only for the play-request generation that armed it and until [untilMs].
     *
     * It holds ONLY against `item: null` and against [priorUri] (the song showing before the tap,
     * still reported for a poll or two) — any OTHER non-null item is real playback and ends it
     * (review 2026-10-04: a hold against everything hid a song Spotify really played).
     * [keptUnconfirmed]: the confirm hit its ceiling — the tapped song stays on screen, paused,
     * under "didn't report", against `item: null` polls only, until any item is reported or the
     * next play request; [untilMs] no longer applies.
     */
    private data class PendingHold(
        val uri            : String,
        val generation     : Long,
        val untilMs        : Long,
        val priorUri       : String?,
        val keptUnconfirmed: Boolean = false,
    )
    @Volatile private var pendingHold: PendingHold? = null
    /** "poll: EMPTY while a play is pending" is logged once per hold, not every 3 s. */
    @Volatile private var holdLogged = false

    fun holdPendingItem(uri: String, generation: Long, untilMs: Long, priorUri: String?) {
        pendingHold = PendingHold(uri, generation, untilMs, priorUri)
        holdLogged = false
    }

    /** Moves the caller's own hold's ceiling (the confirm's deadline, which starts after the PUT). */
    fun extendPendingItem(generation: Long, untilMs: Long) {
        val h = pendingHold ?: return
        if (h.generation == generation && !h.keptUnconfirmed && untilMs > h.untilMs) {
            pendingHold = h.copy(untilMs = untilMs)
        }
    }

    /** The confirm's ceiling: keep the tapped item against `item: null` only (see [PendingHold]). */
    fun keepPendingItemUnconfirmed(generation: Long) {
        val h = pendingHold ?: return
        if (h.generation == generation) pendingHold = h.copy(keptUnconfirmed = true)
    }

    /**
     * Ends the hold — only the caller's own (a newer play's hold is never released by an older
     * one), and never a [PendingHold.keptUnconfirmed] one: that ends on a reported item or the next
     * play request.
     */
    fun releasePendingItem(generation: Long) {
        val h = pendingHold ?: return
        if (h.generation == generation && !h.keptUnconfirmed) pendingHold = null
    }

    /**
     * Shows the tapped [track] under the spinner at once (track, 0:00, its duration) with the
     * CALLER's [contextUri] (null for a uris play) — never the previous playback's context, which a
     * kept hold would otherwise leave beside the tapped song for the play button's restore to plan
     * from (review 2026-10-04). The next poll that reports a real item replaces it; an EMPTY poll
     * keeps it while the hold is live.
     */
    fun seedPendingItem(track: SpotifyTrack, contextUri: String?) {
        val ctx = contextUri?.takeIf { it.isNotBlank() }
        _state.update {
            it.copy(currentTrack = track, progressMs = 0L, durationMs = track.durationMs,
                    contextUri = ctx, hasContext = ctx != null)
        }
    }

    private fun holdIsLive(now: Long): Boolean {
        val h = pendingHold ?: return false
        return h.generation == playRequestGeneration.get() && (h.keptUnconfirmed || now < h.untilMs)
    }

    /**
     * Every SUCCESSFUL poll (200 or 204) — never written on a failure, so a DNS blip is not read
     * as "no device". A SharedFlow, not a StateFlow (review 2026-10-04): an observation EQUAL to
     * the previous one must still reach the collector, or a flag set after it (the player's
     * "didn't report" line, the "Open Spotify" hint) would never be cleared by the steady state.
     */
    private val _lastPoll = MutableSharedFlow<PollObservation>(
        replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val lastPoll: SharedFlow<PollObservation> = _lastPoll
    /** The last successful poll, or null before the first one. */
    fun lastPollValue(): PollObservation? = _lastPoll.replayCache.lastOrNull()

    /**
     * Is this Spotify Connect device THIS phone? Installed by `PlayerViewModel` (its strict
     * `isThisPhone`: a remembered id or a handheld named like this phone). The play button's SDK
     * resume runs over an EMPTY player only when it is this phone's (review 2026-10-04): an idle
     * speaker reporting `item: null` must get the Web API resume, not a local SDK one.
     */
    var isLocalDevice: ((SpotifyDevice) -> Boolean)? = null

    /**
     * Process-lifetime flag: a bare `uris` body was ACCEPTED and then left the player EMPTY
     * (Spotify Android 9.1.88) — armed by a tap's confirm or a wake restore's confirm. While it is
     * armed a uris play goes straight to its context fallback (`PlayerViewModel.startPlay`), and so
     * does a wake restore's planned uris body (`PlayerViewModel.wakeBodyWhileSuspect`); while it is
     * NOT, both send the uris body exactly as 4.1.0 did (D', Cris 2026-10-04: a user on an
     * unaffected Spotify keeps the URI queue). Six hours, then the uris body is tried again — the
     * Spotify client may have been fixed.
     */
    @Volatile private var urisBodySuspectUntilMs = 0L
    fun noteUrisBodyEmptied() {
        urisBodySuspectUntilMs = System.currentTimeMillis() + URIS_SUSPECT_MS
        Log.w("PlayerStateManager", "uris bodies SUSPECT for ${URIS_SUSPECT_MS / 3_600_000L} h — an accepted " +
              "uris play left the player EMPTY; plays go to their context fallback first")
    }
    fun urisBodiesSuspect(): Boolean = System.currentTimeMillis() < urisBodySuspectUntilMs
    private var progressTickJob: Job? = null
    private var sleepTimerJob  : Job? = null

    private var isPlayingLockUntil: Long = 0L
    private var shuffleLockUntil  : Long = 0L
    private var repeatLockUntil   : Long = 0L
    private var pollBackoffUntil  : Long = 0L   // the PLAYER family's window
    private var libraryBackoffUntil: Long = 0L  // the LIBRARY family's window
    private val _libraryRateLimitUntil = MutableStateFlow(0L)
    /** Epoch ms until which the LIBRARY family is gated (0 = open) — the Library bar's warning icon reads it. */
    val libraryRateLimitUntil: StateFlow<Long> = _libraryRateLimitUntil
    private var trackLockUntil    : Long = 0L

    @Volatile private var serviceRunning = false

    // Fired at the START of each SDK 404 path — before connectAndPlay/skipNext/etc.
    // PlayerViewModel sets isWakingUp=true here. Fires even when connectSuspend() short-circuits
    // on a stale connection, so the indeterminate indicator always shows during position restore.
    var onWakeOperationStart: (() -> Unit)? = null

    // Called after each SDK-wake operation completes (playPause/skip 404 paths).
    // PlayerViewModel sets this to clear its isWakingUp flag precisely when the operation is done.
    var onWakeOperationComplete: (() -> Unit)? = null

    /**
     * The play button's wake restore (set by `PlayerViewModel.init`): runs the same
     * `restoreAfterWake` as a track tap, with the body `planWakeRestore` picks and the progress at
     * the tap as the base position. Returns false when it could not run at all (the ViewModel is
     * gone), in which case [playPause] falls back to the bare single-uri `connectAndPlay`.
     * It owns clearing the waking state; [onWakeOperationComplete] is not fired when it ran.
     * `step` is 0 for the play button, +1 / -1 for next / previous while Spotify is dead: the
     * restore lands on the neighbour (a uris queue Lyra knows the order of) or on the current item
     * and then skips (a context, whose order Spotify owns) — before 2026-09-25 pm a skip on a dead
     * Spotify went to the App Remote's skip on an EMPTY player and cleared the spinner on a timer.
     */
    var onWakeRestore: (suspend (track: SpotifyTrack, pausedProgressMs: Long, step: Int) -> Boolean)? = null

    // ── Playback origin + play-request generation ─────────────────────────────

    /**
     * Bumped by every user play/pause/skip request. A wake restore captures it when it starts and
     * abandons silently — no body, no waking-state change — once it moves: the device wait can
     * take a minute, and a body landing after the user picked something else (or paused) would
     * override them. Every Lyra-issued play also records its origin, so [recordPlayOrigin] bumps it.
     */
    private val playRequestGeneration = AtomicLong(0L)
    val playGeneration: Long get() = playRequestGeneration.get()
    fun notePlayRequest(): Long = playRequestGeneration.incrementAndGet()

    /**
     * True while a play made with the user's shuffle ON still owes its re-assert
     * (PlayerViewModel.startPlay: for a multi-uri body the bracket shuffle OFF → play → shuffle ON;
     * for a context / single body just the ON after the play — Spotify can DROP shuffle when a
     * context starts after a `uris` playback, device pass 2026-09-25 A3). The mirror then
     * reads `shuffleEnabled = false`, so without this a second tap inside the ~1.5 s window — or a
     * pause / skip / Library play superseding a wake restore — would read "shuffle is off" and the
     * user's shuffle would stay off for good. The next play treats `mirror || owed` as the user's
     * setting; cleared by a successful ON, by an explicit shuffle choice ([toggleShuffle],
     * [setShuffle], a caller's `shuffle` argument, a shuffle-play), never by a 429.
     */
    @Volatile var shuffleOwedOn: Boolean = false
        private set
    fun markShuffleOwedOn() { shuffleOwedOn = true }
    fun clearShuffleOwed()  { shuffleOwedOn = false }

    /**
     * ONE writer for the origin file: two writes launched close together (a Lyra play and a poll
     * observation) must land in the order they were issued, or the older origin would be what
     * survives on disk. `synchronized` in the store protects the file, not the order.
     */
    private val originWriter = Dispatchers.IO.limitedParallelism(1)

    @Volatile private var lastLyraOriginAt = 0L
    /** The context uri the poll last reported (null included), so a CHANGE is what gets recorded. */
    @Volatile private var lastObservedContextUri: String? = null

    /**
     * Records where a play Lyra is about to issue comes from, and counts as a new play request.
     * The write runs on `Dispatchers.IO`; the generation bump is synchronous, so a caller that
     * reads [playGeneration] right after sees its own request.
     */
    fun recordPlayOrigin(origin: PlaybackOrigin) {
        notePlayRequest()
        lastLyraOriginAt = System.currentTimeMillis()
        scope.launch(originWriter) { originStore.save(origin) }
    }

    suspend fun loadPlayOrigin(): PlaybackOrigin? = withContext(originWriter) { originStore.load() }

    /**
     * Sign-out (2026-10-04 evening, `AppContainer.signOut`): forgets the signed-out account's
     * playback — the mirror (track, context, device), the pending hold, the shuffle debt, the SDK
     * mirror's catalog copy and the poll-shape log — and deletes the origin file THROUGH [originWriter],
     * so a save launched just before cannot land after the delete and resurrect it. The play-request
     * generation moves, so a restore still waiting for its device abandons silently. The sign-out
     * EPOCH moves first ([signOutEpoch], review 2026-10-04 evening): a poll, a local read or a catalog
     * lookup already in flight is dropped when it returns, and a poll's origin save re-checks it on
     * the writer — so a late answer can neither write the old account back nor re-save its context.
     * The shuffle / repeat locks go too; the PLAYER / LIBRARY rate-limit windows STAY (a 429 belongs
     * to the client id, not the account — retrying into a ban only extends it). The sleep
     * timer and the device-level uris-suspect flag stay (they are not the account's).
     */
    fun resetForSignOut() {
        // First: every answer / read / lookup already in flight is dropped from here on.
        signOutEpoch++
        notePlayRequest()
        pendingHold = null
        shuffleOwedOn = false
        synchronized(sdkTrackDetails) {
            sdkTrackDetails.clear()
            sdkLookupInFlight.clear()
            sdkLookupFailedUntil.clear()
        }
        dropoutLatched.value = false
        lastObservedContextUri = null
        lastLyraOriginAt = 0L
        isPlayingLockUntil = 0L
        trackLockUntil = 0L
        shuffleLockUntil = 0L
        repeatLockUntil = 0L
        progressTickJob?.cancel()
        lastPollShape = null
        _mirrorSource.value = MirrorSource.WEB_API
        sdkPlayingGate.reset()
        _state.update {
            PlayerState(sleepTimerMinutes = it.sleepTimerMinutes, sleepTimerTotalMinutes = it.sleepTimerTotalMinutes)
        }
        scope.launch(originWriter) { originStore.clear() }
    }

    /** Poll side: remember a context the user started elsewhere (e.g. inside the Spotify app). */
    private fun observeContextUri(contextUri: String?, epoch: Long) {
        if (contextUri == lastObservedContextUri) return
        lastObservedContextUri = contextUri
        if (contextUri == null) return
        if (System.currentTimeMillis() - lastLyraOriginAt < ORIGIN_POLL_QUIET_MS) return
        val origin = PlaybackOrigin.forContext(contextUri)
        // Re-checked ON the writer: the sign-out bumps the epoch BEFORE it queues its delete there,
        // so a save that runs after the delete always sees the new epoch and is dropped.
        scope.launch(originWriter) { if (signOutEpoch == epoch) originStore.save(origin) }
    }

    init {
        startPolling()
        // The SDK stream itself: while the mirror reads the SDK, every local event (a pause from the
        // notification, a track change, a seek) lands at once instead of at the next 3 s poll.
        scope.launch {
            remoteManager.localState.collect { snap ->
                if (snap != null && _mirrorSource.value == MirrorSource.SDK &&
                    sdkSnapshotFresh(snap, SystemClock.elapsedRealtime()) && sdkMayMirror(snap)) {
                    applySdkMirror(snap, null)
                }
            }
        }
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    /**
     * Consecutive polls that failed on the network itself (no HTTP answer). The first poll of a
     * process can be BLOCKED by Android: this manager is built from the Application (a widget
     * broadcast or a launch starts the process) and polls at once, and `am_uid_active` — the moment
     * the uid's network is allowed — landed 9 ms AFTER that poll on 2026-10-05 (`NetdEventListener:
     * DNS … isBlocked=true, 0ms` → "No address associated with hostname", every process start that
     * night). Such a failure is retried after 1 s, at most [POLL_QUICK_RETRIES] times in a row, before
     * the ordinary 3 s cadence resumes; a real outage therefore costs three quick polls, no more.
     */
    private var pollQuickRetries = 0

    private fun startPolling() {
        pollJob = scope.launch {
            // The first poll waits until Android allows this uid's network (Cris, 2026-10-05: "delay
            // the first poll until the process is active instead of repeat quick polls").
            awaitNetworkAllowed()
            while (isActive) {
                val quick = if (System.currentTimeMillis() >= pollBackoffUntil) {
                    val ok = fetchPlayerState() != null || lastPollHadAnswer
                    if (ok) { pollQuickRetries = 0; false }
                    else if (lastPollNetworkFailure && pollQuickRetries < POLL_QUICK_RETRIES) { pollQuickRetries++; true }
                    else false
                } else false
                delay(if (quick) 1_000L else 3_000L)
            }
        }
    }

    /**
     * Suspends until the default network is available AND not blocked for this uid — Android's own
     * `NetworkCallback.onBlockedStatusChanged`, delivered with the current state on registration
     * (API 29+) — or until [NETWORK_ALLOWED_WAIT_MS] has passed. A process started by a widget
     * broadcast or a launch is "stopped"/background for its first ~100 ms, and a request sent then
     * is dropped by netd before any packet (`isBlocked=true, 0ms`). Nothing is lost on a device
     * that never reports blocked: the callback answers at once. Any failure to register (no
     * ConnectivityManager, a SecurityException) falls through to the quick retry.
     */
    private suspend fun awaitNetworkAllowed() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val startedAt = SystemClock.elapsedRealtime()
        var callback: ConnectivityManager.NetworkCallback? = null
        val allowed = try {
            withTimeoutOrNull(NETWORK_ALLOWED_WAIT_MS) {
                suspendCancellableCoroutine { cont ->
                    val cb = object : ConnectivityManager.NetworkCallback() {
                        @Volatile private var available = false
                        @Volatile private var blocked = true
                        private fun settle() { if (available && !blocked && cont.isActive) cont.resume(true) {} }
                        override fun onAvailable(network: Network) { available = true; blocked = false; settle() }
                        override fun onBlockedStatusChanged(network: Network, isBlocked: Boolean) { available = true; blocked = isBlocked; settle() }
                        override fun onLost(network: Network) { available = false }
                    }
                    callback = cb
                    try {
                        cm.registerDefaultNetworkCallback(cb)
                    } catch (e: Exception) {
                        callback = null
                        if (cont.isActive) cont.resume(false) {}
                    }
                }
            }
        } finally {
            callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        }
        val waited = SystemClock.elapsedRealtime() - startedAt
        if (allowed != true || waited > 50L) {
            Log.d("PlayerStateManager", "first poll: network " +
                (if (allowed == true) "allowed after $waited ms" else "not reported allowed within $waited ms — polling anyway"))
        }
    }

    /** Set by [fetchPlayerState]: the last poll got an HTTP answer (any status, a 204 included). */
    @Volatile private var lastPollHadAnswer = false
    /** Set by [fetchPlayerState]: the last poll failed before any HTTP answer (DNS blocked, no route, a timeout). */
    @Volatile private var lastPollNetworkFailure = false

    /**
     * One poll now. Returns the RAW response (null on a 204 or a failure): the mirror's
     * `isPlaying` is held by the optimistic lock, so a caller that must know what Spotify REALLY
     * reports — the wake restore's "is the song playing yet" check — reads this instead.
     */
    suspend fun fetchOnce(): PlayerStateResponse? = fetchPlayerState()

    /**
     * One poll now, as an observation: the 200 / 204 the poll saw, or null when the call FAILED
     * (a failure says nothing about the player — the confirm step must neither count it as EMPTY
     * nor as NO_DEVICE).
     */
    suspend fun pollOnce(): PollObservation? {
        var observed: PollObservation? = null
        fetchPlayerState { observed = it }
        return observed
    }

    private suspend fun fetchPlayerState(onObserved: ((PollObservation) -> Unit)? = null): PlayerStateResponse? {
        val epoch = signOutEpoch
        return repository.getPlayerState().fold(
            onSuccess = { response ->
                lastPollHadAnswer = true
                lastPollNetworkFailure = false
                // Sent before a sign-out, answered after it: the old account's playback — dropped
                // whole (no mirror, no origin save, no source flip, no observation).
                if (signOutEpoch != epoch) {
                    Log.d("PlayerStateManager", "poll: answer from before the sign-out dropped")
                    return@fold null
                }
                val observation = response.toObservation()
                // The Web API's view is BLIND (a 204, or an item-less 200 on this phone): the local
                // app's own state drives the mirror while the bind is live (SDK mirror, register A3).
                val blind = webViewBlind(observation, localDeviceCheck(response?.device))
                if (blind && mirrorFromLocalApp(response?.device, epoch)) {
                    notePollShape(if (response == null) "204 (no active device) — SDK mirror"
                                  else "200 item=null on this phone — SDK mirror")
                    _lastPoll.tryEmit(observation)
                    onObserved?.invoke(observation)
                    return@fold response
                }
                noteMirrorSource(MirrorSource.WEB_API,
                    if (blind) "me/player blind, but no bound App Remote with a fresh state it may mirror"
                    else "me/player reports the player")
                if (response != null) {
                    val now = System.currentTimeMillis()
                    // During a device transfer the API briefly returns a null item (mid-transition).
                    // Lock track+progress+duration together so the UI doesn't flash "Nothing Playing"
                    // or reset the seek bar to 0 while Spotify is switching devices.
                    // A play Lyra issued and Spotify has not reported yet holds the same way
                    // (pendingHold, 2026-10-04): 9.1.88 answers an accepted uris body with item=null.
                    // While the hold is live a NON-target item does not replace the held one either:
                    // right after a uris play the PREVIOUS song is still reported for a poll or two,
                    // then EMPTY — showing the old song under the spinner and then the tapped one
                    // again would be a flicker with no information in it.
                    val hold = pendingHold
                    val heldUri = hold?.uri
                    val itemIsTarget = response.item != null &&
                        (response.item.uri == heldUri || response.item.linkedFrom?.uri == heldUri)
                    // Held against `item: null` and against the PRIOR song only (and, once kept
                    // unconfirmed, against `item: null` alone); any other item is real playback.
                    val itemIsHeldAgainst = response.item == null ||
                        (hold != null && !hold.keptUnconfirmed && hold.priorUri != null &&
                         response.item.uri == hold.priorUri)
                    val holding = !itemIsTarget && itemIsHeldAgainst && holdIsLive(now)
                    val lockingTransfer = (now < trackLockUntil && response.item == null) || holding
                    if (holding && response.item == null) {
                        if (!holdLogged) {
                            holdLogged = true
                            Log.d("PlayerStateManager", "poll: EMPTY while a play is pending " +
                                  "(${pendingHold?.uri}) — holding the tapped item")
                        }
                    } else if (!holding && response.item == null && !lockingTransfer) {
                        Log.w("PlayerStateManager", "poll: 200 with item=null (playing=${response.isPlaying}, " +
                              "type=${response.currentlyPlayingType}, context=${response.context?.uri}, " +
                              "device=${response.device?.describe()}) — the UI will show Nothing Playing")
                    }
                    // The hold ends on the TARGET (or its relinked copy), on any item it does not
                    // hold against, by its owner's release, or at its ceiling — NOT on the previous
                    // song still reported: the EMPTY poll that follows would blank the player.
                    if (hold != null && pendingHold === hold && response.item != null && !holding) pendingHold = null
                    notePollShape("200 item=${response.item?.uri} playing=${response.isPlaying} " +
                                  "type=${response.currentlyPlayingType} device=${response.device?.describe()}")
                    _state.update {
                        if (signOutEpoch != epoch) return@update it
                        it.copy(
                            isPlaying      = if (now < isPlayingLockUntil) it.isPlaying else response.isPlaying,
                            currentTrack   = if (lockingTransfer) it.currentTrack else response.item,
                            progressMs     = if (lockingTransfer) it.progressMs else response.progressMs,
                            durationMs     = if (lockingTransfer) it.durationMs else (response.item?.durationMs ?: it.durationMs),
                            shuffleEnabled = if (now < shuffleLockUntil) it.shuffleEnabled else response.shuffleState,
                            repeatState    = if (now < repeatLockUntil) it.repeatState else response.repeatState,
                            // Locked with the TRACK, not on a lock of its own: `context` goes null
                            // alongside `item` mid-transfer, and the queue's echo drop reads this.
                            hasContext     = if (lockingTransfer) it.hasContext else response.context != null,
                            contextUri     = if (lockingTransfer) it.contextUri
                                             else response.context?.uri?.takeIf { u -> u.isNotBlank() },
                            currentDevice  = response.device,
                        )
                    }
                    if (!lockingTransfer && signOutEpoch == epoch) observeContextUri(_state.value.contextUri, epoch)
                    val isNowPlaying = _state.value.isPlaying
                    if (isNowPlaying && progressTickJob?.isActive != true) startProgressTick()
                    else if (!isNowPlaying) progressTickJob?.cancel()
                    maybeStartService()
                } else {
                    notePollShape("204 (no active device)")
                    // 204 — no active device (Spotify killed or closed)
                    // Clear playing state unless an optimistic lock is in effect (e.g. SDK wake-up in progress).
                    // ONLY isPlaying: the track and its contextUri must survive, the play button
                    // restores from them.
                    val now = System.currentTimeMillis()
                    if (now >= isPlayingLockUntil) {
                        _state.update { it.copy(isPlaying = false) }
                        progressTickJob?.cancel()
                    }
                }
                // Written AFTER the mirror, so a reader of lastPoll sees the device the same poll set.
                _lastPoll.tryEmit(observation)
                onObserved?.invoke(observation)
                response
            },
            onFailure = { e ->
                notePollShape("failure: ${e.message?.take(160)}")
                lastPollHadAnswer = e.httpStatus() != null
                lastPollNetworkFailure = e.httpStatus() == null &&
                    (e is java.net.UnknownHostException || e is java.io.IOException || e.isTransientNetworkError())
                when {
                    e.isHttp(429) -> noteRateLimited(e, "poll me/player")
                    e.isTransientNetworkError() -> { /* silent */ }
                }
                null
            },
        )
    }

    // ── Progress tick ─────────────────────────────────────────────────────────

    private fun startProgressTick() {
        progressTickJob?.cancel()
        progressTickJob = scope.launch {
            while (isActive) {
                delay(1_000L)
                _state.update { s ->
                    s.copy(progressMs = (s.progressMs + 1_000L).coerceAtMost(s.durationMs))
                }
            }
        }
    }

    // ── Service ───────────────────────────────────────────────────────────────

    private fun maybeStartService() {
        if (!serviceRunning && (_state.value.isPlaying || _state.value.sleepTimerMinutes > 0)) {
            try {
                context.startForegroundService(Intent(context, LyraForegroundService::class.java))
                serviceRunning = true
            } catch (_: Exception) { }
        }
    }

    fun notifyServiceStopped() { serviceRunning = false }

    // ── Optimistic locks ──────────────────────────────────────────────────────

    fun lockIsPlaying()  { isPlayingLockUntil = System.currentTimeMillis() + 5_000L }
    fun lockShuffle()    { shuffleLockUntil   = System.currentTimeMillis() + 5_000L }
    fun lockRepeat()     { repeatLockUntil    = System.currentTimeMillis() + 5_000L }
    // Prevents currentTrack from being nulled by a mid-transfer poll where response.item is briefly null.
    fun lockTrack()      { trackLockUntil     = System.currentTimeMillis() + 3_000L }
    private fun backoffUntil(family: RateLimitFamily) = when (family) {
        RateLimitFamily.PLAYER  -> pollBackoffUntil
        RateLimitFamily.LIBRARY -> libraryBackoffUntil
    }
    fun isRateLimited(family: RateLimitFamily = RateLimitFamily.PLAYER) =
        System.currentTimeMillis() < backoffUntil(family)
    /** Seconds left in that family's rate-limit window, 0 when open. */
    fun rateLimitSecondsLeft(family: RateLimitFamily = RateLimitFamily.PLAYER): Long =
        ((backoffUntil(family) - System.currentTimeMillis()) / 1_000L).coerceAtLeast(0L)

    /**
     * Arms the ONE shared backoff window every Web API caller checks (`isRateLimited()`).
     * **Honours `Retry-After`** (2026-09-25 evening): `safeCall` puts the header into the 429
     * message as `Retry-After=<seconds>`; a fixed 60 s used to be re-armed on the FIRST call after
     * it expired, every minute, for as long as Spotify's real penalty lasted — a frozen player
     * (the 3 s poll is gated too) with hero buttons that silently did nothing. The window is now
     * `max(60 s, Retry-After)`, capped at [MAX_RATE_LIMIT_BACKOFF_MS], and every arming is logged
     * with its origin so a device log names the endpoint.
     */
    fun noteRateLimited(
        e     : Throwable? = null,
        who   : String = "caller",
        family: RateLimitFamily = RateLimitFamily.PLAYER,
    ) {
        val retryAfterS = e.retryAfterSeconds()
            ?: e?.message?.let { Regex("Retry-After=(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }
        val cap = when (family) {
            RateLimitFamily.PLAYER  -> MAX_PLAYER_BACKOFF_MS
            RateLimitFamily.LIBRARY -> MAX_LIBRARY_BACKOFF_MS
        }
        val backoffMs = ((retryAfterS ?: 0L) * 1_000L).coerceIn(60_000L, cap)
        val until = System.currentTimeMillis() + backoffMs
        when (family) {
            RateLimitFamily.PLAYER  -> if (until > pollBackoffUntil) pollBackoffUntil = until
            RateLimitFamily.LIBRARY -> if (until > libraryBackoffUntil) {
                libraryBackoffUntil = until
                _libraryRateLimitUntil.value = until
            }
        }
        Log.w("PlayerStateManager", "429 from $who — Retry-After=${retryAfterS ?: "?"} s; the $family " +
              "family backs off ${backoffMs / 1_000L} s (${e?.message?.take(80)})")
    }

    // Optimistically marks Spotify as playing AND locks the state so transient 204 polls
    // during SDK wake-up don't flip the UI back to the play icon.
    // Resets progress to 0 and stops the tick so the bar doesn't count up from the old position
    // while the new track is loading. Call immediately after setOptimisticallyPlaying().
    fun resetProgressForNewTrack() {
        progressTickJob?.cancel()
        _state.update { it.copy(progressMs = 0L) }
    }

    // Starts the progress tick if isPlaying=true and the tick isn't already running.
    // Call just before clearing isWakingUp so the bar starts counting the moment it becomes determinate.
    fun ensureTickRunning() {
        if (_state.value.isPlaying && progressTickJob?.isActive != true) startProgressTick()
    }

    fun setOptimisticallyPlaying() {
        isPlayingLockUntil = System.currentTimeMillis() + 5_000L
        _state.update { it.copy(isPlaying = true) }
        if (progressTickJob?.isActive != true) startProgressTick()
        maybeStartService()
    }

    // Releases the optimistic lock and marks playback as stopped.
    // Call on failure paths where the wake attempt failed entirely.
    fun releasePlayingOptimism() {
        isPlayingLockUntil = 0L
        _state.update { it.copy(isPlaying = false) }
        progressTickJob?.cancel()
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    fun playPause() {
        val current = _state.value
        notePlayRequest()
        lockIsPlaying()
        scope.launch {
            if (current.isPlaying) {
                _state.update { it.copy(isPlaying = false) }
                progressTickJob?.cancel()
                remoteManager.pause()
                // While the mirror reads the SDK the Web API is blind: the SDK's pause is the one path.
                if (!sdkSourceActive()) repository.pause()
            } else {
                val track = current.currentTrack
                _state.update { it.copy(isPlaying = true) }
                startProgressTick()
                maybeStartService()
                // 2026-10-04: when the Web API's view is EMPTY (a 204, or 9.1.88's item-less 200)
                // but the App Remote is bound, the local Spotify app may well hold the item — a
                // Web API resume does nothing there (or 404s into a full restore over audio the SDK
                // could simply resume). So: the SDK's resume, and the Web API's never for the same tap.
                // An item-less 200 counts only when the device reporting it is THIS phone (an idle
                // speaker's EMPTY player gets the Web API resume, review 2026-10-04); a 204 means no
                // device at all, where only the local app can resume.
                val apiViewEmpty = webViewBlind(lastPollValue(), localDeviceCheck(current.currentDevice))
                if ((apiViewEmpty || sdkSourceActive()) && remoteManager.hasLiveRemote()) {
                    resumeThroughSdk(track, current.progressMs)
                    return@launch
                }
                repository.play().fold(
                    onSuccess = { delay(500L); fetchPlayerState() },
                    onFailure = { e ->
                        if (e.isHttp(404) && track != null) {
                            onWakeOperationStart?.invoke()
                            progressTickJob?.cancel()
                            // The full restore (PlayerViewModel.restoreAfterWake): the SDK plays the
                            // item, the queue is rebuilt from where it came from, and the waking
                            // state clears only once Spotify reports the song playing — the hook
                            // owns that clear. Before 2026-09-25 this was the bare single-uri play
                            // below and nothing else, so a play after Spotify died queued ONE song.
                            val restored = onWakeRestore?.invoke(track, current.progressMs, 0) == true
                            if (!restored) {
                                _state.update { it.copy(progressMs = 0L) }
                                remoteManager.connectAndPlay(track.uri)
                                delay(500L)
                                fetchPlayerState()
                                // Start tick optimistically so the bar counts from the moment
                                // indeterminate clears, even if fetchPlayerState returned 204.
                                if (progressTickJob?.isActive != true) startProgressTick()
                                onWakeOperationComplete?.invoke()
                            }
                        }
                    },
                )
            }
        }
    }

    /**
     * The play button while the Web API sees no playback but the App Remote is bound: the SDK
     * resumes, then `me/player` is read every second for up to [SDK_RESUME_CONFIRM_MS] for a
     * playing item. If none shows — the local app had nothing to resume, or the phone is not
     * registered with Connect — the HELD [track] goes through today's 404 restore (the pending
     * hold / a 204 keep it non-null). The waking spinner shows throughout and clears only on a
     * reported play or when the restore has run.
     */
    private suspend fun resumeThroughSdk(track: SpotifyTrack?, progressMs: Long) {
        val generation = playRequestGeneration.get()
        onWakeOperationStart?.invoke()
        remoteManager.resume()
        // While the Web API is blind the local app's own report confirms (SDK mirror, 2026-10-04
        // evening): not paused, the position advanced ≥ 500 ms between two reports after the resume.
        // Any item — like the Web API branch, which takes any reported item playing.
        val sdkWatch = SdkAudibleWatch(targetUri = null, dispatchedAtElapsed = SystemClock.elapsedRealtime())
        Log.d("PlayerStateManager", "play: Web API view EMPTY — SDK resume dispatched (${track?.uri})")
        val deadline = System.currentTimeMillis() + SDK_RESUME_CONFIRM_MS
        var playing = false
        var viaSdk = false
        while (System.currentTimeMillis() < deadline) {
            delay(1_000L)
            if (playRequestGeneration.get() != generation) {
                Log.d("PlayerStateManager", "play: SDK resume superseded")
                onWakeOperationComplete?.invoke()
                return
            }
            if (isRateLimited()) break
            lockIsPlaying()
            lockTrack()
            var obs: PollObservation? = null
            val r = fetchPlayerState { obs = it }
            if (r != null && r.isPlaying && r.item != null) { playing = true; break }
            // The blind poll has just refreshed the local snapshot (mirrorFromLocalApp).
            if (webViewBlind(obs, localDeviceCheck(_state.value.currentDevice)) &&
                sdkWatch.offer(remoteManager.localState.value)) { playing = true; viaSdk = true; break }
        }
        // The last poll (or the rate-limit break) took real time: a tap made meanwhile owns the
        // player now, and the restore below would cancel it and replay the old held track.
        if (playRequestGeneration.get() != generation) {
            Log.d("PlayerStateManager", "play: SDK resume superseded")
            onWakeOperationComplete?.invoke()
            return
        }
        if (playing) {
            Log.d("PlayerStateManager", "play: SDK resume confirmed — " +
                  (if (viaSdk) "the local Spotify app reports ${remoteManager.localState.value?.trackUri} playing"
                   else "Spotify reports ${_state.value.currentTrack?.uri} playing"))
            onWakeOperationComplete?.invoke()
            return
        }
        if (track == null) {
            Log.d("PlayerStateManager", "play: SDK resume not reported playing and no held track — giving up")
            releasePlayingOptimism()
            onWakeOperationComplete?.invoke()
            return
        }
        Log.d("PlayerStateManager", "play: SDK resume not reported playing in ${SDK_RESUME_CONFIRM_MS} ms — " +
              "falling into the wake restore with ${track.uri}")
        progressTickJob?.cancel()
        val restored = onWakeRestore?.invoke(track, progressMs, 0) == true
        if (!restored) {
            _state.update { it.copy(progressMs = 0L) }
            remoteManager.connectAndPlay(track.uri)
            delay(500L)
            fetchPlayerState()
            if (progressTickJob?.isActive != true) startProgressTick()
            onWakeOperationComplete?.invoke()
        }
    }

    fun skipNext() {
        notePlayRequest()
        scope.launch {
            setOptimisticallyPlaying()
            val prevUri = _state.value.currentTrack?.uri
            resetProgressForNewTrack()
            if (sdkSourceActive()) {
                // The Web API is blind: one path per tap, through the local app (skipOnSdkSource).
                skipOnSdkSource(+1, prevUri)
                return@launch
            }
            repository.skipNext().fold(
                onSuccess = {
                    fetchUntilTrackChanges(prevUri)
                    ensureTickRunning()
                },
                onFailure = { e ->
                    if (e.isHttp(404)) {
                        onWakeOperationStart?.invoke()
                        val track = _state.value.currentTrack
                        val restored = track != null &&
                            onWakeRestore?.invoke(track, _state.value.progressMs, +1) == true
                        if (!restored) {
                            remoteManager.skipNext()
                            fetchUntilTrackChanges(prevUri)
                            onWakeOperationComplete?.invoke()
                        }
                    } else {
                        releasePlayingOptimism()
                    }
                },
            )
        }
    }

    fun skipPrevious() {
        notePlayRequest()
        scope.launch {
            setOptimisticallyPlaying()
            val prevUri = _state.value.currentTrack?.uri
            resetProgressForNewTrack()
            if (sdkSourceActive()) {
                // The Web API is blind: one path per tap, through the local app (skipOnSdkSource).
                skipOnSdkSource(-1, prevUri)
                return@launch
            }
            repository.skipPrevious().fold(
                onSuccess = {
                    fetchUntilTrackChanges(prevUri)
                    ensureTickRunning()
                },
                onFailure = { e ->
                    if (e.isHttp(404)) {
                        onWakeOperationStart?.invoke()
                        val track = _state.value.currentTrack
                        val restored = track != null &&
                            onWakeRestore?.invoke(track, _state.value.progressMs, -1) == true
                        if (!restored) {
                            remoteManager.skipPrevious()
                            fetchUntilTrackChanges(prevUri)
                            onWakeOperationComplete?.invoke()
                        }
                    } else {
                        releasePlayingOptimism()
                    }
                },
            )
        }
    }

    /**
     * Next ([step] +1) / previous (-1) while the mirror reads the SDK (2026-10-04 evening follow-up).
     * The local app's OWN skip only when it holds a real context ([sdkSkipAllowed] on
     * `SpotifyRemoteManager.localContext` + `lonePlaySinceContext`), confirmed by the SDK stream.
     * Otherwise it holds a LONE uri — an App Remote wake whose queue body never landed (the phone
     * never listed, the body dropped) leaves exactly the one item the SDK played — and its skip would
     * give Spotify's autoplay (or nothing) instead of the Liked / list neighbour: the skip then goes
     * through the same neighbour restore as a 404 on the Web API path (`onWakeRestore(track, progress,
     * ±1)` → `PlayerViewModel.restoreForResume`), which lands on the neighbour through the SDK — what
     * every skip with Spotify off Connect did before the mirror. One log line names the route.
     */
    private suspend fun skipOnSdkSource(step: Int, prevUri: String?) {
        val what = if (step > 0) "next" else "previous"
        val ctx = remoteManager.localContext.value
        val lone = remoteManager.lonePlaySinceContext
        val track = _state.value.currentTrack
        val ctxLabel = when {
            lone        -> "a single-item SDK play not yet reported as a context"
            ctx == null -> "no context reported"
            else        -> "context ${ctx.uri ?: "(blank)"}, type ${ctx.type}"
        }
        if (sdkSkipAllowed(ctx?.uri, lone) || track == null) {
            Log.d("PlayerStateManager", "skip $what: SDK source — the local app's own skip (" + ctxLabel +
                  (if (track == null) "; no track to restore from" else "") + ")")
            sdkSkip(step)
            awaitSdkTrackChange(prevUri)
            ensureTickRunning()
            return
        }
        Log.d("PlayerStateManager", "skip $what: SDK source, the local app holds a LONE uri ($ctxLabel) — " +
              "the neighbour restore from ${track.uri}")
        onWakeOperationStart?.invoke()
        val restored = onWakeRestore?.invoke(track, _state.value.progressMs, step) == true
        if (!restored) {
            // The ViewModel is gone: the local app's own skip after all, as the 404 arm does.
            sdkSkip(step)
            awaitSdkTrackChange(prevUri)
            ensureTickRunning()
            onWakeOperationComplete?.invoke()
        }
    }

    private suspend fun sdkSkip(step: Int) {
        if (step > 0) remoteManager.skipNext() else remoteManager.skipPrevious()
    }

    /**
     * The SDK-source skip's confirm: the local app re-read every 500 ms until it reports a track
     * other than [prevUri] (up to [maxMs]); the mirror takes it at once.
     */
    private suspend fun awaitSdkTrackChange(prevUri: String?, maxMs: Long = 4_000L) {
        val deadline = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < deadline) {
            delay(500L)
            val snap = remoteManager.refreshLocalState() ?: continue
            if (snap.trackUri != null && snap.trackUri != prevUri) {
                if (sdkMayMirror(snap)) applySdkMirror(snap, null)
                return
            }
        }
    }

    // Polls until Spotify reports a NON-NULL item whose URI differs from prevTrackUri (up to maxMs),
    // checking every 700 ms. An item=null poll never ends it (2026-10-04): the mirror's track going
    // null is the 9.1.88 EMPTY player, not "the skip landed". By uri, not id (audit W3): two local
    // files both have a null id, so an id test never saw the change.
    private suspend fun fetchUntilTrackChanges(prevTrackUri: String?, maxMs: Long = 4_000L) {
        val deadline = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < deadline) {
            delay(700L)
            val item = fetchPlayerState()?.item
            if (item != null && item.uri != prevTrackUri) return
        }
    }

    fun seekTo(fraction: Float) {
        val posMs = (fraction * _state.value.durationMs).toLong()
        _state.update { it.copy(progressMs = posMs) }
        scope.launch {
            if (sdkSourceActive()) remoteManager.seekTo(posMs) else repository.seek(posMs)
        }
    }

    fun toggleShuffle() {
        clearShuffleOwed()
        val new = !_state.value.shuffleEnabled
        lockShuffle()
        _state.update { it.copy(shuffleEnabled = new) }
        scope.launch {
            if (sdkSourceActive()) { remoteManager.setShuffle(new); return@launch }
            repository.setShuffle(new).onFailure { e ->
                if (e.isHttp(404)) remoteManager.setShuffle(new)
            }
        }
    }

    /**
     * Sets shuffle to [enabled] without toggling — the Classic's deliberate-selection path (shuffle OFF
     * before playing the tapped song) and Shuffle Songs (shuffle ON). Mirrors [toggleShuffle]'s
     * structure: optimistic lock + optimistic state + Web API, 404 → App Remote.
     */
    fun setShuffle(enabled: Boolean) {
        clearShuffleOwed()
        lockShuffle()
        _state.update { it.copy(shuffleEnabled = enabled) }
        scope.launch {
            if (sdkSourceActive()) { remoteManager.setShuffle(enabled); return@launch }
            repository.setShuffle(enabled).onFailure { e ->
                if (e.isHttp(404)) remoteManager.setShuffle(enabled)
            }
        }
    }

    /**
     * Awaitable variant of [setShuffle] for callers that need to sequence the shuffle change BEFORE
     * a play request (iLyra mode: shuffle OFF → play the tapped song, otherwise the uris body starts
     * at a random entry). Returns the Web API result; a 404 is NOT swallowed — the caller owns the
     * App Remote fallback and must apply shuffle there too.
     */
    suspend fun applyShuffle(enabled: Boolean): Result<Unit> {
        lockShuffle()
        _state.update { it.copy(shuffleEnabled = enabled) }
        return repository.setShuffle(enabled).also { if (enabled && it.isSuccess) clearShuffleOwed() }
    }

    /**
     * Clears the optimistic shuffle lock so the next [fetchPlayerState] writes the server's truth.
     * Used by the shuffle-re-assert path in [PlayerViewModel.shuffleContext]: the 1.5 s verification
     * fetch must read the REAL server state, not the locked-true optimistic value.
     */
    fun clearShuffleLock() {
        shuffleLockUntil = 0L
    }

    fun cycleRepeat() {
        val next = when (_state.value.repeatState) {
            "context" -> "track"
            "track"   -> "off"
            else      -> "context"
        }
        lockRepeat()
        _state.update { it.copy(repeatState = next) }
        val sdkMode = when (next) { "context" -> 1; "track" -> 2; else -> 0 }
        scope.launch {
            if (sdkSourceActive()) { remoteManager.setRepeat(sdkMode); return@launch }
            repository.setRepeat(next).onFailure { e ->
                if (e.isHttp(404)) remoteManager.setRepeat(sdkMode)
            }
        }
    }

    /** Sets repeat to an explicit state ("off" / "context" / "track") — the Classic's repeat bar. */
    fun setRepeat(state: String) {
        lockRepeat()
        _state.update { it.copy(repeatState = state) }
        val sdkMode = when (state) { "context" -> 1; "track" -> 2; else -> 0 }
        scope.launch {
            if (sdkSourceActive()) { remoteManager.setRepeat(sdkMode); return@launch }
            repository.setRepeat(state).onFailure { e ->
                if (e.isHttp(404)) remoteManager.setRepeat(sdkMode)
            }
        }
    }

    // ── Sleep timer ───────────────────────────────────────────────────────────

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        _state.update { it.copy(sleepTimerMinutes = minutes, sleepTimerTotalMinutes = minutes) }
        if (minutes <= 0) return
        maybeStartService()
        sleepTimerJob = scope.launch {
            repeat(minutes) { elapsed ->
                delay(60_000L)
                val remaining = minutes - elapsed - 1
                _state.update { it.copy(sleepTimerMinutes = remaining) }
                if (remaining == 0) {
                    remoteManager.pause()
                    repository.pause()
                    _state.update { it.copy(isPlaying = false, sleepTimerTotalMinutes = 0) }
                    progressTickJob?.cancel()
                }
            }
        }
    }
}

private fun Throwable.isTransientNetworkError(): Boolean =
    cause is java.net.UnknownHostException ||
    cause is java.net.SocketException ||
    message?.contains("Unable to resolve host") == true ||
    message?.contains("Failed to connect") == true

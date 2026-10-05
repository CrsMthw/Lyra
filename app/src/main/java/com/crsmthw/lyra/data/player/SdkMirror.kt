package com.crsmthw.lyra.data.player

import com.crsmthw.lyra.data.remote.LocalPlayerSnapshot
import com.crsmthw.lyra.data.remote.model.SpotifyAlbum
import com.crsmthw.lyra.data.remote.model.SpotifyArtist
import com.crsmthw.lyra.data.remote.model.SpotifyShow
import com.crsmthw.lyra.data.remote.model.SpotifyTrack

/**
 * The App Remote PlayerState as a MIRROR SOURCE during a Spotify Connect dropout (docs/PLAYER.md →
 * SDK mirror, 2026-10-04 evening; register A3). Pure Kotlin, no Android imports — unit-tested.
 *
 * Spotify Android 9.1.88 loses its Connect session mid-playback: `GET me/player` answers 204 (or an
 * item-less 200) while the phone is audibly playing. The App Remote is a direct on-device channel to
 * the Spotify app and reports what IT is playing. Cris's rules:
 *  - never show playing without audio — the SDK counts as "Spotify reports it playing" only when it
 *    is not paused AND the position ADVANCED between two reports ≥ 500 ms apart ([sdkReportsAudible]),
 *    and that holds for the MIRROR too ([SdkPlayingGate]);
 *  - the Web API wins whenever it is NOT blind ([webViewBlind]);
 *  - the SDK state is the Spotify APP's player, which can be a remote Connect device the app
 *    controls, so the mirror applies ONLY while the Web API answers 204, or 200-EMPTY on THIS phone.
 */

/** Which source the player mirror is reading. Logged once per switch by `PlayerStateManager`. */
enum class MirrorSource { WEB_API, SDK }

/** The least position advance — and the least time between the two reports — that counts as audible. */
const val SDK_AUDIBLE_MIN_ADVANCE_MS = 500L

/**
 * The most the position may advance per millisecond between two reports and still be PLAYBACK
 * (2.5× — podcast 2× speed passes) plus [SDK_AUDIBLE_JUMP_SLACK_MS]. A bigger forward step is a SEEK
 * (the wake path's `seekTo` after the SDK play, the rescue's), which says nothing about audio — the
 * item may be buffering silently at its new position. It re-bases the watch instead of confirming.
 */
const val SDK_AUDIBLE_MAX_RATE = 2.5
const val SDK_AUDIBLE_JUMP_SLACK_MS = 1_500L

/** Did the position move FURTHER than playback could have between [a] and [b] (a forward seek)? */
fun sdkPositionJumped(a: LocalPlayerSnapshot, b: LocalPlayerSnapshot): Boolean {
    val dt = (b.atElapsedMs - a.atElapsedMs).coerceAtLeast(0L)
    return b.positionMs - a.positionMs > (dt * SDK_AUDIBLE_MAX_RATE).toLong() + SDK_AUDIBLE_JUMP_SLACK_MS
}

/** A local snapshot older than this is not used for the mirror. */
const val SDK_SNAPSHOT_FRESH_MS = 10_000L

/**
 * Does the local Spotify app report [targetUri] AUDIBLY playing? [a] then [b], both taken strictly
 * after [dispatchedAtElapsed] (when given; `elapsedRealtime` clock), neither paused, both on the same
 * uri (= [targetUri] when given), at least [SDK_AUDIBLE_MIN_ADVANCE_MS] apart in time AND in position.
 * A position that merely sits still under `isPaused == false` (a stalled load) never counts, and nor
 * does one that jumped further than playback could have ([sdkPositionJumped] — a seek).
 */
fun sdkReportsAudible(
    a                  : LocalPlayerSnapshot,
    b                  : LocalPlayerSnapshot,
    targetUri          : String?,
    dispatchedAtElapsed: Long?,
): Boolean {
    if (dispatchedAtElapsed != null &&
        (a.atElapsedMs <= dispatchedAtElapsed || b.atElapsedMs <= dispatchedAtElapsed)) return false
    if (a.isPaused || b.isPaused) return false
    val uri = a.trackUri ?: return false
    if (b.trackUri != uri) return false
    if (targetUri != null && uri != targetUri) return false
    if (b.atElapsedMs - a.atElapsedMs < SDK_AUDIBLE_MIN_ADVANCE_MS) return false
    if (sdkPositionJumped(a, b)) return false
    return b.positionMs - a.positionMs >= SDK_AUDIBLE_MIN_ADVANCE_MS
}

/**
 * Feeds successive snapshots to [sdkReportsAudible] for one waiting loop (the wake restore's device
 * wait, a play's confirm, the play button's SDK resume). Keeps the earliest QUALIFYING snapshot as
 * the base (after the dispatch, not paused, on the target); a snapshot that does not qualify drops
 * the base, and one whose position went BACK (a seek, a restart), jumped FORWARD further than playback
 * could have (a seek — [sdkPositionJumped]) or whose uri changed re-bases.
 * [offer] returns true once the pair is audible.
 */
class SdkAudibleWatch(
    private val targetUri          : String?,
    private val dispatchedAtElapsed: Long?,
) {
    private var base: LocalPlayerSnapshot? = null

    private fun qualifies(s: LocalPlayerSnapshot): Boolean =
        !s.isPaused && s.trackUri != null &&
            (targetUri == null || s.trackUri == targetUri) &&
            (dispatchedAtElapsed == null || s.atElapsedMs > dispatchedAtElapsed)

    fun offer(snap: LocalPlayerSnapshot?): Boolean {
        if (snap == null) return false
        if (!qualifies(snap)) { base = null; return false }
        val b = base
        if (b == null) { base = snap; return false }
        if (snap.atElapsedMs <= b.atElapsedMs) return false
        if (sdkReportsAudible(b, snap, targetUri, dispatchedAtElapsed)) return true
        if (snap.trackUri != b.trackUri || snap.positionMs < b.positionMs || sdkPositionJumped(b, snap)) base = snap
        return false
    }
}

/**
 * The MIRROR's own audible gate (review 2026-10-04 evening): `isPaused == false` in ONE snapshot is
 * not audio — the local app reports a buffering / stalled item (a cold load, a network drop
 * mid-dropout) exactly that way. Fed every snapshot the mirror sees (the 3 s blind poll's on-demand
 * read, the subscription's events); a [SdkAudibleWatch] on the CURRENT uri (no dispatch time)
 * confirms once two reports ≥ 500 ms apart show the position advancing, and the confirmation holds
 * for that uri until a pause, a snapshot with no track or another uri resets it (a seek on the same
 * uri does not). `playbackSpeed` (`PlayerState.playbackSpeed`, a primitive float) is deliberately
 * NOT read: Gson leaves an absent field at 0, so a hard `> 0` gate could never show playing if
 * 9.1.88 does not send it over IPC — the position rule already rejects a stall.
 * Thread-safe: the poll and the subscription's collector offer from different threads.
 */
class SdkPlayingGate {
    private var uri      : String? = null
    private var watch    : SdkAudibleWatch? = null
    private var confirmed: Boolean = false

    /** Offers [snap]; true while the local app is CONFIRMED audibly playing [snap]'s uri. */
    @Synchronized
    fun offer(snap: LocalPlayerSnapshot): Boolean {
        val u = snap.trackUri
        if (snap.isPaused || u == null) { resetLocked(); return false }
        if (u != uri) {
            uri = u
            watch = SdkAudibleWatch(targetUri = u, dispatchedAtElapsed = null)
            confirmed = false
        }
        if (!confirmed && watch?.offer(snap) == true) confirmed = true
        return confirmed
    }

    @Synchronized
    fun reset() = resetLocked()

    private fun resetLocked() { uri = null; watch = null; confirmed = false }
}

/**
 * Is the Web API's view of the player BLIND — a 204 (no active device), or a 200 with no item, not
 * playing, reported by a device [isLocalDevice] takes for THIS phone? A null [obs] (no successful
 * poll yet, or a failed one) is not blind: a failure says nothing about the player.
 */
fun webViewBlind(obs: PollObservation?, isLocalDevice: (String) -> Boolean): Boolean {
    if (obs == null) return false
    if (obs.status == 204) return true
    if (obs.status != 200 || obs.itemUri != null || obs.isPlaying) return false
    val id = obs.deviceId ?: return false
    return isLocalDevice(id)
}

/**
 * Next / previous while the mirror reads the SDK (2026-10-04 evening follow-up): may the LOCAL app's
 * own skip (`PlayerApi.skipNext` / `skipPrevious`) be used? Only when it holds a REAL context — a
 * playlist, an album, an artist, a show, the Liked `collection`, an autoplay station. After an App
 * Remote wake whose queue body never landed (the phone never listed, the body dropped) it holds the
 * ONE uri the SDK played, and its skip gives Spotify's autoplay (or nothing) instead of the Liked /
 * list NEIGHBOUR — so then the caller goes through the 404-style neighbour restore
 * (`onWakeRestore(track, progress, ±1)` → `restoreForResume`), as every skip did before the mirror.
 *
 * Lone: [localContextUri] null (no context event yet, or unbound) or blank, a single `spotify:track:`
 * / `spotify:episode:` uri (what 9.1.88 reports after an SDK `play(trackUri)` is undocumented — either
 * form counts), or [loneUriFlag] (a single-item SDK play the context events have not caught up with).
 */
fun sdkSkipAllowed(localContextUri: String?, loneUriFlag: Boolean): Boolean {
    if (loneUriFlag) return false
    val uri = localContextUri?.trim()
    if (uri.isNullOrEmpty()) return false
    return !uri.startsWith("spotify:track:") && !uri.startsWith("spotify:episode:")
}

/**
 * The Queue screen's "Spotify isn't connected to Spotify Connect right now" line (2026-10-04 evening
 * follow-up): only for a REAL dropout during playback — the mirror reads the SDK AND the local app
 * reports an item NOT paused, OR the caller's [latched] flag says it DID during this SDK stretch (the
 * manager latches that on the first non-paused report and clears it when the Web API source returns,
 * so the line and the player's "Open Spotify" hint stay through a pause — Cris, 2026-10-04 23:2x). An ordinary idle 204 with the App Remote bound also flips the source to
 * the SDK (the player then shows the local app's last song, paused — intended), but that is Connect
 * being merely inactive, and the line would be noise there. The local app's own `isPaused`, not the
 * mirror's `isPlaying`: the mirror's audible gate keeps that false while an item buffers.
 */
fun connectDropoutNoteShown(source: MirrorSource, local: LocalPlayerSnapshot?, latched: Boolean = false): Boolean =
    source == MirrorSource.SDK && (latched || (local != null && local.trackUri != null && !local.isPaused))

/** Is [snap] recent enough to mirror (`elapsedRealtime` clock)? */
fun sdkSnapshotFresh(snap: LocalPlayerSnapshot?, nowElapsedMs: Long, maxAgeMs: Long = SDK_SNAPSHOT_FRESH_MS): Boolean =
    snap != null && nowElapsedMs - snap.atElapsedMs in 0..maxAgeMs

/** The SDK's repeat mode (0 off, 1 context, 2 track) as the mirror's `repeatState`; null = unknown. */
fun sdkRepeatState(mode: Int?): String? = when (mode) {
    0    -> "off"
    1    -> "context"
    2    -> "track"
    else -> null
}

/**
 * The player mirror written from a LOCAL snapshot while the Web API is blind.
 *  - The track: the [previous] mirror's own track object when the SDK reports the SAME uri (its art,
 *    ids and flags stay, and nothing downstream sees a track change); else [known] when it is that
 *    uri (a catalog `GET tracks/{id}` the caller looked up); else a display-only track built from the
 *    snapshot — id = the uri's tail, the album's name with NO art (the SDK's `spotify:image:` uri has
 *    no documented https form; no art beats the previous track's art). An episode carries its show
 *    (the SDK reports the show as the "artist") and no artists.
 *  - No track in the snapshot (nothing loaded in the local app) → the previous track, not playing.
 *  - Playing only when not paused AND either [audible] (the mirror's [SdkPlayingGate] confirmed the
 *    position advancing) or the [previous] mirror was already playing (continuity: the Web API
 *    reported the song playing a poll before Connect dropped, or a track advanced on its own) — a
 *    non-paused report alone never turns playing ON (Cris: never show playing without audio).
 *    Progress = the snapshot's position, run forward to [nowElapsedMs] while playing, within the
 *    duration; duration from the snapshot (the track's when the SDK sent 0).
 *  - Shuffle / repeat from the snapshot's playback options when present.
 *  - `hasContext = false` (the SDK reports none); `contextUri` and the device are KEPT — the play
 *    button's restore plans from the context, and the device was this phone's.
 */
fun mirrorFromSdk(
    snapshot    : LocalPlayerSnapshot,
    previous    : PlayerState,
    known       : SpotifyTrack? = null,
    nowElapsedMs: Long          = snapshot.atElapsedMs,
    audible     : Boolean       = false,
): PlayerState {
    val uri = snapshot.trackUri
        ?: return previous.copy(isPlaying = false)
    val prevTrack = previous.currentTrack
    val track = when {
        prevTrack != null && prevTrack.uri == uri -> prevTrack
        known != null && known.uri == uri         -> known
        else                                      -> displayTrackFrom(snapshot, uri)
    }
    val durationMs = snapshot.durationMs.takeIf { it > 0L } ?: track.durationMs
    val playing = !snapshot.isPaused && (audible || previous.isPlaying)
    val running = if (!playing) 0L else (nowElapsedMs - snapshot.atElapsedMs).coerceAtLeast(0L)
    val position = (snapshot.positionMs + running).coerceAtLeast(0L)
    return previous.copy(
        isPlaying      = playing,
        currentTrack   = track,
        progressMs     = if (durationMs > 0L) position.coerceAtMost(durationMs) else position,
        durationMs     = durationMs,
        shuffleEnabled = snapshot.isShuffling ?: previous.shuffleEnabled,
        repeatState    = sdkRepeatState(snapshot.repeatMode) ?: previous.repeatState,
        hasContext     = false,
    )
}

/** The id behind `prefix` (`spotify:<type>:`), or null when the uri is not of that type or the id is blank. */
private fun String.idBehind(prefix: String): String? =
    if (startsWith(prefix)) substring(prefix.length).takeIf { it.isNotBlank() } else null

/**
 * A display-only [SpotifyTrack] from the snapshot — never used to address the API by its album /
 * art. Ids are read ONLY behind their `spotify:<type>:` prefix and `isLocal` comes from the uri
 * (audit 2026-10-04 W11): a bare `substringAfterLast(':')` made a local file `spotify:local:…:127`
 * into "track 127" — with a share page, a like target and an artist page for an id of "".
 */
internal fun displayTrackFrom(snapshot: LocalPlayerSnapshot, uri: String): SpotifyTrack {
    val isEpisode = snapshot.isEpisode || uri.startsWith("spotify:episode:")
    val isLocal   = uri.startsWith("spotify:local:")
    val album = snapshot.albumUri?.let { a ->
        SpotifyAlbum(id = a.idBehind("spotify:album:").orEmpty(), name = snapshot.albumName.orEmpty(), images = null)
    }
    val artists = if (isEpisode) emptyList() else snapshot.artists.mapNotNull { (aUri, aName) ->
        val name = aName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        SpotifyArtist(id = aUri?.idBehind("spotify:artist:").orEmpty(), name = name)
    }
    val show = if (!isEpisode) null else snapshot.artists.firstOrNull()?.let { (sUri, sName) ->
        SpotifyShow(
            id   = sUri?.idBehind("spotify:show:"),
            name = sName,
            uri  = sUri,
        )
    }
    return SpotifyTrack(
        id         = (uri.idBehind("spotify:track:") ?: uri.idBehind("spotify:episode:")).orEmpty(),
        name       = snapshot.name.orEmpty(),
        uri        = uri,
        artists    = artists,
        album      = if (isEpisode) null else album,
        durationMs = snapshot.durationMs,
        isLocal    = isLocal,
        type       = if (isEpisode) "episode" else "track",
        show       = show,
    )
}

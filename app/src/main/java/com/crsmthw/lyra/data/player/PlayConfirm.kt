package com.crsmthw.lyra.data.player

import com.crsmthw.lyra.data.remote.model.PlayerStateResponse
import com.crsmthw.lyra.data.remote.model.SpotifyTrack

/**
 * Confirm-then-fall-back for a play Lyra issued (docs/PLAYER.md → Bare-uris plays stop the 9.1.88
 * client, 2026-10-04). Pure Kotlin, no Android imports — unit-tested.
 *
 * Spotify Android 9.1.88 ACCEPTS a bare `uris` body (204) and then STOPS: `GET me/player` reports
 * the phone active with `item: null`, not playing, while a `context_uri` + `offset.uri` body
 * starts at once. So an accepted play is not a playing song; Lyra reads `me/player` until it
 * REPORTS the song, and sends a context body when the player comes back EMPTY.
 */

/**
 * One `GET me/player` answer, reduced to what the confirm step reads. Built ONLY from a successful
 * call (a 200 or a 204) — a FAILED poll (a DNS blip, a 5xx) is not an observation at all and must
 * never be read as "no device".
 */
data class PollObservation(
    /** 200 or 204. */
    val status       : Int,
    val itemUri      : String?,
    /** The uri the item was RELINKED from (`item.linked_from.uri`), so a relinked track confirms. */
    val linkedFromUri: String?,
    val isPlaying    : Boolean,
    val deviceId     : String?,
    /**
     * `context.uri` as reported (null for a bare `uris` play). The wake confirm reads it: right
     * after an App Remote play the SDK is already playing the target, so only the report of the
     * BODY's own context proves the body did not empty the player (review 2026-10-04).
     */
    val contextUri   : String? = null,
)

/** A null response is the 204 (no active device). Call only on a SUCCESSFUL poll. */
fun PlayerStateResponse?.toObservation(): PollObservation =
    if (this == null) {
        PollObservation(status = 204, itemUri = null, linkedFromUri = null, isPlaying = false, deviceId = null)
    } else {
        PollObservation(
            status        = 200,
            itemUri       = item?.uri?.takeIf { it.isNotBlank() },
            linkedFromUri = item?.linkedFrom?.uri?.takeIf { it.isNotBlank() },
            isPlaying     = isPlaying,
            deviceId      = device?.id,
            contextUri    = context?.uri?.takeIf { it.isNotBlank() },
        )
    }

enum class ConfirmVerdict {
    /** Spotify reports the target playing — the only thing that clears the waking state. */
    CONFIRMED,
    /** 200, no item, not playing: the 9.1.88 "accepted and stopped" shape. */
    EMPTY,
    /** Something else is reported (the previous item still, paused, a different song). */
    OTHER_ITEM,
    /** 204 — no active device. */
    NO_DEVICE,
}

/**
 * What [obs] says about a play of [targetUri].
 *
 * [step] 0: CONFIRMED when playing [targetUri] (or an item relinked from it).
 * [step] ≠ 0 (a skip after the body): the target is whatever Spotify moved to, so ANY non-null
 * item other than [priorUri], playing, confirms.
 */
fun confirmVerdict(obs: PollObservation, targetUri: String, priorUri: String?, step: Int = 0): ConfirmVerdict {
    if (obs.status == 204) return ConfirmVerdict.NO_DEVICE
    val item = obs.itemUri
    if (obs.isPlaying && item != null) {
        val onTarget = if (step == 0) item == targetUri || obs.linkedFromUri == targetUri
                       else item != priorUri
        if (onTarget) return ConfirmVerdict.CONFIRMED
    }
    if (obs.status == 200 && item == null && !obs.isPlaying) return ConfirmVerdict.EMPTY
    return ConfirmVerdict.OTHER_ITEM
}

/**
 * Does [obs] report the context [sentContextUri] — the body Lyra sent — as the playing context?
 * The collection is reported in two forms (`spotify:user:<id>:collection`, `spotify:collection:…`),
 * so two collection contexts match each other.
 */
fun reportsContext(obs: PollObservation, sentContextUri: String): Boolean {
    val reported = obs.contextUri ?: return false
    return reported == sentContextUri || (isCollectionContext(reported) && isCollectionContext(sentContextUri))
}

/**
 * CONFIRMED polls in a row that stand in for the body's own context ([reportsContext]) — see
 * [wakeConfirmReached]. FOUR, not two (2026-10-04): on the wake path the SDK is already playing the
 * item when a `uris` body goes out, so the first polls confirm on the SDK's OWN audio; Spotify Android
 * 9.1.88 then empties the player about 1.3–1.8 s after the PUT. Two polls (~2 s) could end the confirm
 * a breath before the silence and the EMPTY fallback would never run; four (~4 s) span it.
 */
const val WAKE_CONFIRM_STREAK_NO_CONTEXT = 4
/** CONFIRMED polls in a row that confirm a context body whose context is reported in another form. */
const val WAKE_CONFIRM_STREAK_OTHER_CONTEXT = 5

/**
 * The wake path's confirm (review 2026-10-04): the SDK is ALREADY playing the target before the
 * body goes out, so the first CONFIRMED poll may be the SDK's own audio, and a body that then
 * empties the player (the collection after a wake, 5/5 on 2026-09-25) would never be caught.
 *  - no body since the last SDK play ([sentContextUri] null and ![bodySent]) → one CONFIRMED;
 *  - a context body → its own context reported, or [WAKE_CONFIRM_STREAK_OTHER_CONTEXT] in a row;
 *  - a uris body → [WAKE_CONFIRM_STREAK_NO_CONTEXT] in a row.
 * [confirmedStreak] counts the CONFIRMED polls in a row, this one included.
 */
fun wakeConfirmReached(
    obs            : PollObservation,
    bodySent       : Boolean,
    sentContextUri : String?,
    confirmedStreak: Int,
): Boolean = when {
    !bodySent                       -> confirmedStreak >= 1
    sentContextUri == null          -> confirmedStreak >= WAKE_CONFIRM_STREAK_NO_CONTEXT
    reportsContext(obs, sentContextUri) -> true
    else                            -> confirmedStreak >= WAKE_CONFIRM_STREAK_OTHER_CONTEXT
}

/** How many polls in a row of [isOtherItemPlaying] an awake play's confirm takes as "Spotify is playing". */
const val OTHER_ITEM_PLAYING_STREAK = 3

/**
 * Spotify reports a DIFFERENT item playing — not [targetUri] (nor relinked from it), not the
 * [priorUri] that was showing before the tap. An unplayable playlist row skips to the next, a
 * relinked track without `linked_from`, the user starting something in Spotify's own app: audio
 * is playing and the mirror shows it, so after [OTHER_ITEM_PLAYING_STREAK] polls the confirm
 * ends without "didn't report" (review 2026-10-04).
 */
fun isOtherItemPlaying(obs: PollObservation, targetUri: String, priorUri: String?): Boolean {
    val item = obs.itemUri ?: return false
    return obs.status == 200 && obs.isPlaying &&
        item != targetUri && obs.linkedFromUri != targetUri && item != priorUri
}

/** A `me/player/play` body, as the confirm step's fallback chain names it. */
sealed interface PlayBody {
    /** `context_uri` + `offset.uri` (null offset = Spotify's own start). */
    data class Context(val contextUri: String, val offsetUri: String?) : PlayBody
    data class Uris(val uris: List<String>) : PlayBody
}

/**
 * The context bodies to try, in order, when a play leaves the player EMPTY — every one positioned
 * on the item by `offset.uri` by the caller. What other clients shipped against 9.1.88: play a
 * lone track from its ALBUM.
 *  - [PlaybackOrigin.Liked] → the user's collection (when [collectionUri] is known), then the album;
 *  - anything else → the album;
 *  - an episode → nothing (no album, and a show is not a documented `context_uri`).
 * The offset is left null here; the caller fills `offset.uri` with the item it plays.
 */
fun fallbackBodies(
    origin       : PlaybackOrigin?,
    albumUri     : String?,
    collectionUri: String?,
    isEpisode    : Boolean,
): List<PlayBody.Context> {
    if (isEpisode) return emptyList()
    val album = albumUri?.takeIf { it.isNotBlank() }
    val collection = collectionUri?.takeIf { it.isNotBlank() }
    return buildList {
        if (origin is PlaybackOrigin.Liked && collection != null) add(PlayBody.Context(collection, null))
        if (album != null) add(PlayBody.Context(album, null))
    }
}

/**
 * `spotify:album:<id>` for a track — the context a lone track falls back to — or null for an
 * episode, a local file or a track whose album id did not arrive (Gson can leave the declared
 * non-null `album.id` null).
 */
fun SpotifyTrack.albumContextUri(): String? {
    if (isEpisode || isLocal) return null
    val id: String? = album?.id
    return id?.takeIf { it.isNotBlank() }?.let { "spotify:album:$it" }
}

package com.crsmthw.lyra.data.player

import com.crsmthw.lyra.data.remote.model.SpotifyDevice

/**
 * The ONE `me/player/play` body the App Remote wake path sends once Spotify is listed as a device
 * (docs/PLAYER.md → Playback 404 Fallback). Pure Kotlin.
 */
sealed interface WakeRestoreBody {
    /**
     * `context_uri` + an offset: `offset.uri` = the current item, or `offset.position` when
     * [offsetPosition] is set (no caller sets it today — a raw position was tried for the
     * collection on the cold path on 2026-09-25 evening and reverted with it). The offset is
     * honoured even with shuffle ON.
     */
    data class Context(val contextUri: String, val offsetPosition: Int? = null) : WakeRestoreBody

    /**
     * A `uris` list starting at the current item. With shuffle ON Spotify starts such a body at a
     * RANDOM entry, so a multi-uri body is always bracketed by shuffle OFF → play → shuffle ON.
     */
    data class Uris(val uris: List<String>) : WakeRestoreBody
}

/**
 * [list] from [uri] onward, capped at [cap] — or null when [uri] is not in it. The first
 * occurrence wins (the liked list is de-duplicated by the caller; a uris origin rarely repeats).
 */
fun windowFrom(list: List<String>, uri: String, cap: Int = PlaybackOrigin.URI_CAP): List<String>? {
    val idx = list.indexOf(uri)
    if (idx < 0) return null
    return list.subList(idx, list.size).take(cap)
}

/**
 * Chooses the restore body for the PLAY BUTTON (a resume after Spotify died while paused) and for
 * next / previous on a dead Spotify. `playTrack` does not use this — it derives its body from its
 * own arguments.
 *
 * Rules, first match wins:
 *  1. The mirror has a [mirrorContextUri] → [WakeRestoreBody.Context]. The Liked `collection` is a
 *     REAL context: `context_uri = spotify:user:<id>:collection` + `offset.uri` (or `.position`)
 *     is accepted and positions inside the collection (lab 2026-09-25 — the 2021 "Can't have
 *     offset for context type: COLLECTION" error is gone), and it is the body every Liked play
 *     sends since 2026-10-04 evening, as Spotify's own clients do. A collection reported in
 *     another form (`spotify:collection:…`) is addressed through [collectionUri], the user's own;
 *     without one it falls to the liked window. An EPISODE never gets a `spotify:show:` context:
 *     a show is not a documented `context_uri`.
 *  2. A [PlaybackOrigin.Liked] origin → [collectionUri] as the context when known, else the liked
 *     window from [currentUri] (the old shape, kept only for when no user id is cached) — if
 *     [likedUris] holds it.
 *  3. A [PlaybackOrigin.Uris] origin holding [currentUri] → that list from [currentUri] onward.
 *  4. No origin at all (nothing better known), a track, and [likedUris] holds it → the collection
 *     context when known, else the liked window.
 *  5. Otherwise the single item.
 *
 * Every window is capped at [PlaybackOrigin.URI_CAP].
 */
fun planWakeRestore(
    currentUri      : String,
    mirrorContextUri: String?,
    origin          : PlaybackOrigin?,
    likedUris       : List<String>?,
    isEpisode       : Boolean,
    collectionUri   : String? = null,
): WakeRestoreBody {
    val ctx = mirrorContextUri?.takeIf { it.isNotBlank() }
    val likedWindow = if (isEpisode) null else likedUris?.let { windowFrom(it, currentUri) }
    val collection = collectionUri?.takeIf { !isEpisode && it.isNotBlank() }
    if (ctx != null && !(isEpisode && ctx.startsWith("spotify:show:"))) {
        when {
            !isCollectionContext(ctx)       -> return WakeRestoreBody.Context(ctx)
            // The user form is addressable as it stands; no user-id lookup needed.
            ctx.startsWith("spotify:user:") -> return WakeRestoreBody.Context(ctx)
            collection != null              -> return WakeRestoreBody.Context(collection)
            likedWindow != null             -> return WakeRestoreBody.Uris(likedWindow)
        }
    }
    if (!isEpisode && origin is PlaybackOrigin.Liked) {
        if (collection != null) return WakeRestoreBody.Context(collection)
        if (likedWindow != null) return WakeRestoreBody.Uris(likedWindow)
    }
    if (origin is PlaybackOrigin.Uris) {
        windowFrom(origin.uris, currentUri)?.let { return WakeRestoreBody.Uris(it) }
    }
    if (origin == null && likedWindow != null) {
        return if (collection != null) WakeRestoreBody.Context(collection) else WakeRestoreBody.Uris(likedWindow)
    }
    return WakeRestoreBody.Uris(listOf(currentUri))
}

/**
 * Picks THIS phone out of `me/player/devices`, so the restore body can carry `device_id` — with
 * it the device only has to be listed, not active. First match wins:
 *  1. a `Smartphone` or `Tablet` whose name equals one of [nameHints] (Settings.Global
 *     `device_name`, `Build.MODEL`) — Tablet too because a foldable registers as a tablet when
 *     unfolded (docs/SPOTIFY.md → "This device" design rationale). FIRST since the review of
 *     2026-10-04: an active handheld ahead of it could be ANOTHER phone of the account, and the
 *     wake body would go there;
 *  2. the active device — but only when it LOOKS like this phone: a `Smartphone` / `Tablet`, or a
 *     name matching one of [nameHints] (2026-10-04: an active desktop or speaker was "picked" and
 *     the wake body went there, hijacking the other device);
 *  3. the only `Smartphone` listed;
 *  4. null — ambiguous or absent, keep polling.
 *
 * Devices without an id or marked restricted cannot be targeted and are ignored. Every field is
 * read null-safely: Gson can leave the declared-non-null `name` / `type` null.
 */
fun pickLocalDevice(devices: List<SpotifyDevice>, nameHints: List<String>): SpotifyDevice? {
    val candidates = devices.filter { !it.id.isNullOrBlank() && !it.isRestricted }
    candidates.firstOrNull { d -> isNamedLikeThisPhone(d, nameHints) }?.let { return it }
    candidates.firstOrNull { it.isActive && (isHandheld(it) || nameMatchesHint(it, nameHints)) }?.let { return it }
    return candidates.filter { typeIs(it, "Smartphone") }.singleOrNull()
}

/**
 * The STRICT identification (2026-10-04): a `Smartphone` / `Tablet` whose name equals one of
 * [nameHints] — what the App Remote rescue and the device picker's transfer fallback require
 * before they play on this phone, because [pickLocalDevice]'s "the only Smartphone" rule would
 * also take ANOTHER phone when this one is not listed.
 */
fun isNamedLikeThisPhone(device: SpotifyDevice, nameHints: List<String>): Boolean =
    !device.id.isNullOrBlank() && isHandheld(device) && nameMatchesHint(device, nameHints)

/**
 * The ONLY `Smartphone` / `Tablet` in [devices] that can be targeted, or null when there are none
 * or several — the device-picker transfer's last way of recognising this phone (2026-10-04): an
 * unfolded Fold registers as a Tablet, which `pickLocalDevice`'s "only Smartphone" rule misses.
 * A GUESS: the transfer uses it only when no listed device is named like this phone and no id is
 * remembered for it, and never remembers the id it picks (review 2026-10-04).
 */
fun soleHandheld(devices: List<SpotifyDevice>): SpotifyDevice? =
    devices.filter { !it.id.isNullOrBlank() && !it.isRestricted && isHandheld(it) }.singleOrNull()

// `String?.equals` is null-safe on the receiver; Gson can leave the declared-non-null `type` null.
private fun typeIs(d: SpotifyDevice, t: String) = d.type.equals(t, ignoreCase = true)
private fun isHandheld(d: SpotifyDevice) = typeIs(d, "Smartphone") || typeIs(d, "Tablet")
private fun nameMatchesHint(d: SpotifyDevice, nameHints: List<String>): Boolean {
    // `name` is widened to String? before use: Gson can leave it null.
    val rawName: String? = d.name
    val name = rawName?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return nameHints.any { it.trim().equals(name, ignoreCase = true) }
}

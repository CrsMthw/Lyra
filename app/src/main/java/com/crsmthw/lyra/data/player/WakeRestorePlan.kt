package com.crsmthw.lyra.data.player

import com.crsmthw.lyra.data.remote.model.SpotifyDevice

/**
 * The ONE `me/player/play` body the App Remote wake path sends once Spotify is listed as a device
 * (docs/PLAYER.md → Playback 404 Fallback). Pure Kotlin.
 */
sealed interface WakeRestoreBody {
    /** `context_uri` + `offset.uri` = the current item. The offset is honoured even with shuffle ON. */
    data class Context(val contextUri: String) : WakeRestoreBody

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
 *     REAL context since the device pass of 2026-09-25 pm: `context_uri = spotify:user:<id>:collection`
 *     + `offset.uri` (or `.position`) is accepted and positions inside the collection — the 2021
 *     "Can't have offset for context type: COLLECTION" error is gone. A collection reported in
 *     another form (`spotify:collection:…`) is addressed through [collectionUri], the user's own;
 *     without one it falls to the liked window. An EPISODE never gets a `spotify:show:` context:
 *     a show is not a documented `context_uri`.
 *  2. A [PlaybackOrigin.Liked] origin → [collectionUri] as the context when known, else the liked
 *     window from [currentUri] (the pre-2026-09-25 shape, still the fallback with no user id) — if
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
 *  1. the active device;
 *  2. a `Smartphone` or `Tablet` whose name equals one of [nameHints] (Settings.Global
 *     `device_name`, `Build.MODEL`) — Tablet too because a foldable registers as a tablet when
 *     unfolded (docs/SPOTIFY.md → "This device" design rationale);
 *  3. the only `Smartphone` listed;
 *  4. null — ambiguous or absent, keep polling.
 *
 * Devices without an id or marked restricted cannot be targeted and are ignored. Every field is
 * read null-safely: Gson can leave the declared-non-null `name` / `type` null.
 */
fun pickLocalDevice(devices: List<SpotifyDevice>, nameHints: List<String>): SpotifyDevice? {
    val candidates = devices.filter { !it.id.isNullOrBlank() && !it.isRestricted }
    candidates.firstOrNull { it.isActive }?.let { return it }
    val hints = nameHints.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
    // `String?.equals` is null-safe on the receiver; `name` is widened to String? before use.
    fun typeIs(d: SpotifyDevice, t: String) = d.type.equals(t, ignoreCase = true)
    candidates.firstOrNull { d ->
        val rawName: String? = d.name
        val name = rawName?.trim()
        (typeIs(d, "Smartphone") || typeIs(d, "Tablet")) &&
            name != null && hints.any { it.equals(name, ignoreCase = true) }
    }?.let { return it }
    return candidates.filter { typeIs(it, "Smartphone") }.singleOrNull()
}

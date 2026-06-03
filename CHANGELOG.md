# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Spotify Connect device switching** — `AssistChip` on the left side of the player action bar shows the active device name and type icon; tapping opens a bottom sheet listing all available Spotify Connect devices; each device shown as a `ListItem` with a `RadioButton` indicating the active one; "This device" shown as a separate `ElevatedCard` above the list (not a radio item) with a subtext explaining it wakes Spotify when dead; other devices transfer via `PUT /me/player` (preserves playback position); "This device" uses the App Remote SDK (`connectAndPlay`) as a fallback when Spotify is not running locally; accessible from both the full player screen and the pop-out panel on wide screens

## [2.1.0] - 2026-06-03

### Added
- **Indeterminate progress while Spotify wakes up** — seek bar switches to an indeterminate wavy animation and the play/pause button shows an M3 `LoadingIndicator` (bouncing dots) from the moment a track is tapped until playback is confirmed; applies to the player screen, pop-out panel, and mini player (library / album / artist screens); covers all wake paths: tapping a track (including first-ever tap with no prior song loaded), tapping play on a playlist from the library, and skip/previous when Spotify is dead

### Changed
- **Full-screen loading states** now use M3 `ContainedLoadingIndicator` throughout — Queue, Album Detail, Artist Detail, Search, Library browser, track list, Add to Playlist sheet, and deep-link screen; replaces the plain `CircularProgressIndicator`
- **Smooth seek bar** — progress now animates continuously instead of jumping once per second; poll corrections blend in invisibly; snaps immediately on seek, track change, or pause
- **Progress bar resets to 0 and freezes on every new-track action** — tapping a track, pressing skip, or pressing Play (Spotify dead) all immediately stop the tick and reset progress to 0; the bar starts counting only once the new track is confirmed playing, so there is no more counting from the old position during loading or indeterminate phases
- **Skip next/previous** now detects the new track within ~700 ms (retry poll loop) instead of waiting up to 3 s for the background poll
- **Pause icon / rotating cookie** appear immediately after tapping a track or skip, even when the player was previously paused — fixed by setting an optimistic playing lock in `PlayerStateManager` so transient 204 polls during wake-up do not revert the UI to the play icon
- Removed "Waking up Spotify…" toast — the player UI now provides the feedback directly
- Upgraded Retrofit 2.11.0 → 3.0.0; service methods now return `T` directly (no `Response<T>` wrapper); `HttpException` handling centralised in `safeCall`
- Upgraded OkHttp 4.12.0 → 5.3.2; timeout configuration now uses `kotlin.time.Duration`
- `open.spotify.com` deep-link intent filters marked `android:autoVerify="false"` — verification was never possible (third-party domain); behaviour unchanged, lint warning resolved

## [2.0.0] - 2026-05-31

### Added
- **Open supported links** — tapping a Spotify share link (`open.spotify.com/track/…`, `/album/…`, `/artist/…`) offers to open in Lyra; track links play immediately and navigate to the player, album/artist links navigate directly to the respective detail screen
  > **Optional recommended setup**: Settings → Apps → Lyra → Set as default → Supported web addresses → enable `open.spotify.com`; after this, tapping any Spotify share link will offer Lyra as an option
- **Album detail screen** — accessible from search results and by tapping the album name in the player controls; shows artwork, release year, type, track count, runtime, full numbered track list with explicit badges, play button, and label/copyright footer
- **Album detail** adapts to screen width: two-pane layout (art + info left, tracks right) on wide screens (≥600dp), single-column on narrow; art sizes dynamically via `BoxWithConstraints` to fit the left pane on both tall and short wide screens
- **Artist detail screen** — accessible from search results, album detail (tap artist name), and player controls (tap artist name); shows artist photo, discography grouped into Albums / Singles / Compilations with a "Load more" button for large catalogues
- **Artist detail** uses the same two-pane adaptive layout as album detail
- **Search** now returns albums and artists alongside tracks; artists appear as a horizontal scrollable row of circle photos at the top of results (Instagram Stories style); tapping any result navigates to the respective detail screen
- **Artist name** in the player controls is tappable — navigates to artist detail
- **Album name** in the player is tappable — navigates to album detail
- **Share button** in the top bar of Album Detail and Artist Detail screens — opens the Android share sheet with the Spotify link for the album or artist
- **Bottom fade scrim** behind the navigation bar on search results, album detail, and artist detail screens, matching the Queue screen
- **Multiple artists** displayed everywhere a track's artist name appears (track lists, mini player, pop-out panel, full player screen, album detail rows) using " · " separator; artist names in the full player screen remain individually tappable
- **Mini player** now visible on Album Detail and Artist Detail screens, matching the Library screen behaviour — same accent colors, same animations, same wavy progress ring
- **On unfolded (≥600dp) and folded landscape**: mini player sits at the bottom of the right content pane, matching the Library two-pane layout; tapping a track or the play button opens the pop-out player panel instead of navigating away; tapping the mini player also opens the panel
- **On folded portrait**: tapping the mini player navigates to the full player screen with the shared album-art transition
- **Album art** morphs between the mini player and the full player screen when navigating via album/artist name taps (folded and unfolded)
- **Liked Songs queue** now uses the full cached track list at play time (up to 750 songs from the tapped position) instead of only what was loaded on screen
- **Background liked-songs fetcher**: while music is playing (notification visible), Lyra quietly fetches one page of liked songs every 30 seconds into the library cache until the full library is indexed; the fetcher respects Spotify's rate-limit window and backs off on 429
- **Like/unlike** from the player now surgically updates the liked-songs cache (prepend on like, remove on unlike) without invalidating or re-fetching the full list; external likes/unlikes are detected by count diff on next library open — new songs prepended in one API call, removed songs trigger a full refetch
- **Sleep timer Live notification** on Android 16+ devices — when a sleep timer is active, a separate pinned notification appears in the Live notifications section showing a live countdown and a Cancel button; on Samsung OneUI 8, requires enabling "Live notifications for all apps" in Developer options (a Samsung limitation)
- **Settings → Notifications** section (Android 16+ only) — shows whether Live notifications are enabled for Lyra; on Samsung, tapping Enable opens an explanatory dialog with a direct link to Developer options

### Changed
- **Pull-to-refresh** on liked songs now preserves background-fetch progress beyond page 1 instead of overwriting the full cache with only 50 tracks

### Fixed
- **Album track count** now reads "1 song" instead of "1 songs"
- **Playlist thumbnails** not showing in the library browser — Spotify's thumbnail URL was available on every playlist object but `PlaylistCard` and `PlaylistListCard` only ever rendered a mosaic or a blank icon; both now fall back to the Spotify thumbnail when no mosaic exists, with mosaic generation skipped entirely for playlists that already have Spotify art
- **Queue screen crash** when shuffle is on with a small playlist — Spotify's queue API repeats the same track multiple times in the response; duplicates are now filtered out before display
- **Shuffle and repeat** active state now shows a small dot indicator below the button (both full player and pop-out panel), matching Spotify's own affordance; the previous color-only tint was hard to read against many album art colors
- **Launch crash** after adding `albumType` field to `SpotifyAlbum` — Gson bypasses Kotlin non-null defaults on deserialization, leaving the field `null` on cached tracks and crashing `hashCode()`; fixed by making the field nullable

## [1.2.0] - 2026-05-29

### Added
- Queue screen — tap the queue icon (left of Share) in the player to see what's playing and what's coming up next; read-only (Spotify's API does not support reordering or removing queue items)
- Queue refreshes on screen open, when the current track changes, and every 30 seconds while the screen is open

### Fixed
- Play/pause, skip next, and skip previous buttons now wake Spotify from a killed/force-stopped state via the App Remote SDK, matching the existing behavior when tapping a track from the track list
- Player UI no longer shows music as actively playing when Spotify has no active device — stale "playing" state clears within the next poll cycle
- "Waking up Spotify…" toast shown when the SDK fallback fires so the user knows the app is responding
- Progress bar wave now fades out within ~500 ms of pausing or Spotify becoming inactive (was 2+ seconds due to an external animation layered on top of M3's own internal amplitude transition)

## [1.1.0] - 2026-05-26

> **Breaking change**: this release requires new OAuth scopes (`playlist-modify-public`, `playlist-modify-private`). Go to **Settings → Disconnect Spotify** and reconnect to grant them. If adding to playlists still fails, revoke access at spotify.com/account/apps and reconnect.

### Added
- Now Playing persistent notification with foreground service (required for reliable Spotify App Remote callbacks in the background)
- Sleep timer cancel action in the notification, showing minutes remaining
- Add to Playlist button on the player — bottom sheet with thumbnails and checkboxes, stays open for multi-playlist adds
- Share button on the player — shares the Spotify track link via the Android share sheet
- Pull-to-refresh on the track list (current playlist / liked songs)
- Pull-to-refresh on the library browser (picks up playlist additions, removals, and reorders from Spotify)
- Opening a playlist now silently refreshes its tracks in the background even when the cache is fresh

### Fixed
- Scroll snap coroutine permanently dying after a fling interrupted it (`CancellationException` from `MutatorMutex`)
- Snap racing with the pull-to-refresh settle animation
- Snap looping indefinitely on short playlists that cannot fully collapse the header

## [1.0.3] - 2026-05-25

### Added
- Version number displayed in Settings → About

### Changed
- All hardcoded UI strings moved to `strings.xml`
- Dependency updates: Kotlin 2.3.10 → 2.3.21, Compose BOM 2026.04.01 → 2026.05.01, Material3 alpha19 → alpha20, Coil 3.1.0 → 3.4.0, and several others

### Fixed
- Splash screen animated icon was never shown — `Theme.Lyra.Splash` was defined but not applied to the activity

## [1.0.2] - 2026-05-24

### Fixed
- Pop-out player scrim did not cover the status bar in two-pane (unfolded) mode

## [1.0.1] - 2026-05-24

### Added
- M3 Expressive 12-sided cookie shape for the play/pause button
- Cookie shape rotates slowly while playing; the icon counter-rotates to stay upright

### Fixed
- App showed the login screen after access token expiry (~1 hour idle) instead of silently refreshing

## [1.0.0] - 2026-05-23

### Added
- Initial release

[Unreleased]: https://github.com/CrsMthw/Lyra/compare/v2.1.0...HEAD
[2.1.0]: https://github.com/CrsMthw/Lyra/compare/v2.0.0...v2.1.0
[2.0.0]: https://github.com/CrsMthw/Lyra/compare/v1.2.0...v2.0.0
[1.2.0]: https://github.com/CrsMthw/Lyra/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/CrsMthw/Lyra/compare/v1.0.3...v1.1.0
[1.0.3]: https://github.com/CrsMthw/Lyra/compare/v1.0.2...v1.0.3
[1.0.2]: https://github.com/CrsMthw/Lyra/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/CrsMthw/Lyra/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/CrsMthw/Lyra/releases/tag/v1.0.0

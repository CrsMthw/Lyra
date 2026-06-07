# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Home-screen widget (Now Playing)** — resizable Jetpack Glance widget with three layouts chosen from the actual size (`SizeMode.Exact`): compact (art + title/artist + play-pause), medium (adds prev/next), large (album-art hero that scales to the widget size + prev/play/next + shuffle/repeat); built on standard Material 3 (Glance `1.3.0-alpha01`; Material 3 Expressive is not yet renderable in widgets). Colours are seeded from the album-art palette while a track is present (same vibrant/dominant recipe as the player screen) and fall back to wallpaper/dynamic colours in the empty "Nothing playing" state; AMOLED honoured. State is mirrored from `PlayerStateManager` via an app-scoped collector into the widget's own Glance state (`updateAppWidgetState`/`currentState`), which is what drives recomposition (no second poller); album art is downsampled and palette-extracted once per track. Controls route through `LyraForegroundService` actions (`actionStartService`) so taps work cold without the process-death gap of a bare callback; whole-surface tap opens Lyra. Widget updates are skipped entirely when no widget is placed.
- **Synchronized lyrics** — full player screen (portrait and landscape) shows a synchronized, auto-scrolling lyrics view in place of the album art when enabled; preference persists to DataStore across app restarts; sourced from LRCLIB (lrclib.net) — a free, community-maintained LRC lyrics database; a separate OkHttp/Retrofit client (no Spotify token interceptor) is used; fetch strategy: exact `GET /api/get` by artist/title/album/duration, falling back to `GET /api/search` if not found; results cached in memory per track; pop-out panel and mini player are intentionally excluded
- **Synchronized lyrics display** — active line is bold `titleLarge` centered in the art area; surrounding lines fade with distance (65% / 35% / 18% alpha); `LazyColumn` auto-scrolls to keep the active line vertically centered; blank LRC lines rendered as spacers for musical pauses; plain-lyrics fallback (static scrollable `bodyLarge` text) when only unsynced lyrics are available
- **Audio visualizer** — `android.media.audiofx.Visualizer(0)` captures the global output mix and drives two reactive painters: `FftCWave` (smooth Akima-spline circular filled-disc that pulses outward past the art edge, with gravity-decay physics) pulsates behind the album art in `PlayerScreen` (portrait and landscape); `FftWave` (mirrored horizontal wave) overlays the bottom scrim in `QueueScreen`; toggled via the top-bar media menu Visualizer item; `RECORD_AUDIO` permission requested on-demand only when the user first enables it (never at launch); off by default, persisted in DataStore; degrades gracefully if permission is denied or revoked
- **Visualizer Settings toggle** — Settings → Player section has a dedicated Visualizer toggle to enable or disable the audio wave animation; persisted in DataStore; complements the existing toggle in the player top-bar media menu
- **Player top-bar media menu** — M3 Expressive `DropdownMenu` behind a `Tune` icon button in the player `TopAppBar`; replaces the earlier bottom-right `FloatingActionButtonMenu`; two `DropdownMenuGroup`s: first group has Lyrics (checkable) and Visualizer (checkable) as connected pill items; second group has Sleep Timer with active-timer supporting text; `containerColor = Color.Transparent` + `shadowElevation = 0.dp` on the outer `DropdownMenu` so M3's group pill surfaces render without a wrapping white elevated box
- **Create playlist** — "New playlist" row at the top of the add-to-playlist sheet opens a `BasicAlertDialog` with a creation form (name, description, public/private toggle); playlist is created via `POST /me/playlists` and the current track is added immediately; new playlist appears checked at the top of the list and is prepended to the library cache; accessible from both the full player screen and the pop-out panel

### Changed
- **Player media menu toggle behavior** — Lyrics and Visualizer items no longer dismiss the menu on tap; menu stays open so both can be toggled without reopening; only the Sleep Timer item dismisses (it opens a dialog)
- **Player media menu checked state** — leading icon swaps to `Icons.Default.Check` when active (trailing checkmark was removed — it caused the menu width to expand on toggle, shifting the layout); `selectedContainerColor` is `surfaceAccentColor.copy(alpha = 0.15f)` for a subtle album-art-matched tint instead of a solid fill
- **Secure storage** — `EncryptedPrefs` migrated from the deprecated `androidx.security.crypto.EncryptedSharedPreferences` to a direct Android Keystore implementation; each value encrypted with AES-256-GCM (randomised IV, 256-bit key, 128-bit GCM tag) and stored as Base64 in standard `SharedPreferences`; `security-crypto` dependency removed entirely; **note**: stored credentials (Client ID + tokens) are cleared on first launch after this update — re-enter your Spotify Client ID and reconnect once
- **Dependency updates** — Material3 `1.5.0-alpha20` → `1.5.0-alpha21`; `core-ktx` `1.18.0` → `1.19.0`
- **`PlayerPanelHost` screen height** now reads from `LocalWindowInfo.containerSize` instead of the deprecated `LocalConfiguration.screenHeightDp`, consistent with the window-size-class API used elsewhere for adaptive layout
- **Circle visualizer beat-snap rotation** — on each energy spike detected in `FftCWavePainter`, the FFT band array is rotated by a random offset (25–67% of the circle) before mapping to the disc, so the bass bulge blooms from a different position on the circle each beat; beat detection uses an exponential moving average of per-frame RMS energy with a 1.6× threshold and 300 ms cooldown; `FftWavePainter` (bottom wave) is unaffected — both painters receive the same raw FFT bytes independently
- **Visualizer animation model** — `GravityModel` rewritten from physics-decay (`dy`/`ay` per-frame gravity) to a held-target asymmetric envelope (ATTACK=0.35, RELEASE=0.06); each band holds its target between FFT updates instead of decaying, eliminating the jitter caused by the 43 Hz FFT / 120 Hz render mismatch; silence detection zeros all targets when no non-quiet data arrives for >100 ms, letting the release curve drain bars smoothly to zero
- **Frequency tilt** — `applyFrequencyTilt` ramps gain linearly from 1× (lowest bin) to 5× (highest bin), applied after the power transform in the circular painter and before mirroring in the wave painter; high-frequency detail now remains visible during heavy bass passages instead of being drowned out
- **Visualizer accent color** — all `FftWaveCanvas` instances now use the palette-derived `surfaceAccentColor` (via `LocalVisualizerAccentColor` CompositionLocal provided in `LyraNavGraph`) instead of `onAccentColor`; consistent with the circular visualizer color across all screens: Library, Album, Artist, Search, Player, and Queue
- **Landscape player layout is now dynamic** — art size uses `minOf(maxWidth, maxHeight * 0.82f)` so it shrinks automatically on height-constrained screens (e.g. folded landscape) without a separate UI branch; controls column replaced `Column + verticalScroll` with `BoxWithConstraints`; spacers between controls sections are proportional to available height (`spacingLarge = maxHeight * 0.04f` clamped to 6–16dp, `spacingSmall = maxHeight * 0.03f` clamped to 4–12dp) so the full controls UI fits without scrolling on any landscape phone

### Removed
- **Liked Songs manual refresh** removed from Settings → Storage — pull-to-refresh on the track list covers this

### Fixed
- **Settings screen missing bottom fade scrim** — `SettingsScreen` had `navigationBarsPadding()` applied directly to the scrollable `Column` with no `Box` wrapper, so there was no overlay surface for the scrim; restructured to wrap in a `Box`, removed `navigationBarsPadding()` from the `Column`, added a matching bottom `Spacer(scrimHeight)`, and overlaid the `Brush.verticalGradient` scrim `Box` at `Alignment.BottomCenter`, consistent with all other scrollable screens
- **Bottom visualizer clipped to right pane on Album Detail and Artist Detail wide screens** — `FftWaveCanvas` was placed inside the right card's `Box`, so it only covered 58% of the screen width; moved to an outer `Box` wrapping the full `Row` so it spans both panes at full width, matching the Library screen layout; right-card scrim now fades to `surface` instead of `background` to match the Card's background color
- **Shuffle and repeat buttons misaligned with skip buttons** — the dot indicator was placed in a `Column` below the `IconButton`, making the Column's layout height 53dp (48dp button + 5dp dot) vs the 48dp skip buttons; the Row's `verticalAlignment = Alignment.CenterVertically` was centering the Column's midpoint instead of the icon's midpoint, pushing shuffle/repeat ~2.5dp too high; fixed by wrapping in a `Box` instead — the dot overlays with `align(Alignment.BottomCenter).offset(y = (-5).dp)` outside the layout flow so the Box reports 48dp height, matching the skip buttons; applies to both `PlayerScreen` and `PlayerCardContent`

## [2.2.0] - 2026-06-03

### Added
- **Spotify Connect device switching** — `AssistChip` on the left side of the player action bar shows the active device name and type icon; tapping opens a bottom sheet listing all available Spotify Connect devices; each device shown as a `ListItem` with a `RadioButton` indicating the active one; "This device" shown as a separate `ElevatedCard` above the list (not a radio item) with a subtext explaining it wakes Spotify when dead; other devices transfer via `PUT /me/player` (preserves playback position); "This device" uses the App Remote SDK (`connectAndPlay`) as a fallback when Spotify is not running locally; accessible from both the full player screen and the pop-out panel on wide screens
- **Queue, share, and add-to-playlist buttons in the pop-out player panel** — the full action bar (device chip + three action buttons) now appears on both the pop-out panel and the full player screen

### Fixed
- **Play/pause button icon invisible on white album art** — the cookie button's icon and waking-up `LoadingIndicator` now use `onAccentColor` (Black on light art, White on dark) instead of hardcoded white, so they remain visible when `accentColor` is white or near-white

### Changed
- **Player action bar** (both surfaces) — queue, share, and add-to-playlist buttons are now an M3 connected `ButtonGroup` using `customItem` + S-size `FilledTonalIconButton` (40dp container, 18dp icon, `ButtonGroupDefaults` connected corner shapes); replaces the previous three separate `IconButton`s whose `clickableItem` label rendered as visible text, causing layout reflow when dragging across buttons
- **Action bar accent colors** — the device `AssistChip` and all three action buttons now use album art `surfaceAccentColor` (12% alpha container, full-opacity icons, 40% alpha chip border) instead of Material You colors, matching the seek bar, shuffle/repeat indicators, and like button
- **Pop-out panel height and scroll** — panel is capped at 80% of screen height; album art shrinks dynamically via `BoxWithConstraints` to fit rather than the panel scrolling; scrim and panel dismiss immediately when folding the phone
- **Pop-out player panel modularized** — `LibraryScreen` previously duplicated player management (`SharedTransitionLayout`, `MiniPlayerHolder`, `BackHandler`, `PlayerPopOutPanel`) in both `TwoPaneLayout` and `SinglePaneLayout`; all screens now delegate to `PlayerPanelHost`, consistent with Album/Artist screens
- **Spacing** between playback controls and action bar: 4dp → 24dp in the pop-out panel, 0dp → 16dp explicit spacer in the full player screen
- **Album Detail and Artist Detail two-pane layouts** now use the same Card-based style as the Library screen — each pane wrapped in a `Card` with rounded top corners and an 8dp gap; `VerticalDivider` removed
- **Adaptive breakpoints** migrated from manual `LocalConfiguration.current.screenWidthDp >= 600` to `currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(600)` across all screens (Library, Album Detail, Artist Detail, PlayerPanelHost)

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

[Unreleased]: https://github.com/CrsMthw/Lyra/compare/v2.2.0...HEAD
[2.2.0]: https://github.com/CrsMthw/Lyra/compare/v2.1.0...v2.2.0
[2.1.0]: https://github.com/CrsMthw/Lyra/compare/v2.0.0...v2.1.0
[2.0.0]: https://github.com/CrsMthw/Lyra/compare/v1.2.0...v2.0.0
[1.2.0]: https://github.com/CrsMthw/Lyra/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/CrsMthw/Lyra/compare/v1.0.3...v1.1.0
[1.0.3]: https://github.com/CrsMthw/Lyra/compare/v1.0.2...v1.0.3
[1.0.2]: https://github.com/CrsMthw/Lyra/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/CrsMthw/Lyra/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/CrsMthw/Lyra/releases/tag/v1.0.0

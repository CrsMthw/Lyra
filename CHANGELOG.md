# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/CrsMthw/Lyra/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/CrsMthw/Lyra/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/CrsMthw/Lyra/compare/v1.0.3...v1.1.0
[1.0.3]: https://github.com/CrsMthw/Lyra/compare/v1.0.2...v1.0.3
[1.0.2]: https://github.com/CrsMthw/Lyra/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/CrsMthw/Lyra/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/CrsMthw/Lyra/releases/tag/v1.0.0

<div align="center">
  <img src="assets/icons/Lyra_light.png" width="120"/>
  <h1>Lyra</h1>
  <p>A minimal Spotify client for Android with an adaptive layout for phones, foldables, and tablets.<br/>Lyra uses your own Spotify Developer credentials — no third-party servers, no data collection.</p>
  <p><sub>Current release: <b>v4.0.0 “Quattro”</b></sub></p>
</div>

---

## Features

**Your library**
- Library browser with four tabs — **Playlists**, **Albums** (saved), **Artists** (followed) and **Shows** (followed podcasts) — plus Liked Songs, all cached for an instant first paint and refreshed stale-while-revalidate
- Playlist detail with **drag-to-reorder**, **rename / edit description** and **multi-select removal** for the playlists you own, with counts confirmed against Spotify after every edit
- An opt-in **“For you” band** — *Jump back in* and *On repeat* rows, off by default and making no API calls until you turn it on
- A **listening-stats** screen — top artists and top tracks over 4 weeks / 6 months / all time

**Playback**
- Full player with seek, shuffle, repeat, a sleep timer (Live notification countdown on Android 16+) and a queue screen; **Add to queue** and **Share** from the player or any song's touch-and-hold menu
- **Podcasts** — follow, browse and play shows; episode rows show what you have finished and how much is left, and every player surface handles an episode as the now-playing item
- Synchronized lyrics — time-synced, auto-scrolling lyrics on the player screen (via LRCLIB), with a plain-text fallback when synced lyrics aren't available
- Audio visualizer — optional, album-art-coloured, reacting across the audible range (ProjectM-style per-band normalization); a circular pulse behind the album art (in the full player *and* the pop-out panel), a wave along the bottom of other screens, or both, with resolution, gain and averaging controls in Settings
- Spotify Connect device switching — transfer playback to any device on your account, with a volume slider for the active device; "This device" wakes Spotify locally when it isn't running
- Home-screen widget — resizable Now Playing widget with playback controls; its layout and artwork scale to the size you choose, and its colours are drawn from the current album art

**Finding things**
- Search for tracks, albums, artists and shows behind swipeable tabs, each type paging on its own; a Recent list of what you last opened, and the search button morphing into the search bar as you open it
- Album and artist detail screens — full track lists, play + shuffle, a discography grouped by Albums / Singles / Compilations
- Open Spotify share links directly in Lyra — tracks, albums, artists, playlists, shows and episodes, including the short `spotify.link` links and `spotify:` URIs

**The frame around it**
- Adaptive layout for every screen — single-pane on phones, a two-pane browser/detail split on foldables and tablets, and a permanent docked full-player third pane on large landscape screens (tablets, Chromebooks)
- Material 3 Expressive design — a real app bar on every screen, spring-based motion, predictive back that tracks your finger everywhere (including the album-art morph into the full player), Material You dynamic colour and AMOLED black mode
- One mini player, on every browse screen, that rides above the keyboard and opens into a pop-out panel on wide screens
- Haptic feedback — a single semantic vocabulary of Material 3 Expressive haptics throughout the UI, toggleable in Settings
- Accessibility — every control announces its role and state to TalkBack, headings for section navigation, labelled gestures, and a widget that reads out what is playing
- Fast cold starts — a Baseline Profile compiles the start-up path at install time
- Pull-to-refresh on the library and every track list
- Tokens stored encrypted via AES-256-GCM (Android Keystore); the whole library cache lives on your device

---

## Can you find the easter egg?

There is something hidden in Lyra. It is not on any menu, no setting names it, and nothing in the app will tell you it is there. It has been there since the first 4.0 build. Some people find it in a minute; some never do. If you do, keep it to yourself — half the fun is that the next person has to look.

---

## Screenshots

### Single pane

<table>
  <tr>
    <td><img src="assets/screenshots/single-pane/library-light.png" width="200"/></td>
    <td><img src="assets/screenshots/single-pane/tracks-light.png" width="200"/></td>
    <td><img src="assets/screenshots/single-pane/player-light.png" width="200"/></td>
  </tr>
  <tr>
    <td><img src="assets/screenshots/single-pane/library-dark.png" width="200"/></td>
    <td><img src="assets/screenshots/single-pane/tracks-dark.png" width="200"/></td>
    <td><img src="assets/screenshots/single-pane/player-dark.png" width="200"/></td>
  </tr>
</table>

### Dual pane

<table>
  <tr>
    <td><img src="assets/screenshots/dual-pane/library-2p.png" width="400"/></td>
    <td><img src="assets/screenshots/dual-pane/mini-player-2p.png" width="400"/></td>
  </tr>
</table>

---

## Install

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="80">](http://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/CrsMthw/Lyra)

Or download the latest APK from the [Releases](../../releases) page and install it directly on your device. You may need to allow installation from unknown sources in your Android settings.

> ⚠️ **Existing Lyra users (upgrading from 3.x): reconnect Spotify once.** New users can skip this — a first sign-in already grants everything. The APK installs over 3.1.4 in place and everything keeps working on your existing sign-in — except podcast episode progress, which needs one more permission that Spotify grants only on a fresh sign-in. After installing:
> 1. In Lyra: **Settings → Disconnect Spotify**.
> 2. On [spotify.com/account/apps](https://www.spotify.com/account/apps/) (*Manage apps*): **Remove access** for Lyra.
> 3. Open Lyra and sign in again with the **same Client ID**.

Before launching, you need to register the app in the Spotify Developer Dashboard — this is a one-time step and takes about two minutes.

### 1. Create a Spotify Developer app

1. Go to [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard)
2. Click **Create app**
3. Fill in any name and description — these are just for your dashboard
4. At the bottom, under **Which API/SDKs are you planning to use?**, check **Web API** and **Android**

### 2. Configure your app settings

In your app's **Settings**:

- Under **Redirect URIs**, add the following and save:
  ```
  com.crsmthw.lyra://callback
  ```
- Under **Android**, add:

  | Field | Value |
  |---|---|
  | Package name | `com.crsmthw.lyra` |
  | SHA-1 certificate fingerprint | `50530A2931B5B1595D1C991F92DA6644ABA6AFD6` |

### 3. Get your Client ID

From the dashboard overview, copy your **Client ID**. Enter it in the app on first launch — it is stored encrypted on-device and never leaves it.

---

## Opening Spotify links in Lyra by default

Android hands a web link to the app that has *verified* the domain, and only Spotify can verify `spotify.com` — so Lyra can never take Spotify links automatically. Two switches make it the default:

1. **Turn Spotify's link handling off.** Settings → Apps → Spotify → **Set as default** (called *Open by default* on some phones) → switch **Open supported links** off. Individual links can't be turned off there on One UI, so the whole switch goes off.
2. **Turn Lyra's on.** Settings → Apps → Lyra → **Set as default** → switch **Open supported links** on, then **Add link** and tick every listed host (`open.spotify.com`, `spotify.link`, `spotify.app.link`, `spotify-alternate.app.link`).

After that, any shared Spotify link — including the short `spotify.link` links the Spotify app's share sheet produces, the `open.spotify.com/intl-xx/…` locale form and `spotify:` URIs — opens in Lyra: tracks and episodes start playing and the player opens, albums, artists and shows open their pages, and one of your own playlists opens in the Library. To go back, reverse the two switches.

## Building

### Prerequisites

- Android Studio Meerkat or later
- Android SDK 37 (compile), min SDK 35
- **JDK 21** for command-line builds — the Gradle daemon is pinned to a 21 toolchain (`gradle/gradle-daemon-jvm.properties`) and will not fall back to a newer `JAVA_HOME`; Android Studio's bundled JDK 25 is fine inside the IDE only
- A free [Spotify Developer account](https://developer.spotify.com)
- The Spotify app installed on your device (required for App Remote playback)

Follow the Spotify Developer Dashboard Setup steps in the [Install](#install) section above. For debug builds, also add the SHA-1 of your local debug keystore (found via `./gradlew signingReport`) to the Android package settings in your Spotify app.

### 1. Get the Spotify App Remote SDK

The SDK is proprietary and cannot be redistributed, so it is not included in this repo.

1. Go to [github.com/spotify/android-sdk/releases](https://github.com/spotify/android-sdk/releases)
2. Download `spotify-app-remote-release-x.x.x.aar`
3. Place it in `app/libs/` and rename it to `spotify-app-remote-release-0.8.0.aar` (or update the filename in `app/build.gradle.kts` to match your downloaded version)

### 2. Build

Clone the repo, add the AAR as above, then open the root folder in Android Studio.

```bash
# Debug build
./gradlew assembleDebug

# Release build (signed if local.properties holds the keystore entries, unsigned otherwise)
./gradlew assembleRelease

# JVM unit tests (the visualizer maths, the lyrics parser, the API models, the playlist paging and reorder logic)
./gradlew :app:testDebugUnitTest

# Full lint — the release build runs only lint-vital
./gradlew :app:lintRelease
```

The Baseline Profile in `app/src/main/generated/baselineProfiles/` is checked in; regenerating it needs the `:baselineprofile` module and a Gradle-managed emulator (`./gradlew :app:generateBaselineProfile`), never a connected phone.

---

## Architecture

```
app/src/main/java/com/crsmthw/lyra/
├── data/
│   ├── auth/        SpotifyAuthManager (OAuth 2.0 PKCE via AppAuth), TokenManager
│   ├── local/       EncryptedPrefs, LyraDataStore, LibraryCache, LikedSongsIndexer, ReorderCalculator
│   ├── player/      PlayerStateManager (shared player-state observer for the service + widget)
│   ├── remote/      SpotifyApiService (Retrofit), SpotifyRemoteManager (App Remote)
│   │   └── model/   Spotify API data models
│   └── repository/  SpotifyRepository, SettingsRepository
├── di/              AppContainer — manual DI, no Hilt
├── service/         LyraForegroundService (Now Playing + sleep timer notifications, widget controls)
├── ui/
│   ├── components/  MiniPlayer, PlayerCardContent, PlayerPopOutPanel, PlayerPanelHost (the one app-wide player surface),
│   │                RootTopBar / DetailTopBar (the M3 app bars), DetailArtHero, TrackRow, AddToPlaylistSheet,
│   │                DevicePickerSheet, TrackActionsSheet, BottomSheetCap, ValueSlider
│   ├── navigation/  LyraNavGraph, Screen
│   ├── screens/     auth / library / player / search / settings / album / artist / show / queue / stats / deeplink
│   └── theme/       Material You + static colour schemes, AMOLED overlay
├── util/            Extensions, Motion, Haptics, AlbumArtColor, LrcParser, PagerTabs,
│                    visualizer (Visualizer(0) capture + per-band-normalized FFT painters)
└── widget/          Home-screen widget (Jetpack Glance)

baselineprofile/     Gradle-managed-device generator for the checked-in Baseline Profile
app/src/test/        JVM unit tests (171)
```

**Auth**: PKCE via AppAuth — browser-based OAuth, no client secret ever stored.

**DI**: Manual `AppContainer` created in `LyraApplication`. No annotation processing.

**Playback**: Web API first; falls back to Spotify App Remote SDK on 404 (no active device). The SDK binds directly to the Spotify service via IPC, bypassing the Connect device requirement.

**Caching**: Coil disk cache (150 MB, survives system cache clears) for images. Gson-based JSON cache for library data with stale-while-revalidate refresh and per-playlist snapshot invalidation.

---

## Tech Stack

| Layer | Library |
|---|---|
| UI | Jetpack Compose (BOM 2026.09.00) + Material 3 Expressive (Material3 1.5.0-alpha28) |
| Navigation | Navigation Compose 2.10.1 |
| Auth | AppAuth 0.11.1 (PKCE) |
| Network | Retrofit 3.0.0 + OkHttp 5.5.0 |
| Images | Coil 3.6.3 |
| Drag-to-reorder | Reorderable 3.1.0 (`sh.calvin.reorderable`) |
| Widgets | Jetpack Glance 1.3.0-alpha02 |
| Secure storage | Android Keystore (AES-256-GCM) |
| Settings | DataStore Preferences |
| Performance | AndroidX Baseline Profile 1.5.0 + profileinstaller 1.4.1 |
| Tests | JUnit 4 · kotlin-test · kotlinx-coroutines-test |
| Build | AGP 9.4.1 · Kotlin 2.4.20 · Gradle 9.7.1 · JDK 21 · min SDK 35 · target SDK 37 |

---

## Credits

- **Lyrics** — [LRCLIB](https://github.com/tranxuanthang/lrclib), a free and open-source lyrics API
- **Visualizer** — visual style inspired by [Nier-Visualizer](https://github.com/bogerchan/Nier-Visualizer) and [NextGenVisualizer](https://github.com/jeffshee/NextGenVisualizer)
- **Visualizer audio analysis** — [projectM](https://github.com/projectM-visualizer/projectm), an open-source music visualizer whose per-band normalization + logarithmic-equalize approach Lyra's analysis is modeled on (reimplemented in Kotlin; no projectM code is used)
- **Drag-to-reorder lists** — [Reorderable](https://github.com/Calvin-LL/Reorderable) by Calvin Liang
- **Fonts** — Liberation Sans (SIL Open Font License, see `app/src/main/assets/fonts/`)
- **App icon** — idea by BambiD, drawn up digitally by Shubbu

---

## License

MIT

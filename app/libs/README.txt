Spotify App Remote SDK – required to build
==========================================

The SDK is proprietary and cannot be redistributed, so the .aar is gitignored and
NOT in this repository. The build references it unconditionally
(app/build.gradle.kts: implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))),
so without it the project does not compile.

1. Go to: https://github.com/spotify/android-sdk/releases
2. Download: spotify-app-remote-release-x.x.x.aar
3. Place it in THIS directory (app/libs/) as spotify-app-remote-release-0.8.0.aar
   (or change the filename in app/build.gradle.kts to match the version you downloaded)

The SDK is what plays music when Spotify has no active Connect device: the Web API
is tried first and the App Remote binding is the 404 fallback (see SpotifyRemoteManager).

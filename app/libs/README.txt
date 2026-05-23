Spotify App Remote SDK – required for playback control
======================================================

1. Go to: https://github.com/spotify/android-sdk/releases
2. Download: spotify-app-remote-release-x.x.x.aar
3. Place the .aar file in THIS directory (app/libs/)
4. In app/build.gradle.kts, uncomment:
       implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
   (adjust the filename to match your downloaded version)
5. In SpotifyRemoteManager.kt, uncomment the real implementation blocks
   and remove the stub code.

Without the AAR the app compiles and runs — playback controls are no-ops.

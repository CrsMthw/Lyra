package com.crsmthw.lyra.di

import android.content.Context
import android.os.Build
import android.provider.Settings
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.crsmthw.lyra.data.auth.SpotifyAuthManager
import com.crsmthw.lyra.data.auth.TokenManager
import com.crsmthw.lyra.data.local.EncryptedPrefs
import com.crsmthw.lyra.data.local.LibraryCache
import com.crsmthw.lyra.data.local.LikedSongsIndexer
import com.crsmthw.lyra.data.local.LyraDataStore
import com.crsmthw.lyra.data.local.PlaybackOriginStore
import com.crsmthw.lyra.BuildConfig
import com.crsmthw.lyra.data.remote.LrcLibApiService
import com.crsmthw.lyra.data.remote.SpotifyApiService
import com.crsmthw.lyra.data.remote.SpotifyRemoteManager
import com.crsmthw.lyra.data.player.PlayerStateManager
import com.crsmthw.lyra.data.repository.LyricsRepository
import com.crsmthw.lyra.data.repository.SettingsRepository
import com.crsmthw.lyra.data.repository.SpotifyRepository
import com.crsmthw.lyra.util.visualizer.VisualizerManager
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import kotlin.time.Duration.Companion.seconds

class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * The names this phone is likely listed under in `me/player/devices` — the user-set device
     * name (Settings.Global `device_name`) and `Build.MODEL`. Read at call time (the name can be
     * edited); the wake restore matches them against the device list (`pickLocalDevice`).
     */
    fun localDeviceNameHints(): List<String> = listOfNotNull(
        runCatching {
            Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull(),
        Build.MODEL,
    ).filter { it.isNotBlank() }.distinct()

    // ── Local storage ────────────────────────────────────────────────────────
    val encryptedPrefs = EncryptedPrefs(context)
    val dataStore      = LyraDataStore(context)
    val libraryCache   = LibraryCache(context)
    /** Where the current playback came from — the play button's wake restore reads it. */
    val playbackOriginStore = PlaybackOriginStore(context)

    // ── Auth ─────────────────────────────────────────────────────────────────
    val authManager  = SpotifyAuthManager(context, encryptedPrefs)
    val tokenManager = TokenManager(encryptedPrefs, authManager)

    // ── Network ──────────────────────────────────────────────────────────────
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(tokenManager)
        .connectTimeout(15.seconds)
        .readTimeout   (15.seconds)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://api.spotify.com/v1/")
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val spotifyApiService: SpotifyApiService = retrofit.create(SpotifyApiService::class.java)

    // ── LRCLIB (separate client — no token interceptor) ───────────────────────
    private val lrcLibOkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "Lyra/${BuildConfig.VERSION_NAME} (github.com/CrsMthw/Lyra)")
                    .build()
            )
        }
        .connectTimeout(10.seconds)
        .readTimeout   (10.seconds)
        .build()

    private val lrcLibRetrofit = Retrofit.Builder()
        .baseUrl("https://lrclib.net/")
        .client(lrcLibOkHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val lrcLibApiService: LrcLibApiService = lrcLibRetrofit.create(LrcLibApiService::class.java)

    // ── Spotify short-link resolver (spotify.link / *.app.link — Branch) ──────
    // A DEDICATED plain client. It must never be `okHttpClient`, whose TokenManager interceptor
    // attaches the user's Spotify bearer token to every request — that token has no business
    // reaching a third-party link-shortening host.
    //
    // Redirects are followed BY HAND (`followRedirects = false`) so the hop count is capped and
    // every `Location` is inspected; with OkHttp following them itself, `Response.header("Location")`
    // reads null at every hop. Timeouts are short because a spinner is on screen for all of it.
    // See ui/screens/deeplink/LinkResolverViewModel.
    val linkResolverClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "Lyra/${BuildConfig.VERSION_NAME} (github.com/CrsMthw/Lyra)")
                    .build()
            )
        }
        .connectTimeout(5.seconds)
        .readTimeout   (5.seconds)
        .callTimeout   (10.seconds)
        .build()

    // ── Image client (token-free) ─────────────────────────────────────────────
    // Album art comes from Spotify's image CDN (i.scdn.co and friends), which needs no bearer
    // token and is not the Web API. Coil used to fetch through `okHttpClient`, so every art
    // request carried the user's access token to the CDN, could block on a token refresh inside
    // TokenManager's synchronized block, and shared the API client's dispatcher and connection
    // pool with the player poll. A dedicated client keeps the token where it belongs and isolates
    // a burst of art fetches (the iLyra CoverFlow prefetch) from API latency (2026-09-20).
    private val imageOkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15.seconds)
        .readTimeout   (15.seconds)
        .build()

    // ── Image loader (permanent disk cache in filesDir) ──────────────────────
    // 500 MB (Cris, 2026-09-21; was 150): the Classic's Cover Flow prefetch warms every liked song's
    // 640px cover — on the order of 2000 distinct covers, 100+ MB — and at 150 MB that set would
    // have churned the cache and evicted the rest of the app's art to fit.
    val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, 0.15)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.filesDir.resolve("lyra_image_cache").toOkioPath())
                .maxSizeBytes(500L * 1024 * 1024)
                .build()
        }
        .components { add(OkHttpNetworkFetcherFactory(imageOkHttpClient)) }
        .build()

    // ── Spotify App Remote ───────────────────────────────────────────────────
    val remoteManager = SpotifyRemoteManager(context, encryptedPrefs)

    // ── Mosaic generator ─────────────────────────────────────────────────────
    val mosaicGenerator = com.crsmthw.lyra.util.MosaicGenerator(imageLoader, context)

    // ── Repositories ─────────────────────────────────────────────────────────
    val spotifyRepository  = SpotifyRepository(spotifyApiService, encryptedPrefs)
    val settingsRepository = SettingsRepository(dataStore)
    val lyricsRepository   = LyricsRepository(lrcLibApiService)

    // ── App-scoped player state ───────────────────────────────────────────────
    val playerStateManager = PlayerStateManager(context, spotifyRepository, remoteManager, playbackOriginStore)

    // ── Liked-songs indexer (shared by the foreground service and the iLyra) ──
    val likedSongsIndexer = LikedSongsIndexer(libraryCache, spotifyRepository, playerStateManager)

    // ── Audio visualizer ─────────────────────────────────────────────────────
    val visualizerManager = VisualizerManager(context)

    // ── Home-screen widget ────────────────────────────────────────────────────
    val nowPlayingWidgetUpdater =
        com.crsmthw.lyra.widget.NowPlayingWidgetUpdater(context, imageLoader, dataStore)
}

package com.crsmthw.lyra.di

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.crsmthw.lyra.data.auth.SpotifyAuthManager
import com.crsmthw.lyra.data.auth.TokenManager
import com.crsmthw.lyra.data.local.EncryptedPrefs
import com.crsmthw.lyra.data.local.LibraryCache
import com.crsmthw.lyra.data.local.LyraDataStore
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

    // ── Local storage ────────────────────────────────────────────────────────
    val encryptedPrefs = EncryptedPrefs(context)
    val dataStore      = LyraDataStore(context)
    val libraryCache   = LibraryCache(context)

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

    // ── Image loader (permanent disk cache in filesDir) ──────────────────────
    val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, 0.15)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.filesDir.resolve("lyra_image_cache").toOkioPath())
                .maxSizeBytes(150L * 1024 * 1024)
                .build()
        }
        .components { add(OkHttpNetworkFetcherFactory(okHttpClient)) }
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
    val playerStateManager = PlayerStateManager(context, spotifyRepository, remoteManager)

    // ── Audio visualizer ─────────────────────────────────────────────────────
    val visualizerManager = VisualizerManager(context)

    // ── Home-screen widget ────────────────────────────────────────────────────
    val nowPlayingWidgetUpdater =
        com.crsmthw.lyra.widget.NowPlayingWidgetUpdater(context, imageLoader, dataStore)
}

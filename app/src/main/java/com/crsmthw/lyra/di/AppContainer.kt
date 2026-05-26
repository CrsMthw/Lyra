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
import com.crsmthw.lyra.data.remote.SpotifyApiService
import com.crsmthw.lyra.data.remote.SpotifyRemoteManager
import com.crsmthw.lyra.data.player.PlayerStateManager
import com.crsmthw.lyra.data.repository.SettingsRepository
import com.crsmthw.lyra.data.repository.SpotifyRepository
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout   (15, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://api.spotify.com/v1/")
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val spotifyApiService: SpotifyApiService = retrofit.create(SpotifyApiService::class.java)

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

    // ── App-scoped player state ───────────────────────────────────────────────
    val playerStateManager = PlayerStateManager(context, spotifyRepository, remoteManager)
}

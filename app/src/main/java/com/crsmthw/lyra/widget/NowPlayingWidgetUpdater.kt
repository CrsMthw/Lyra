package com.crsmthw.lyra.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.crsmthw.lyra.data.local.LyraDataStore
import com.crsmthw.lyra.data.player.PlayerState
import com.crsmthw.lyra.util.albumArtColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bridges [PlayerState] to the home-screen widget. Loads + downsamples album art, extracts the
 * album palette (same recipe as the player screen so the accent matches), writes a per-widget Glance
 * state snapshot, and pushes an update. Art + palette are re-derived only when the track's art URL
 * changes — play/pause/shuffle toggles reuse the cached values.
 */
class NowPlayingWidgetUpdater(
    private val context    : Context,
    private val imageLoader: ImageLoader,
    private val dataStore  : LyraDataStore,
) {
    @Volatile private var lastArtUrl  : String? = null
    @Volatile private var lastArtFile : String? = null
    @Volatile private var lastAccent: Int = SPOTIFY_GREEN
    // Edge/perimeter tone the widget background blends toward (stored in the DOMINANT Glance key).
    @Volatile private var lastBg    : Int = SPOTIFY_GREEN

    suspend fun update(state: PlayerState) {
        // No widget placed → do zero network/CPU. Without this, every track change would download
        // art + run Palette purely to feed a widget that doesn't exist.
        val ids = GlanceAppWidgetManager(context).getGlanceIds(NowPlayingWidget::class.java)
        if (ids.isEmpty()) return

        val track  = state.currentTrack
        val amoled = dataStore.amoledBlack.first()

        if (track == null) {
            ids.forEach { id ->
                updateAppWidgetState(context, id) { it[WidgetKeys.HAS_TRACK] = false }
            }
            NowPlayingWidget().updateAll(context)
            return
        }

        val artUrl = track.artUrl.takeIf { it.isNotBlank() }
        if (artUrl != null && artUrl != lastArtUrl) {
            loadArtAndPalette(artUrl)?.let { extracted ->
                lastArtUrl  = artUrl
                lastArtFile = extracted.file
                lastAccent  = extracted.accent
                lastBg      = extracted.bg
            }
        }

        ids.forEach { id ->
            updateAppWidgetState(context, id) { p ->
                p[WidgetKeys.HAS_TRACK] = true
                p[WidgetKeys.TITLE]     = track.name
                p[WidgetKeys.ARTIST]    = track.allArtists
                lastArtFile?.let { p[WidgetKeys.ART_FILE] = it } ?: p.remove(WidgetKeys.ART_FILE)
                p[WidgetKeys.PLAYING]   = state.isPlaying
                p[WidgetKeys.SHUFFLE]   = state.shuffleEnabled
                p[WidgetKeys.REPEAT]    = state.repeatState
                p[WidgetKeys.ACCENT]    = lastAccent
                p[WidgetKeys.DOMINANT]  = lastBg
                p[WidgetKeys.AMOLED]    = amoled
            }
        }
        NowPlayingWidget().updateAll(context)
    }

    private data class Extracted(val file: String, val accent: Int, val bg: Int)

    private suspend fun loadArtAndPalette(url: String): Extracted? = withContext(Dispatchers.IO) {
        try {
            val result = imageLoader.execute(ImageRequest.Builder(context).data(url).build())
            if (result !is SuccessResult) return@withContext null
            val bitmap = result.image.toBitmap().copy(Bitmap.Config.ARGB_8888, false)
            // Same recipe as the player (util/AlbumArtColor.kt): accent (Vibrant) for the buttons,
            // edge (chroma-weighted border) for the background so the widget merges with the art.
            // isDark is irrelevant here — the widget never uses the theme-aware surfaceAccent.
            val colors = bitmap.albumArtColors(isDark = true)

            // Downsample before handing the bitmap to RemoteViews. The file name is keyed on the art
            // URL so the path CHANGES per track — otherwise the widget's `remember(artFile)` decode
            // never re-runs (constant path) and shows the previous song's art while the colour updates.
            val scaled  = bitmap.scale(ART_SIZE, ART_SIZE)
            val dir     = File(context.cacheDir, "widget").apply { mkdirs() }
            val outFile = File(dir, "art_${url.hashCode().toUInt().toString(16)}.png")
            val tmpFile = File(dir, "art.tmp.png")
            tmpFile.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmpFile.renameTo(outFile)
            // Keep only the current art so the cache doesn't grow one file per track played.
            dir.listFiles()?.forEach { if (it != outFile) it.delete() }
            Extracted(outFile.absolutePath, colors.accent, colors.edge)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val SPOTIFY_GREEN = 0xFF1DB954.toInt()
        // Spotify's largest art is 640²; 512 keeps the large widget crisp while staying comfortably
        // within the RemoteViews bitmap budget (≈1 MB at ARGB_8888).
        private const val ART_SIZE = 512
    }
}

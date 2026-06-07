package com.crsmthw.lyra.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.datastore.preferences.core.Preferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.crsmthw.lyra.LyraApplication
import com.crsmthw.lyra.MainActivity
import com.crsmthw.lyra.R
import com.crsmthw.lyra.service.LyraForegroundService

class NowPlayingWidget : GlanceAppWidget() {

    // Exact (not Responsive) so the large layout can size its album art to the real widget size,
    // instead of a fixed dp tied to a responsive bucket.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as LyraApplication
        // Added while already playing: this widget's Glance state may be empty (the state collector
        // already consumed the current value before the widget existed). Kick a background refresh so
        // the snapshot gets written + the widget recomposes from it.
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val hasTrack = prefs[WidgetKeys.HAS_TRACK] ?: false
        if (!hasTrack && app.container.playerStateManager.state.value.currentTrack != null) {
            app.refreshWidget()
        }
        provideContent { WidgetContent(context) }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { RenderSnapshot(context, PREVIEW_SNAPSHOT, null) }
    }

    /** Reads the live Glance state; recomposes automatically when [NowPlayingWidgetUpdater] writes it. */
    @Composable
    private fun WidgetContent(context: Context) {
        val snapshot = currentState<Preferences>()?.toWidgetSnapshot() ?: WidgetSnapshot()
        val art = remember(snapshot.artFile) {
            snapshot.artFile?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        }
        RenderSnapshot(context, snapshot, art)
    }

    @Composable
    private fun RenderSnapshot(context: Context, snapshot: WidgetSnapshot, art: Bitmap?) {
        if (snapshot.hasTrack) {
            GlanceTheme(playingColors(snapshot)) {
                NowPlaying(context, snapshot, art)
            }
        } else {
            // Nothing playing → wallpaper / dynamic colours.
            GlanceTheme { EmptyState() }
        }
    }

    // ── Layouts ────────────────────────────────────────────────────────────────

    @Composable
    private fun NowPlaying(context: Context, snapshot: WidgetSnapshot, art: Bitmap?) {
        val size = LocalSize.current
        val root = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.background)
            .cornerRadius(20.dp)
            .clickable(actionStartActivity<MainActivity>())
        when {
            size.height >= LARGE.height -> {
                // Largest square that fits above the title + controls row (~168dp reserved for those).
                val artDim = minOf(size.width - 32.dp, size.height - 168.dp).coerceIn(120.dp, 320.dp)
                LargeLayout(context, snapshot, art, root, artDim)
            }
            size.width >= MEDIUM.width -> MediumLayout(context, snapshot, art, root)
            else                       -> CompactLayout(context, snapshot, art, root)
        }
    }

    @Composable
    private fun CompactLayout(context: Context, s: WidgetSnapshot, art: Bitmap?, root: GlanceModifier) {
        Row(
            modifier = root.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Art(art, 48.dp)
            Spacer(GlanceModifier.width(10.dp))
            Box(GlanceModifier.defaultWeight()) { TrackText(s) }
            Spacer(GlanceModifier.width(6.dp))
            PlayPause(context, s.isPlaying, 48.dp)
        }
    }

    @Composable
    private fun MediumLayout(context: Context, s: WidgetSnapshot, art: Bitmap?, root: GlanceModifier) {
        Row(
            modifier = root.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Art(art, 64.dp)
            Spacer(GlanceModifier.width(12.dp))
            Box(GlanceModifier.defaultWeight()) { TrackText(s) }
            Spacer(GlanceModifier.width(4.dp))
            IconControl(context, R.drawable.ic_widget_skip_previous, R.string.widget_cd_previous, LyraForegroundService.ACTION_PREV, 40.dp)
            PlayPause(context, s.isPlaying, 48.dp)
            IconControl(context, R.drawable.ic_widget_skip_next, R.string.widget_cd_next, LyraForegroundService.ACTION_NEXT, 40.dp)
        }
    }

    @Composable
    private fun LargeLayout(
        context: Context,
        s: WidgetSnapshot,
        art: Bitmap?,
        root: GlanceModifier,
        artDim: androidx.compose.ui.unit.Dp,
    ) {
        Column(
            modifier = root.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = GlanceModifier.defaultWeight().fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) { Art(art, artDim) }
            Spacer(GlanceModifier.height(12.dp))
            TrackText(s, center = true)
            Spacer(GlanceModifier.height(12.dp))
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToggleControl(context, R.drawable.ic_widget_shuffle,
                    R.string.widget_cd_shuffle, LyraForegroundService.ACTION_SHUFFLE, active = s.shuffle, sizeDp = 40.dp)
                Spacer(GlanceModifier.width(8.dp))
                IconControl(context, R.drawable.ic_widget_skip_previous, R.string.widget_cd_previous, LyraForegroundService.ACTION_PREV, 48.dp)
                Spacer(GlanceModifier.width(8.dp))
                PlayPause(context, s.isPlaying, 60.dp)
                Spacer(GlanceModifier.width(8.dp))
                IconControl(context, R.drawable.ic_widget_skip_next, R.string.widget_cd_next, LyraForegroundService.ACTION_NEXT, 48.dp)
                Spacer(GlanceModifier.width(8.dp))
                ToggleControl(context,
                    if (s.repeat == "track") R.drawable.ic_widget_repeat_one else R.drawable.ic_widget_repeat,
                    R.string.widget_cd_repeat, LyraForegroundService.ACTION_REPEAT, active = s.repeat != "off", sizeDp = 40.dp)
            }
        }
    }

    @Composable
    private fun EmptyState() {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(20.dp)
                .clickable(actionStartActivity<MainActivity>())
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_music_note),
                    contentDescription = null,
                    modifier = GlanceModifier.size(24.dp),
                )
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    text = glanceString(R.string.widget_nothing_playing),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
                )
            }
        }
    }

    // ── Pieces ──────────────────────────────────────────────────────────────────

    @Composable
    private fun TrackText(s: WidgetSnapshot, center: Boolean = false) {
        Column(horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start) {
            Text(
                text = s.title,
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onBackground,
                    fontSize = if (center) 16.sp else 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = s.artist,
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = if (center) 13.sp else 12.sp,
                ),
            )
        }
    }

    @Composable
    private fun Art(art: Bitmap?, sizeDp: androidx.compose.ui.unit.Dp) {
        val mod = GlanceModifier.size(sizeDp).cornerRadius(10.dp)
        if (art != null) {
            Image(
                provider = ImageProvider(art),
                contentDescription = null,
                modifier = mod,
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = mod.background(GlanceTheme.colors.primary),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_music_note),
                    contentDescription = null,
                    modifier = GlanceModifier.size(sizeDp.times(0.45f)),
                )
            }
        }
    }

    @Composable
    private fun PlayPause(context: Context, isPlaying: Boolean, sizeDp: androidx.compose.ui.unit.Dp) {
        CircleIconButton(
            imageProvider = ImageProvider(if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play),
            contentDescription = glanceString(if (isPlaying) R.string.widget_cd_pause else R.string.widget_cd_play),
            onClick = playerAction(context, LyraForegroundService.ACTION_PLAY_PAUSE),
            modifier = GlanceModifier.size(sizeDp),
            backgroundColor = GlanceTheme.colors.primary,
            contentColor = GlanceTheme.colors.onPrimary,
        )
    }

    @Composable
    private fun IconControl(context: Context, iconRes: Int, cdRes: Int, action: String, sizeDp: androidx.compose.ui.unit.Dp) {
        CircleIconButton(
            imageProvider = ImageProvider(iconRes),
            contentDescription = glanceString(cdRes),
            onClick = playerAction(context, action),
            modifier = GlanceModifier.size(sizeDp),
            backgroundColor = ColorProvider(Color.Transparent),
            contentColor = GlanceTheme.colors.onBackground,
        )
    }

    @Composable
    private fun ToggleControl(context: Context, iconRes: Int, cdRes: Int, action: String, active: Boolean, sizeDp: androidx.compose.ui.unit.Dp) {
        CircleIconButton(
            imageProvider = ImageProvider(iconRes),
            contentDescription = glanceString(cdRes),
            onClick = playerAction(context, action),
            modifier = GlanceModifier.size(sizeDp),
            backgroundColor = ColorProvider(Color.Transparent),
            contentColor = if (active) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
        )
    }

    companion object {
        // Thresholds for picking a layout from the actual widget size (SizeMode.Exact).
        private val MEDIUM = DpSize(220.dp, 96.dp)
        private val LARGE  = DpSize(260.dp, 200.dp)

        private val PREVIEW_SNAPSHOT = WidgetSnapshot(
            hasTrack = true,
            title = "Song title",
            artist = "Artist",
            isPlaying = true,
            accentArgb = NowPlayingWidgetUpdater.SPOTIFY_GREEN,
            dominantArgb = NowPlayingWidgetUpdater.SPOTIFY_GREEN,
        )
    }
}

// ── Colour scheme seeded from the album palette ────────────────────────────────

private fun playingColors(s: WidgetSnapshot) = run {
    val accent   = Color(s.accentArgb.takeIf { it != 0 } ?: NowPlayingWidgetUpdater.SPOTIFY_GREEN)
    val dominant = Color(s.dominantArgb.takeIf { it != 0 } ?: NowPlayingWidgetUpdater.SPOTIFY_GREEN)
    val bg       = if (s.amoled) Color.Black else lerp(Color(0xFF0E0E0E), dominant, 0.55f)
    val onBg     = if (bg.luminance() < 0.5f) Color.White else Color.Black
    val onAccent = if (accent.luminance() < 0.5f) Color.White else Color.Black
    ColorProviders(
        darkColorScheme(
            primary          = accent,
            onPrimary        = onAccent,
            background       = bg,
            onBackground     = onBg,
            surface          = bg,
            onSurface        = onBg,
            surfaceVariant   = bg,
            onSurfaceVariant = onBg.copy(alpha = 0.75f),
            secondary        = accent,
        )
    )
}

private fun playerAction(context: Context, action: String): Action =
    actionStartService(
        Intent(context, LyraForegroundService::class.java).setAction(action),
        /* isForegroundService = */ true,
    )

@Composable
private fun glanceString(resId: Int): String = LocalContext.current.getString(resId)

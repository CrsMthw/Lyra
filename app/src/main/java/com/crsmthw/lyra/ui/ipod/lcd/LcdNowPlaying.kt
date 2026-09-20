package com.crsmthw.lyra.ui.ipod.lcd

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.crsmthw.lyra.R
import com.crsmthw.lyra.ui.ipod.IPodColors
import com.crsmthw.lyra.ui.ipod.IPodFontFamily
import com.crsmthw.lyra.ui.ipod.nav.LcdNowPlaying
import com.crsmthw.lyra.util.toTimeString

// ── Tunables ────────────────────────────────────────────────────────────────

/** Art occupies this fraction of the content width. */
private const val ART_WIDTH_FRACTION = 0.42f
/** Gap between the art column and the text column, as a fraction of content width. */
private const val ART_TEXT_GAP_FRACTION = 0.05f
/** The upper region holding art + text takes this fraction of the content height. */
private const val UPPER_REGION_FRACTION = 0.78f
/** The bottom progress strip takes this fraction of the content height. */
private const val BOTTOM_STRIP_FRACTION = 0.20f
/** Perspective tilt of the album art (degrees around the Y axis). */
private const val ART_ROTATION_Y = -12f
/** Camera distance for the perspective tilt (multiplied by density). */
private const val ART_CAMERA_DISTANCE_FACTOR = 8f
/** Starting alpha of the reflection at its top edge. */
private const val REFLECTION_ALPHA = 0.35f
/** Reflection height as a fraction of the art height. */
private const val REFLECTION_HEIGHT_FRACTION = 0.35f
/** Progress bar track height as a fraction of the strip height. */
private const val PROGRESS_BAR_HEIGHT_FRACTION = 0.08f
/** Progress marker radius as a fraction of the strip height. */
private const val PROGRESS_MARKER_RADIUS_FRACTION = 0.04f
/** Scrub-mode marker radius (slightly larger). */
private const val SCRUB_MARKER_RADIUS_FRACTION = 0.06f
/** Font sizes as fractions of the content height. */
private const val TITLE_FONT_FRACTION = 0.050f
private const val ARTIST_FONT_FRACTION = 0.042f
private const val ALBUM_FONT_FRACTION = 0.038f
private const val POSITION_FONT_FRACTION = 0.034f
private const val TIME_FONT_FRACTION = 0.038f

// ── Entry point ─────────────────────────────────────────────────────────────

/**
 * The real Now Playing screen, matching the 6th-gen iPod Classic layout:
 * - Left: album art with perspective tilt and a fading reflection beneath it.
 * - Right: title (bold, marquee), artist (marquee), album (ellipsised), "N of M".
 * - Bottom strip: elapsed time, progress bar (two-tone blue fill + marker), remaining time.
 *
 * Progress updates at 1 Hz via [LcdNowPlaying.progressMs]; only the [NowPlayingProgressStrip]
 * recomposes on the tick (art and text read stable fields).
 */
@Composable
internal fun LcdNowPlayingContent(
    nowPlaying: LcdNowPlaying?,
    contentHeight: Dp,
) {
    if (nowPlaying == null) {
        LcdCentredMessage(
            text = stringResource(R.string.ipod_now_playing_empty),
            contentHeight = contentHeight,
        )
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val contentHeightPx = with(density) { contentHeight.toPx() }
        val contentWidthPx = constraints.maxWidth.toFloat()
        val contentWidthDp = with(density) { contentWidthPx.toDp() }

        val upperHeight = contentHeight * UPPER_REGION_FRACTION
        val stripHeight = contentHeight * BOTTOM_STRIP_FRACTION

        Column(Modifier.fillMaxSize()) {
            // Upper region: art on the left, text on the right.
            NowPlayingUpperRegion(
                nowPlaying = nowPlaying,
                contentWidthDp = contentWidthDp,
                contentHeightPx = contentHeightPx,
                regionHeight = upperHeight,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(upperHeight),
            )

            // Bottom strip: elapsed, progress bar, remaining.
            // Scoped to its own composable so 1 Hz ticks do not recompose the art/text.
            NowPlayingProgressStrip(
                progressMs = nowPlaying.scrubProgressMs ?: nowPlaying.progressMs,
                durationMs = nowPlaying.durationMs,
                isScrubbing = nowPlaying.scrubProgressMs != null,
                contentHeightPx = contentHeightPx,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(stripHeight),
            )
        }
    }
}

// ── Upper region (art + text) ───────────────────────────────────────────────

@Composable
private fun NowPlayingUpperRegion(
    nowPlaying: LcdNowPlaying,
    contentWidthDp: Dp,
    contentHeightPx: Float,
    regionHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val artWidth = contentWidthDp * ART_WIDTH_FRACTION
    val gapWidth = contentWidthDp * ART_TEXT_GAP_FRACTION
    val textWidth = contentWidthDp - artWidth - gapWidth

    val titleFontSize = with(density) { (contentHeightPx * TITLE_FONT_FRACTION).toSp() }
    val artistFontSize = with(density) { (contentHeightPx * ARTIST_FONT_FRACTION).toSp() }
    val albumFontSize = with(density) { (contentHeightPx * ALBUM_FONT_FRACTION).toSp() }
    val positionFontSize = with(density) { (contentHeightPx * POSITION_FONT_FRACTION).toSp() }

    Row(
        modifier = modifier.padding(start = contentWidthDp * 0.04f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: album art with tilt + reflection.
        NowPlayingArt(
            artUrl = nowPlaying.artUrl,
            modifier = Modifier
                .width(artWidth)
                .height(regionHeight),
        )

        Spacer(Modifier.width(gapWidth))

        // Right: title / artist / album / position.
        Column(
            modifier = Modifier
                .width(textWidth)
                .padding(end = contentWidthDp * 0.04f),
        ) {
            // Title (bold, marquee when overflowing).
            Text(
                text = nowPlaying.title,
                fontFamily = IPodFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = titleFontSize,
                color = IPodColors.LcdText,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(iterations = Int.MAX_VALUE),
            )

            Spacer(Modifier.height(with(density) { (contentHeightPx * 0.01f).toDp() }))

            // Artist (marquee when overflowing).
            Text(
                text = nowPlaying.artist,
                fontFamily = IPodFontFamily,
                fontSize = artistFontSize,
                color = IPodColors.LcdTextSecondary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(iterations = Int.MAX_VALUE),
            )

            Spacer(Modifier.height(with(density) { (contentHeightPx * 0.005f).toDp() }))

            // Album (ellipsised, no marquee).
            Text(
                text = nowPlaying.album,
                fontFamily = IPodFontFamily,
                fontSize = albumFontSize,
                color = IPodColors.LcdTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            // "N of M" — only when both are known (i.e. the user picked a song from a list).
            if (nowPlaying.positionInList != null && nowPlaying.listSize != null) {
                Spacer(Modifier.height(with(density) { (contentHeightPx * 0.01f).toDp() }))
                Text(
                    text = stringResource(
                        R.string.ipod_now_playing_position,
                        nowPlaying.positionInList,
                        nowPlaying.listSize,
                    ),
                    fontFamily = IPodFontFamily,
                    fontSize = positionFontSize,
                    color = IPodColors.LcdTextSecondary,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ── Album art with tilt + reflection ────────────────────────────────────────

@Composable
private fun NowPlayingArt(
    artUrl: String,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val context = LocalContext.current

    // Remember the ImageRequest keyed on artUrl so 1 Hz ticks do not rebuild it.
    val imageRequest = remember(artUrl) {
        ImageRequest.Builder(context)
            .data(artUrl.ifBlank { null })
            .crossfade(200)
            .build()
    }

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val availableWidth = maxWidth
        val availableHeight = maxHeight
        // Square art, sized to fit the available space with room for the reflection.
        val artSide = minOf(availableWidth, availableHeight * (1f / (1f + REFLECTION_HEIGHT_FRACTION)))
        val reflectionHeight = artSide * REFLECTION_HEIGHT_FRACTION

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // The main art image with perspective tilt.
            Box(
                modifier = Modifier
                    .size(artSide)
                    .graphicsLayer {
                        rotationY = ART_ROTATION_Y
                        cameraDistance = ART_CAMERA_DISTANCE_FACTOR * density.density
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (artUrl.isBlank()) {
                    ArtPlaceholder(Modifier.fillMaxSize())
                } else {
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Reflection: the same image flipped vertically, alpha fading from top to transparent.
            Box(
                modifier = Modifier
                    .size(width = artSide, height = reflectionHeight)
                    .graphicsLayer {
                        rotationY = ART_ROTATION_Y
                        cameraDistance = ART_CAMERA_DISTANCE_FACTOR * density.density
                        scaleY = -1f
                        alpha = REFLECTION_ALPHA
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Black, Color.Transparent),
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (artUrl.isBlank()) {
                    ArtPlaceholder(Modifier.fillMaxSize())
                } else {
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * Placeholder when no art is available: a grey square with a music note glyph.
 */
@Composable
private fun ArtPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(IPodColors.ArtPlaceholder),
        contentAlignment = Alignment.Center,
    ) {
        // A simple music note as unicode glyph, sized relative to the placeholder.
        Text(
            text = "♫", // beamed eighth notes
            fontFamily = IPodFontFamily,
            fontSize = with(LocalDensity.current) { 24.toDp().toSp() },
            color = IPodColors.ArtPlaceholderIcon,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Progress strip ──────────────────────────────────────────────────────────

/**
 * The bottom strip: elapsed time (left), remaining time (right), progress bar between them.
 * This is its own composable so the 1 Hz tick that changes [progressMs] does not recompose the
 * art and text above it.
 */
@Composable
private fun NowPlayingProgressStrip(
    progressMs: Long,
    durationMs: Long,
    isScrubbing: Boolean,
    contentHeightPx: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val timeFontSize = with(density) { (contentHeightPx * TIME_FONT_FRACTION).toSp() }
    val stripHeightPx = contentHeightPx * BOTTOM_STRIP_FRACTION
    val barHeightPx = stripHeightPx * PROGRESS_BAR_HEIGHT_FRACTION
    val markerRadiusPx = stripHeightPx *
        if (isScrubbing) SCRUB_MARKER_RADIUS_FRACTION else PROGRESS_MARKER_RADIUS_FRACTION

    val elapsedText = progressMs.coerceAtLeast(0L).toTimeString()
    val remainingMs = (durationMs - progressMs).coerceAtLeast(0L)
    val remainingText = stringResource(R.string.ipod_now_playing_remaining, remainingMs.toTimeString())

    val fraction = if (durationMs > 0) {
        (progressMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    // Faint gradient background for the strip.
    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        IPodColors.LcdStatusBottom.copy(alpha = 0.3f),
                        IPodColors.LcdStatusTop.copy(alpha = 0.15f),
                    ),
                ),
            )
            .padding(horizontal = with(density) { (contentHeightPx * 0.04f).toDp() }),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val barWidth = maxWidth - with(density) { (contentHeightPx * 0.20f).toDp() }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Elapsed time.
                Text(
                    text = elapsedText,
                    fontFamily = IPodFontFamily,
                    fontSize = timeFontSize,
                    color = IPodColors.LcdText,
                    maxLines = 1,
                )

                Spacer(Modifier.width(with(density) { (contentHeightPx * 0.02f).toDp() }))

                // Progress bar (custom drawn for the two-tone fill + marker).
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(with(density) { (barHeightPx * 4f).toDp() })
                        .drawBehind {
                            drawProgressBar(
                                fraction = fraction,
                                barHeight = barHeightPx,
                                markerRadius = markerRadiusPx,
                                isScrubbing = isScrubbing,
                            )
                        },
                )

                Spacer(Modifier.width(with(density) { (contentHeightPx * 0.02f).toDp() }))

                // Remaining time.
                Text(
                    text = remainingText,
                    fontFamily = IPodFontFamily,
                    fontSize = timeFontSize,
                    color = IPodColors.LcdText,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Draws the Classic's two-tone blue progress bar with a small marker at the playhead.
 */
private fun DrawScope.drawProgressBar(
    fraction: Float,
    barHeight: Float,
    markerRadius: Float,
    isScrubbing: Boolean,
) {
    val barTop = (size.height - barHeight) / 2f
    val cornerRadius = barHeight / 2f

    // Track background.
    drawRoundRect(
        color = IPodColors.ProgressTrack,
        topLeft = Offset(0f, barTop),
        size = Size(size.width, barHeight),
        cornerRadius = CornerRadius(cornerRadius),
    )

    // Two-tone blue fill.
    val fillWidth = size.width * fraction
    if (fillWidth > 0f) {
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(IPodColors.ProgressTop, IPodColors.ProgressBottom),
                startY = barTop,
                endY = barTop + barHeight,
            ),
            topLeft = Offset(0f, barTop),
            size = Size(fillWidth.coerceAtMost(size.width), barHeight),
            cornerRadius = CornerRadius(cornerRadius),
        )
    }

    // Marker at the playhead.
    val markerCenterX = fillWidth.coerceIn(markerRadius, size.width - markerRadius)
    val markerCenterY = size.height / 2f
    val markerColor = if (isScrubbing) IPodColors.LcdText else IPodColors.ProgressBottom
    drawCircle(
        color = markerColor,
        radius = markerRadius,
        center = Offset(markerCenterX, markerCenterY),
    )
}

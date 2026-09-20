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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
private const val ART_WIDTH_FRACTION = 0.44f
/** Gap between the art column and the text column, as a fraction of content width. */
private const val ART_TEXT_GAP_FRACTION = 0.04f
/** The upper region holding art + text takes this fraction of the content height. */
/** The bottom progress strip takes this fraction of the content height. */
private const val BOTTOM_STRIP_FRACTION = 0.20f
/** Perspective tilt of the album art (degrees around the Y axis). */
/**
 * Positive = the RIGHT edge is the near one — the Classic's art turns toward the text. (The first
 * build used −12°, which put the LEFT edge nearer: the mirror image of the reference photo.)
 */
private const val ART_ROTATION_Y = 20f

/** The art's top edge, as a fraction of the content height. */
private const val ART_TOP_FRACTION = 0.10f

/** The art may take at most this fraction of the content height (the reference's is ~0.55). */
private const val ART_MAX_HEIGHT_FRACTION = 0.62f

/** The title block starts this far below the art's top edge, as a fraction of the art side. */
private const val TEXT_TOP_OFFSET_FRACTION = 0.14f
/** Camera distance for the perspective tilt (multiplied by density). */
/**
 * Compose's cameraDistance is in the RenderNode's own units (View.setCameraDistance divides its
 * pixels by the dpi; the default is 8, NOT 8 × density). The first build multiplied by density and
 * so viewed the art from ~3× the default distance, which flattened the rotation into a faint skew.
 * A little closer than the default gives the Classic's visibly receding far edge.
 */
private const val ART_CAMERA_DISTANCE = 7f
/** Starting alpha of the reflection at its top edge. */
private const val REFLECTION_ALPHA = 0.6f
/** Reflection height as a fraction of the art height. */
private const val REFLECTION_HEIGHT_FRACTION = 0.42f
/** Progress bar track height as a fraction of the strip height. */
/** The Classic's bar is a real channel, not a hairline: ~22 % of the strip (~4 % of the panel). */
private const val PROGRESS_BAR_HEIGHT_FRACTION = 0.30f
/** Progress marker radius as a fraction of the strip height. */
private const val PROGRESS_MARKER_RADIUS_FRACTION = 0.04f
/** Scrub-mode marker radius (slightly larger). */
private const val SCRUB_MARKER_RADIUS_FRACTION = 0.06f
/** Font sizes as fractions of the content height. */
private const val TITLE_FONT_FRACTION = 0.050f
private const val ARTIST_FONT_FRACTION = 0.042f
private const val ALBUM_FONT_FRACTION = 0.038f
private const val POSITION_FONT_FRACTION = 0.034f
private const val TIME_FONT_FRACTION = 0.042f
/** Art padding from left edge of the content, as a fraction of content width. */
private const val ART_START_PAD_FRACTION = 0.05f
/** Text padding from right edge, as a fraction of content width. */
private const val TEXT_END_PAD_FRACTION = 0.04f
/** Music note glyph size as a fraction of the art side. */
private const val NOTE_GLYPH_FRACTION = 0.35f

// ── Entry point ─────────────────────────────────────────────────────────────

/**
 * The real Now Playing screen, matching the 6th-gen iPod Classic layout:
 * - Left: album art with perspective tilt and a fading reflection beneath it.
 * - Right: title (bold, marquee), artist (marquee), album (ellipsised), "N of M".
 * - Bottom strip: elapsed time, progress bar (two-tone blue fill + marker), remaining time.
 *
 * The upper region receives only scalar fields (title, artist, album, artUrl, position) so that
 * the 1 Hz progress tick that changes [LcdNowPlaying.progressMs] does not recompose the art/text
 * -- only the [NowPlayingProgressStrip] recomposes on the tick.
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
        val contentWidthDp = with(density) { constraints.maxWidth.toDp() }

        val stripHeight = contentHeight * BOTTOM_STRIP_FRACTION

        Box(Modifier.fillMaxSize()) {
            // Art on the left, text on the right — the art column runs the whole content height so
            // the reflection can reach down toward the bar, as on the Classic. Stable fields only:
            // the 1 Hz tick does not recompose this region.
            NowPlayingUpperRegion(
                title = nowPlaying.title,
                artist = nowPlaying.artist,
                album = nowPlaying.album,
                artUrl = nowPlaying.artUrl,
                positionInList = nowPlaying.positionInList,
                listSize = nowPlaying.listSize,
                contentWidthDp = contentWidthDp,
                contentHeightPx = contentHeightPx,
                contentHeight = contentHeight,
                modifier = Modifier.fillMaxSize(),
            )

            // Bottom strip: elapsed, glass bar, remaining — on the plain LCD white.
            NowPlayingProgressStrip(
                progressMs = nowPlaying.scrubProgressMs ?: nowPlaying.progressMs,
                durationMs = nowPlaying.durationMs,
                isScrubbing = nowPlaying.scrubProgressMs != null,
                contentHeightPx = contentHeightPx,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(stripHeight),
            )
        }
    }
}

// ── Upper region (art + text) ───────────────────────────────────────────────

@Composable
private fun NowPlayingUpperRegion(
    title: String,
    artist: String,
    album: String,
    artUrl: String,
    positionInList: Int?,
    listSize: Int?,
    contentWidthDp: Dp,
    contentHeightPx: Float,
    contentHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val artWidth = contentWidthDp * ART_WIDTH_FRACTION
    val artSide = minOf(artWidth, contentHeight * ART_MAX_HEIGHT_FRACTION)
    val artTop = contentHeight * ART_TOP_FRACTION
    val gapWidth = contentWidthDp * ART_TEXT_GAP_FRACTION
    val textWidth = contentWidthDp - artWidth - gapWidth -
        contentWidthDp * ART_START_PAD_FRACTION - contentWidthDp * TEXT_END_PAD_FRACTION

    val titleFontSize = with(density) { (contentHeightPx * TITLE_FONT_FRACTION).toSp() }
    val artistFontSize = with(density) { (contentHeightPx * ARTIST_FONT_FRACTION).toSp() }
    val albumFontSize = with(density) { (contentHeightPx * ALBUM_FONT_FRACTION).toSp() }
    val positionFontSize = with(density) { (contentHeightPx * POSITION_FONT_FRACTION).toSp() }

    Row(
        modifier = modifier.padding(
            start = contentWidthDp * ART_START_PAD_FRACTION,
            end = contentWidthDp * TEXT_END_PAD_FRACTION,
        ),
        verticalAlignment = Alignment.Top,
    ) {
        // Left: album art with tilt + reflection, its top edge at ART_TOP_FRACTION.
        NowPlayingArt(
            artUrl = artUrl,
            artSide = artSide,
            modifier = Modifier
                .width(artWidth)
                .padding(top = artTop),
        )

        Spacer(Modifier.width(gapWidth))

        // Right: title / artist / album / position, starting a little below the art's top edge
        // (the Classic's title sits ~12 % of the art height down from it).
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = artTop + artSide * TEXT_TOP_OFFSET_FRACTION),
        ) {
            // Title (bold, marquee when overflowing).
            Text(
                text = title,
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
                text = artist,
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
                text = album,
                fontFamily = IPodFontFamily,
                fontSize = albumFontSize,
                color = IPodColors.LcdTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            // "N of M" — only when both are known (i.e. the user picked a song from a list).
            if (positionInList != null && listSize != null) {
                Spacer(Modifier.height(with(density) { (contentHeightPx * 0.01f).toDp() }))
                Text(
                    text = stringResource(
                        R.string.ipod_now_playing_position,
                        positionInList,
                        listSize,
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
    artSide: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Remember the ImageRequest keyed on artUrl so 1 Hz ticks do not rebuild it.
    val imageRequest = remember(artUrl) {
        ImageRequest.Builder(context)
            .data(artUrl.ifBlank { null })
            .crossfade(200)
            .build()
    }

    val reflectionHeight = artSide * REFLECTION_HEIGHT_FRACTION
    val totalHeight = artSide + reflectionHeight
    // The rotation axis runs through the ART's centre: art and reflection are one plane, so the
    // reflection's edges continue the art's (two separately rotated layers met at a visible kink).
    val pivotY = (artSide / 2) / totalHeight

    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(artSide)
                .height(totalHeight)
                .graphicsLayer {
                    rotationY = ART_ROTATION_Y
                    cameraDistance = ART_CAMERA_DISTANCE
                    transformOrigin = TransformOrigin(0.5f, pivotY)
                },
        ) {
            // The art. Placeholder always underneath; the AsyncImage lands on top with a crossfade,
            // covering loading, error and blank-url cases.
            Box(
                modifier = Modifier.size(artSide),
                contentAlignment = Alignment.Center,
            ) {
                ArtPlaceholder(Modifier.fillMaxSize(), artSide)
                if (artUrl.isNotBlank()) {
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Reflection: the full square, flipped, clipped to the reflection height, so only the
            // art's bottom edge continues across the seam. scaleY = -1f makes pre-flip "top" the
            // post-flip "bottom", so the DstIn gradient runs Transparent at the pre-flip top to
            // Black at the pre-flip bottom (= the seam after the flip): opaque at the seam, gone at
            // the far end.
            Box(
                modifier = Modifier
                    .width(artSide)
                    .height(reflectionHeight)
                    .clipToBounds(),
            ) {
                Box(
                    modifier = Modifier
                        .size(artSide) // full square, overflows the clip parent
                        .graphicsLayer {
                            scaleY = -1f
                            alpha = REFLECTION_ALPHA
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    (1f - REFLECTION_HEIGHT_FRACTION) to Color.Transparent,
                                    1f to Color.Black,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    ArtPlaceholder(Modifier.fillMaxSize(), artSide)
                    if (artUrl.isNotBlank()) {
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
}

@Composable
private fun ArtPlaceholder(modifier: Modifier, artSide: Dp) {
    val density = LocalDensity.current
    val glyphSizePx = with(density) { (artSide * NOTE_GLYPH_FRACTION).toPx() }

    Box(
        modifier = modifier
            .background(IPodColors.ArtPlaceholder)
            .drawBehind {
                drawMusicNote(
                    center = Offset(size.width / 2f, size.height / 2f),
                    noteSize = glyphSizePx,
                    color = IPodColors.ArtPlaceholderIcon,
                )
            },
    )
}

/**
 * Draws a simple single eighth-note: a filled oval head, a vertical stem, and a flag.
 */
private fun DrawScope.drawMusicNote(center: Offset, noteSize: Float, color: Color) {
    val headWidth = noteSize * 0.40f
    val headHeight = noteSize * 0.28f
    val stemHeight = noteSize * 0.70f
    val stemWidth = noteSize * 0.06f
    val flagWidth = noteSize * 0.22f
    val flagHeight = noteSize * 0.35f

    // Head: an oval at the bottom-left.
    val headCx = center.x - noteSize * 0.05f
    val headCy = center.y + stemHeight * 0.35f
    drawOval(
        color = color,
        topLeft = Offset(headCx - headWidth / 2f, headCy - headHeight / 2f),
        size = Size(headWidth, headHeight),
    )

    // Stem: rises from the right edge of the head.
    val stemX = headCx + headWidth / 2f - stemWidth
    val stemTop = headCy - stemHeight
    drawRect(
        color = color,
        topLeft = Offset(stemX, stemTop),
        size = Size(stemWidth, stemHeight),
    )

    // Flag: a curved stroke from the top of the stem.
    val flagPath = Path().apply {
        moveTo(stemX + stemWidth, stemTop)
        cubicTo(
            stemX + stemWidth + flagWidth, stemTop + flagHeight * 0.2f,
            stemX + stemWidth + flagWidth * 0.8f, stemTop + flagHeight * 0.6f,
            stemX + stemWidth, stemTop + flagHeight,
        )
        lineTo(stemX + stemWidth, stemTop + flagHeight - stemWidth)
        cubicTo(
            stemX + stemWidth + flagWidth * 0.6f, stemTop + flagHeight * 0.5f,
            stemX + stemWidth + flagWidth * 0.8f, stemTop + flagHeight * 0.25f,
            stemX + stemWidth, stemTop + stemWidth,
        )
        close()
    }
    drawPath(flagPath, color, style = Fill)
}

// ── Progress strip ──────────────────────────────────────────────────────────

/**
 * The bottom strip: elapsed time (left), remaining time (right), progress bar between them.
 * This is its own composable taking only the three progress-related scalars so the 1 Hz tick
 * does not recompose the art and text above it.
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
            .padding(horizontal = with(density) { (contentHeightPx * 0.04f).toDp() }),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
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

/**
 * The Classic's progress bar: an inset channel (darker at the top, white at the bottom, hairline
 * edge) filled with "aqua" glass — pale at the top, deep blue through the middle, a lighter band at
 * the bottom, and a white specular sheen over the upper half. No playhead while playing; a small
 * dark diamond appears only while the wheel scrubs.
 */
private fun DrawScope.drawProgressBar(
    fraction: Float,
    barHeight: Float,
    markerRadius: Float,
    isScrubbing: Boolean,
) {
    val barTop = (size.height - barHeight) / 2f
    val barBottom = barTop + barHeight
    val corner = CornerRadius(barHeight * 0.18f)
    val track = Rect(0f, barTop, size.width, barBottom)

    // Channel.
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(IPodColors.ProgressTrackTop, IPodColors.ProgressTrackBottom),
            startY = barTop,
            endY = barBottom,
        ),
        topLeft = track.topLeft,
        size = track.size,
        cornerRadius = corner,
    )
    drawRoundRect(
        color = IPodColors.ProgressTrackEdge,
        topLeft = track.topLeft,
        size = track.size,
        cornerRadius = corner,
        style = Stroke(width = 1f),
    )

    // Glass fill, clipped to the channel's shape.
    val fillWidth = (size.width * fraction).coerceIn(0f, size.width)
    if (fillWidth > 1f) {
        val fillClip = Path().apply {
            addRoundRect(RoundRect(Rect(0f, barTop, fillWidth, barBottom), corner))
        }
        clipPath(fillClip) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to IPodColors.ProgressGlassTop,
                    0.45f to IPodColors.ProgressGlassMid,
                    0.5f to IPodColors.ProgressGlassLow,
                    1f to IPodColors.ProgressGlassBottom,
                    startY = barTop,
                    endY = barBottom,
                ),
                topLeft = Offset(0f, barTop),
                size = Size(fillWidth, barHeight),
            )
            // Specular sheen over the upper half.
            drawRect(
                brush = Brush.verticalGradient(
                    0f to IPodColors.HighlightText.copy(alpha = 0.6f),
                    1f to IPodColors.HighlightText.copy(alpha = 0f),
                    startY = barTop,
                    endY = barTop + barHeight * 0.48f,
                ),
                topLeft = Offset(0f, barTop),
                size = Size(fillWidth, barHeight * 0.48f),
            )
        }
    }

    // Reflection under the bar: the Classic's bar sits on a glossy surface, so a faint flipped
    // echo fades away beneath it — grey under the channel, blue under the filled part.
    val reflectionTop = barBottom + 1f
    val reflectionHeight = barHeight * 0.7f
    drawRect(
        brush = Brush.verticalGradient(
            0f to IPodColors.ProgressTrackTop.copy(alpha = 0.22f),
            1f to IPodColors.ProgressTrackTop.copy(alpha = 0f),
            startY = reflectionTop,
            endY = reflectionTop + reflectionHeight,
        ),
        topLeft = Offset(0f, reflectionTop),
        size = Size(size.width, reflectionHeight),
    )
    if (fillWidth > 1f) {
        drawRect(
            brush = Brush.verticalGradient(
                0f to IPodColors.ProgressGlassBottom.copy(alpha = 0.38f),
                1f to IPodColors.ProgressGlassBottom.copy(alpha = 0f),
                startY = reflectionTop,
                endY = reflectionTop + reflectionHeight,
            ),
            topLeft = Offset(0f, reflectionTop),
            size = Size(fillWidth, reflectionHeight),
        )
    }

    // Scrub diamond only.
    if (isScrubbing) {
        val cx = fillWidth.coerceIn(markerRadius, size.width - markerRadius)
        val cy = size.height / 2f
        val diamond = Path().apply {
            moveTo(cx, cy - markerRadius)
            lineTo(cx + markerRadius, cy)
            lineTo(cx, cy + markerRadius)
            lineTo(cx - markerRadius, cy)
            close()
        }
        drawPath(diamond, IPodColors.LcdText, style = Fill)
    }
}

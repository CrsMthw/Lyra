package com.crsmthw.lyra.ui.ipod.lcd

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import com.crsmthw.lyra.ui.ipod.nav.NowPlayingMode
import com.crsmthw.lyra.ui.ipod.nav.LcdRepeat
import com.crsmthw.lyra.ui.ipod.IPodDimens
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.AnimatedContent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.ui.graphics.drawscope.withTransform
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
/** Small: the far edge already recedes toward the text, and the Classic sets the text right beside the art. */
private const val ART_TEXT_GAP_FRACTION = 0.025f
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
private const val ART_TOP_FRACTION = 0.09f

/** The art may take at most this fraction of the content height (the reference's is ~0.55). */
/* Art top 9 % + art 58 % + reflection 22 % of 58 % ≈ 80 % = the strip's top: the reflection ends there. */
private const val ART_MAX_HEIGHT_FRACTION = 0.58f

/** The title block starts this far below the art's top edge, as a fraction of the art side. */
private const val TEXT_TOP_OFFSET_FRACTION = 0.12f
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
/** The Classic's reflection is short and dies fast — it must end above the progress strip. */
private const val REFLECTION_HEIGHT_FRACTION = 0.22f
/** Progress bar track height as a fraction of the strip height. */
/** The Classic's bar is a real channel, not a hairline: ~22 % of the strip (~4 % of the panel). */
private const val PROGRESS_BAR_HEIGHT_FRACTION = 0.30f
/** Progress marker radius as a fraction of the strip height. */
private const val PROGRESS_MARKER_RADIUS_FRACTION = 0.04f
/** Scrub-mode marker radius (slightly larger). */
private const val SCRUB_MARKER_RADIUS_FRACTION = 0.06f
/** Font sizes as fractions of the content height. */
private const val TITLE_FONT_FRACTION = 0.062f
private const val ARTIST_FONT_FRACTION = 0.050f
private const val ALBUM_FONT_FRACTION = 0.050f
private const val POSITION_FONT_FRACTION = 0.050f
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

            // Bottom strip: whatever the wheel drives right now — scrubber, media volume, shuffle
            // or repeat. SELECT cycles them; the old bar slides out to the left as the new one
            // comes in from the right.
            AnimatedContent(
                targetState = nowPlaying.mode,
                transitionSpec = {
                    slideInHorizontally(tween(IPodDimens.LcdSlideMillis)) { it } togetherWith
                        slideOutHorizontally(tween(IPodDimens.LcdSlideMillis)) { -it }
                },
                label = "now_playing_bar",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(stripHeight),
            ) { mode ->
                when (mode) {
                    NowPlayingMode.SCRUB -> NowPlayingProgressStrip(
                        progressMs = nowPlaying.scrubProgressMs ?: nowPlaying.progressMs,
                        durationMs = nowPlaying.durationMs,
                        isScrubbing = nowPlaying.scrubProgressMs != null,
                        contentHeightPx = contentHeightPx,
                        modifier = Modifier.fillMaxSize(),
                    )
                    NowPlayingMode.VOLUME -> NowPlayingVolumeStrip(
                        volumePercent = nowPlaying.volumePercent,
                        contentHeightPx = contentHeightPx,
                        modifier = Modifier.fillMaxSize(),
                    )
                    NowPlayingMode.SHUFFLE -> NowPlayingOptionStrip(
                        glyph = OptionGlyph.SHUFFLE,
                        options = listOf(R.string.ipod_value_off, R.string.ipod_value_on),
                        selectedIndex = if (nowPlaying.shuffleEnabled) 1 else 0,
                        contentHeightPx = contentHeightPx,
                        modifier = Modifier.fillMaxSize(),
                    )
                    NowPlayingMode.REPEAT -> NowPlayingOptionStrip(
                        glyph = OptionGlyph.REPEAT,
                        options = listOf(R.string.ipod_value_off, R.string.ipod_value_all, R.string.ipod_value_one),
                        selectedIndex = nowPlaying.repeat.ordinal,
                        contentHeightPx = contentHeightPx,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
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
                .width(artSide)   // exactly the art: no slack column pushing the text away
                .padding(top = artTop),
        )

        Spacer(Modifier.width(gapWidth))

        // Right: title / artist / album / position, starting a little below the art's top edge
        // (the Classic's title sits ~12 % of the art height down from it).
        // Artist / album / position are dark grey on the Classic, not light; lines sit a clear
        // half-line apart.
        val secondary = IPodColors.LcdText.copy(alpha = 0.72f)
        val lineGap = artSide * 0.055f
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

            Spacer(Modifier.height(lineGap))

            // Artist (marquee when overflowing).
            Text(
                text = artist,
                fontFamily = IPodFontFamily,
                fontSize = artistFontSize,
                color = secondary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(iterations = Int.MAX_VALUE),
            )

            Spacer(Modifier.height(lineGap))

            // Album (ellipsised, no marquee).
            Text(
                text = album,
                fontFamily = IPodFontFamily,
                fontSize = albumFontSize,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            // "N of M" — only when both are known (i.e. the user picked a song from a list).
            if (positionInList != null && listSize != null) {
                Spacer(Modifier.height(lineGap))
                Text(
                    text = stringResource(
                        R.string.ipod_now_playing_position,
                        positionInList,
                        listSize,
                    ),
                    fontFamily = IPodFontFamily,
                    fontSize = positionFontSize,
                    color = secondary,
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
                        // The full square must overflow the clip box with its TOP at the box's top.
                        // Neither `size` nor `requiredSize` does that: a child that violates its
                        // constraints is reported at the coerced size and its content CENTRED in the
                        // slot, which showed the art's middle band. wrapContentSize(TopStart,
                        // unbounded) measures the square unconstrained and pins it top-start.
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .size(artSide)
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
                                    (1f - REFLECTION_HEIGHT_FRACTION * 0.5f) to Color.Black.copy(alpha = 0.28f),
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
internal fun ArtPlaceholder(modifier: Modifier, artSide: Dp) {
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
internal fun DrawScope.drawMusicNote(center: Offset, noteSize: Float, color: Color) {
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
                fontWeight = FontWeight.Bold,
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
                fontWeight = FontWeight.Bold,
                fontSize = timeFontSize,
                color = IPodColors.LcdText,
                maxLines = 1,
            )
        }
    }
}

// ── Volume / option bars (the other three things SELECT cycles to) ─────────

/** The media-volume bar: the same glass channel between a quiet and a loud speaker glyph. */
@Composable
private fun NowPlayingVolumeStrip(
    volumePercent: Int,
    contentHeightPx: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val stripHeightPx = contentHeightPx * BOTTOM_STRIP_FRACTION
    val barHeightPx = stripHeightPx * PROGRESS_BAR_HEIGHT_FRACTION
    val glyphSize = with(density) { (stripHeightPx * 0.42f).toDp() }
    val fraction = (volumePercent / 100f).coerceIn(0f, 1f)

    Box(modifier.padding(horizontal = with(density) { (contentHeightPx * 0.04f).toDp() })) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(glyphSize)) { drawSpeaker(loud = false) }
            Spacer(Modifier.width(with(density) { (contentHeightPx * 0.02f).toDp() }))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(with(density) { (barHeightPx * 4f).toDp() })
                    .drawBehind {
                        drawProgressBar(fraction = fraction, barHeight = barHeightPx, markerRadius = 0f, isScrubbing = false)
                    },
            )
            Spacer(Modifier.width(with(density) { (contentHeightPx * 0.02f).toDp() }))
            Canvas(Modifier.size(glyphSize)) { drawSpeaker(loud = true) }
        }
    }
}

/** A small speaker glyph; [loud] adds two sound arcs. */
private fun DrawScope.drawSpeaker(loud: Boolean) {
    val w = size.width
    val h = size.height
    val color = IPodColors.LcdText
    val body = Path().apply {
        moveTo(w * 0.05f, h * 0.36f)
        lineTo(w * 0.30f, h * 0.36f)
        lineTo(w * 0.55f, h * 0.12f)
        lineTo(w * 0.55f, h * 0.88f)
        lineTo(w * 0.30f, h * 0.64f)
        lineTo(w * 0.05f, h * 0.64f)
        close()
    }
    drawPath(body, color)
    if (loud) {
        for (r in floatArrayOf(0.22f, 0.36f)) {
            drawArc(
                color = color,
                startAngle = -40f,
                sweepAngle = 80f,
                useCenter = false,
                topLeft = Offset(w * 0.55f - w * r, h * 0.5f - h * r),
                size = Size(2f * w * r, 2f * h * r),
                style = Stroke(width = maxOf(1f, h * 0.07f)),
            )
        }
    }
}

/** Which glyph leads the segmented switch on the shuffle / repeat bar. */
private enum class OptionGlyph { SHUFFLE, REPEAT }

/**
 * The shuffle / repeat bar as the Classic draws it: the mode's glyph, then a segmented switch —
 * the SELECTED segment light with blue bold text, the others darker grey with black text — centred.
 */
@Composable
private fun NowPlayingOptionStrip(
    glyph: OptionGlyph,
    options: List<Int>,
    selectedIndex: Int,
    contentHeightPx: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val stripHeightPx = contentHeightPx * BOTTOM_STRIP_FRACTION
    val fontSize = with(density) { (contentHeightPx * TIME_FONT_FRACTION * 1.15f).toSp() }
    val segmentHeight = with(density) { (stripHeightPx * 0.5f).toDp() }
    val segmentMinWidth = with(density) { (stripHeightPx * 1.1f).toDp() }
    val glyphSize = with(density) { (stripHeightPx * 0.42f).toDp() }
    val corner = RoundedCornerShape(with(density) { (stripHeightPx * 0.06f).toDp() })

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(glyphSize)) {
            when (glyph) {
                OptionGlyph.SHUFFLE -> drawShuffleGlyph()
                OptionGlyph.REPEAT -> drawRepeatGlyph()
            }
        }
        Spacer(Modifier.width(with(density) { (stripHeightPx * 0.4f).toDp() }))
        Row(
            modifier = Modifier
                .height(segmentHeight)
                .clip(corner)
                .border(1.dp, IPodColors.ProgressTrackEdge, corner),
        ) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(min = segmentMinWidth)
                        .background(
                            Brush.verticalGradient(
                                colors = if (selected) {
                                    listOf(IPodColors.LcdStatusGlossTop, IPodColors.LcdStatusGlossMid)
                                } else {
                                    listOf(IPodColors.LcdStatusGlossLow, IPodColors.LcdStatusGlossMid)
                                },
                            ),
                        )
                        .padding(horizontal = with(density) { (stripHeightPx * 0.3f).toDp() }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(label),
                        fontFamily = IPodFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = fontSize,
                        color = if (selected) IPodColors.ProgressGlassLow else IPodColors.LcdText,
                        maxLines = 1,
                    )
                }
                if (index < options.lastIndex) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(IPodColors.ProgressTrackEdge),
                    )
                }
            }
        }
    }
}

/** Two crossing arrows. */
private fun DrawScope.drawShuffleGlyph() {
    val w = size.width
    val h = size.height
    val color = IPodColors.LcdText
    val stroke = Stroke(width = maxOf(1.5f, h * 0.13f), cap = StrokeCap.Round)
    val down = Path().apply {
        moveTo(w * 0.05f, h * 0.28f)
        cubicTo(w * 0.40f, h * 0.28f, w * 0.60f, h * 0.72f, w * 0.85f, h * 0.72f)
    }
    val up = Path().apply {
        moveTo(w * 0.05f, h * 0.72f)
        cubicTo(w * 0.40f, h * 0.72f, w * 0.60f, h * 0.28f, w * 0.85f, h * 0.28f)
    }
    drawPath(down, color, style = stroke)
    drawPath(up, color, style = stroke)
    val head = h * 0.2f
    for (y in floatArrayOf(h * 0.28f, h * 0.72f)) {
        val arrow = Path().apply {
            moveTo(w * 0.98f, y)
            lineTo(w * 0.98f - head, y - head * 0.8f)
            lineTo(w * 0.98f - head, y + head * 0.8f)
            close()
        }
        drawPath(arrow, color)
    }
}

/** Two curved arrows chasing each other. */
private fun DrawScope.drawRepeatGlyph() {
    val w = size.width
    val h = size.height
    val color = IPodColors.LcdText
    val stroke = Stroke(width = maxOf(1.5f, h * 0.13f), cap = StrokeCap.Round)
    val inset = h * 0.1f
    val box = Rect(inset, inset, w - inset, h - inset)
    // Top arc: from the left going clockwise over the top to the right.
    drawArc(color, startAngle = 200f, sweepAngle = 140f, useCenter = false, topLeft = box.topLeft, size = box.size, style = stroke)
    // Bottom arc: from the right going clockwise under to the left.
    drawArc(color, startAngle = 20f, sweepAngle = 140f, useCenter = false, topLeft = box.topLeft, size = box.size, style = stroke)
    val head = h * 0.22f
    // Arrowhead at the end of the top arc (right side, pointing down).
    val rx = box.center.x + box.width / 2f * kotlin.math.cos(Math.toRadians(340.0)).toFloat()
    val ry = box.center.y + box.height / 2f * kotlin.math.sin(Math.toRadians(340.0)).toFloat()
    drawPath(Path().apply { moveTo(rx, ry + head); lineTo(rx - head * 0.8f, ry - head * 0.2f); lineTo(rx + head * 0.8f, ry - head * 0.2f); close() }, color)
    // Arrowhead at the end of the bottom arc (left side, pointing up).
    val lx = box.center.x + box.width / 2f * kotlin.math.cos(Math.toRadians(160.0)).toFloat()
    val ly = box.center.y + box.height / 2f * kotlin.math.sin(Math.toRadians(160.0)).toFloat()
    drawPath(Path().apply { moveTo(lx, ly - head); lineTo(lx - head * 0.8f, ly + head * 0.2f); lineTo(lx + head * 0.8f, ly + head * 0.2f); close() }, color)
}

/**
 * The Classic's progress bar: an inset channel (darker at the top, white at the bottom, hairline
 * edge) filled with "aqua" glass — pale at the top, deep blue through the middle, a lighter band at
 * the bottom, and a white specular sheen over the upper half. Beneath it, the bar's own mirror image
 * (channel, edge and fill, rounded ends and all) fading into the LCD white. No playhead while
 * playing; a small dark diamond appears only while the wheel scrubs.
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
    val fillWidth = (size.width * fraction).coerceIn(0f, size.width)
    val fillClip = Path().apply {
        addRoundRect(RoundRect(Rect(0f, barTop, fillWidth, barBottom), corner))
    }
    val channelBrush = Brush.verticalGradient(
        colors = listOf(IPodColors.ProgressTrackTop, IPodColors.ProgressTrackBottom),
        startY = barTop,
        endY = barBottom,
    )
    val glassBrush = Brush.verticalGradient(
        0f to IPodColors.ProgressGlassTop,
        0.45f to IPodColors.ProgressGlassMid,
        0.5f to IPodColors.ProgressGlassLow,
        1f to IPodColors.ProgressGlassBottom,
        startY = barTop,
        endY = barBottom,
    )

    /** The bar — channel, edge, glass fill, sheen — drawn at [alpha]; called upright and mirrored. */
    fun DrawScope.drawBar(alpha: Float) {
        drawRoundRect(brush = channelBrush, topLeft = track.topLeft, size = track.size, cornerRadius = corner, alpha = alpha)
        drawRoundRect(
            color = IPodColors.ProgressTrackEdge,
            topLeft = track.topLeft,
            size = track.size,
            cornerRadius = corner,
            style = Stroke(width = 1f),
            alpha = alpha,
        )
        if (fillWidth > 1f) {
            clipPath(fillClip) {
                drawRect(brush = glassBrush, topLeft = Offset(0f, barTop), size = Size(fillWidth, barHeight), alpha = alpha)
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to IPodColors.HighlightText.copy(alpha = 0.6f),
                        1f to IPodColors.HighlightText.copy(alpha = 0f),
                        startY = barTop,
                        endY = barTop + barHeight * 0.48f,
                    ),
                    topLeft = Offset(0f, barTop),
                    size = Size(fillWidth, barHeight * 0.48f),
                    alpha = alpha,
                )
            }
        }
    }

    // Reflection first: the bar mirrored about its own bottom edge, then faded into the LCD white
    // over REFLECTION of its height and covered entirely below that. The upright bar is drawn on
    // top so the seam stays crisp.
    val reflectionHeight = barHeight * 0.75f
    withTransform({ scale(scaleX = 1f, scaleY = -1f, pivot = Offset(size.width / 2f, barBottom)) }) {
        drawBar(alpha = 0.55f)
    }
    drawRect(
        brush = Brush.verticalGradient(
            0f to IPodColors.LcdBackground.copy(alpha = 0.35f),
            1f to IPodColors.LcdBackground,
            startY = barBottom,
            endY = barBottom + reflectionHeight,
        ),
        topLeft = Offset(0f, barBottom),
        size = Size(size.width, reflectionHeight),
    )
    val coverTop = barBottom + reflectionHeight
    if (coverTop < size.height) {
        drawRect(
            color = IPodColors.LcdBackground,
            topLeft = Offset(0f, coverTop),
            size = Size(size.width, size.height - coverTop),
        )
    }

    // The bar itself.
    drawBar(alpha = 1f)

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

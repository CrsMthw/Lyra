package com.crsmthw.lyra.ui.ipod.lcd

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.crsmthw.lyra.R
import com.crsmthw.lyra.ui.ipod.IPodColors
import com.crsmthw.lyra.ui.ipod.IPodFontFamily
import com.crsmthw.lyra.ui.ipod.nav.IPodStackEntry
import com.crsmthw.lyra.ui.ipod.nav.LcdIndexStatus
import com.crsmthw.lyra.ui.ipod.nav.LcdItem
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.cos
import kotlin.math.sign

// ── Tunables ────────────────────────────────────────────────────────────────
// All meant for the device pass. Listed individually in the structured report.

/** Rotation angle (degrees) of side covers toward the centre. */
private const val COVER_SIDE_ANGLE = 65f
/** Camera distance for the perspective tilt -- NOT multiplied by density. */
internal const val COVER_CAMERA_DISTANCE = 7f
/** Centre cover side is this fraction of the content height. */
private const val COVER_TILE_FRACTION = 0.50f
/** Scale of side covers relative to the centre cover (1.0 = same size). */
private const val COVER_SIDE_SCALE = 0.82f
/** Gap between the centre cover edge and the first side cover, as fraction of tile side. */
private const val COVER_CENTRE_GAP = 0.08f
/** Step between successive side covers, as fraction of tile side (< 1 means overlap). */
private const val COVER_SIDE_STEP = 0.22f
/** Height of the reflection as a fraction of the tile side. */
internal const val COVER_REFLECTION_HEIGHT = 0.42f
/** Alpha of the reflection layer (the seam is fully this; the mask below fades it out). */
internal const val COVER_REFLECTION_ALPHA = 0.5f
/** Mask alpha half-way down the reflection — the higher, the slower the fade. */
internal const val COVER_REFLECTION_MID_ALPHA = 0.55f
/** Mask alpha at 85 % of the reflection's height; 0 at the far edge. */
internal const val COVER_REFLECTION_FAR_ALPHA = 0.15f
/**
 * Where the covers sit vertically: art + reflection centred in the content, then shifted by this
 * fraction of the content height (negative = up). The Classic's Cover Flow sits a little high, and
 * the text block is drawn OVER the reflection floor below.
 */
private const val COVER_VERTICAL_BIAS = -0.05f
/** Height of the "Indexing N of M…" strip above the covers while the list is filling. */
private const val COVER_INDEX_LINE_FRACTION = 0.06f
/** Space under the "N of M" line, as a fraction of the content height — off the LCD's bottom edge. */
private const val COVER_POSITION_BOTTOM_PAD = 0.04f
/** Duration of the position animation per detent (finite tween, never a spring). */
private const val COVER_SLIDE_MILLIS = 180
/**
 * Floor of the glide under a fast spin. The glide's duration is COVER_SLIDE_MILLIS divided by the
 * distance still to cover, so when detents arrive faster than one glide the ribbon speeds up
 * instead of falling further behind with every detent (the lag is what blanked the screen).
 */
private const val COVER_FAST_SLIDE_MILLIS = 50
/** Number of covers drawn per side of the centre cover. */
private const val COVERS_PER_SIDE = 5
/** Jump threshold: snap instead of animate when abs(delta) exceeds this. */
private const val COVER_SNAP_THRESHOLD = 8
/** Title text as a fraction of the content height. */
private const val COVER_TITLE_FRACTION = 0.055f
/** Subtitle text as a fraction of the content height. */
private const val COVER_SUBTITLE_FRACTION = 0.045f
/** Position/indexing text as a fraction of the content height. */
private const val COVER_POSITION_FRACTION = 0.038f
/** Corner radius of the cover edge, as fraction of tile side. */
private const val COVER_EDGE_CORNER_FRACTION = 0.02f
/** Crossfade duration on cover art images. */
internal const val COVER_ART_CROSSFADE_MS = 150
/** Space between the bottom of the covers+reflections and the text block, as fraction of content height. */
private const val COVER_TEXT_TOP_GAP = 0.02f

// ── Entry point ─────────────────────────────────────────────────────────────

/**
 * The iPod Classic's Cover Flow: a 3D ribbon of album covers along X on the LCD's white
 * background. Driven entirely by the entry's [LcdListState][com.crsmthw.lyra.ui.ipod.nav.LcdListState]:
 * [selectedIndex][com.crsmthw.lyra.ui.ipod.nav.LcdListState.selectedIndex] is the centre cover,
 * items carry [LcdItem.artUrl] for the tile and title/subtitle for the text below.
 *
 * No touch handling (rule: the wheel is the only input). No haptics or sounds (fired by the
 * wheel, never here). Reads NOTHING from nowPlaying so the 1 Hz tick cannot recompose it.
 */
/** A cover's pose at one instant: its unscaled art rect in root coordinates, plus the scale and tilt drawn around the art's centre. */
internal class CoverPose(val artRect: Rect, val scale: Float, val rotationY: Float)

/**
 * The ribbon's layout in root coordinates plus its LIVE position, reported by Cover Flow on every
 * layout. LcdScreen asks it for the SELECTED cover's pose at the instant of a SELECT — which may
 * be mid-glide, a little to one side, smaller and turned — so the flight into Now Playing takes
 * off from where that cover really is instead of from the centre slot (the one-frame jump that
 * read as a flash when SELECT landed within the glide).
 */
internal class CoverFlowGeometry(
    private val originInRoot: Offset,
    private val contentWidthPx: Float,
    private val tileSidePx: Float,
    private val coversTopPx: Float,
    private val firstNeighbourOffsetPx: Float,
    private val sideStepPx: Float,
    private val position: Animatable<Float, AnimationVector1D>,
) {
    fun poseOf(index: Int): CoverPose {
        val d = index.toFloat() - position.value
        val pose = tilePose(d, firstNeighbourOffsetPx, sideStepPx)
        val left = originInRoot.x + contentWidthPx / 2f - tileSidePx / 2f + pose.offsetX
        val top = originInRoot.y + coversTopPx
        return CoverPose(Rect(left, top, left + tileSidePx, top + tileSidePx), pose.scale, pose.rotationY)
    }
}

/** The ribbon's pose function: where a tile at signed distance [d] from the centre sits, how big, how turned. */
internal class TilePose(val offsetX: Float, val scale: Float, val rotationY: Float)

internal fun tilePose(d: Float, firstNeighbourOffsetPx: Float, sideStepPx: Float): TilePose {
    val c = d.coerceIn(-1f, 1f)
    // Within the first step the offset is interpolated linearly with |d| (as the angle and scale
    // are) so a glide never pops; from the first neighbour on, every cover steps COVER_SIDE_STEP
    // outward — the Classic's tightly packed rolodex.
    val a = abs(d)
    val mag = if (a <= 1f) a * firstNeighbourOffsetPx else firstNeighbourOffsetPx + (a - 1f) * sideStepPx
    return TilePose(
        offsetX = sign(d) * mag,
        scale = 1f - abs(c) * (1f - COVER_SIDE_SCALE),
        // A tile to the RIGHT of centre (d > 0) presents its LEFT edge toward the viewer, which is
        // negative rotationY (positive rotationY brings the right edge nearer) — the opposite of
        // the Now Playing art, which faces right.
        rotationY = -c * COVER_SIDE_ANGLE,
    )
}

@Composable
internal fun LcdCoverFlowContent(
    entry: IPodStackEntry,
    likedIndex: LcdIndexStatus?,
    contentHeight: Dp,
    /** The tile that is FLYING into Now Playing (LcdScreen's overlay draws it; this slot stays empty). */
    hiddenIndex: Int? = null,
    /** The ribbon's geometry + live position, reported on every layout — the flight's take-off. */
    onGeometry: (CoverFlowGeometry) -> Unit = {},
) {
    val list = entry.list
    val items = list.items
    val title = entry.title

    when {
        list.isLoading -> LcdCentredMessage(
            text = stringResource(R.string.ipod_list_loading),
            contentHeight = contentHeight,
        )
        list.error != null -> LcdCentredMessage(
            text = stringResource(R.string.ipod_list_error, title.resolve()),
            contentHeight = contentHeight,
        )
        items.isEmpty() -> LcdCentredMessage(
            text = stringResource(R.string.ipod_list_empty, title.resolve()),
            contentHeight = contentHeight,
        )
        else -> CoverFlowRow(
            items = items,
            selectedIndex = list.selectedIndex,
            likedIndex = likedIndex,
            contentHeight = contentHeight,
            hiddenIndex = hiddenIndex,
            onGeometry = onGeometry,
        )
    }
}

// ── The 3D row ──────────────────────────────────────────────────────────────

@Composable
private fun CoverFlowRow(
    items: List<LcdItem>,
    selectedIndex: Int,
    likedIndex: LcdIndexStatus?,
    contentHeight: Dp,
    hiddenIndex: Int?,
    onGeometry: (CoverFlowGeometry) -> Unit,
) {
    val density = LocalDensity.current
    val contentHeightPx = with(density) { contentHeight.toPx() }

    // The animated centre position. Changes per detent; mid-glide it interpolates smoothly.
    val position = remember { Animatable(selectedIndex.toFloat()) }
    LaunchedEffect(selectedIndex) {
        val target = selectedIndex.toFloat()
        val gap = abs(target - position.value)
        when {
            gap > COVER_SNAP_THRESHOLD -> position.snapTo(target)
            gap > 0f -> {
                // One detent glides for the full duration; a target several covers ahead (a fast
                // spin re-targets before the last glide is done) gets a proportionally shorter
                // glide, floored, so the position keeps up with the wheel.
                val millis = (COVER_SLIDE_MILLIS / gap).roundToInt()
                    .coerceIn(COVER_FAST_SLIDE_MILLIS, COVER_SLIDE_MILLIS)
                position.animateTo(target, tween(millis, easing = FastOutSlowInEasing))
            }
        }
    }

    // The cover the ribbon is CENTRED on right now — the animated position, rounded. The tile
    // window, the z-order and the text all follow THIS, not the detent's selectedIndex: under a
    // fast spin the position lags the detent by several covers, and a window built around the
    // detent composed only tiles ahead of the visible centre (the departing side went blank, the
    // whole ribbon once the lag passed COVERS_PER_SIDE) while a far incoming tile, nearer to the
    // detent than a visually nearer one, drew on top of it. derivedStateOf recomposes once per
    // cover crossed, never per frame.
    val displayedCentre by remember(items.size) {
        derivedStateOf { position.value.roundToInt().coerceIn(0, items.lastIndex.coerceAtLeast(0)) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val contentWidthPx = constraints.maxWidth.toFloat()

        // Tile geometry -- constant per layout, never per frame.
        val tileSidePx = contentHeightPx * COVER_TILE_FRACTION
        val reflectionHeightPx = tileSidePx * COVER_REFLECTION_HEIGHT
        val totalTileHeightPx = tileSidePx + reflectionHeightPx
        // Where the FIRST neighbour's centre sits: past the centre cover's half-width, the gap, and
        // the neighbour's own projected half-width (scaled, turned through COVER_SIDE_ANGLE). A
        // bare gap put the first two neighbours underneath the centre cover.
        val firstNeighbourOffsetPx = tileSidePx * (
            0.5f + COVER_CENTRE_GAP +
                0.5f * COVER_SIDE_SCALE * cos(Math.toRadians(COVER_SIDE_ANGLE.toDouble())).toFloat()
            )
        val sideStepPx = tileSidePx * COVER_SIDE_STEP
        val tileSideDp = with(density) { tileSidePx.toDp() }
        val reflectionHeightDp = with(density) { reflectionHeightPx.toDp() }
        val totalTileHeightDp = with(density) { totalTileHeightPx.toDp() }

        // Window of tiles around the DISPLAYED centre: composition-phase, changes once per cover
        // crossed (not per animation frame).
        val centreIndex = displayedCentre
        val windowStart = (centreIndex - COVERS_PER_SIDE).coerceAtLeast(0)
        val windowEnd = (centreIndex + COVERS_PER_SIDE).coerceAtMost(items.lastIndex)

        // Layout: the covers (art + reflection) centred in the content with a slight upward bias,
        // the title / artist / "N of M" block drawn OVER the reflection floor at the bottom, and —
        // while the list is still filling — the indexing line over the empty band above the covers.
        val indexLineHeightPx = contentHeightPx * COVER_INDEX_LINE_FRACTION
        val coversTopPx = ((contentHeightPx - totalTileHeightPx) / 2f + contentHeightPx * COVER_VERTICAL_BIAS)
            .coerceAtLeast(if (likedIndex != null) indexLineHeightPx else 0f)

        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coords ->
                        onGeometry(
                            CoverFlowGeometry(
                                originInRoot = coords.positionInRoot(),
                                contentWidthPx = contentWidthPx,
                                tileSidePx = tileSidePx,
                                coversTopPx = coversTopPx,
                                firstNeighbourOffsetPx = firstNeighbourOffsetPx,
                                sideStepPx = sideStepPx,
                                position = position,
                            ),
                        )
                    },
            ) {
                for (i in windowStart..windowEnd) {
                    val item = items.getOrNull(i) ?: continue
                    val zOrder = -(abs(i - centreIndex).toFloat())
                    androidx.compose.runtime.key(item.id) {
                        CoverFlowTile(
                            item = item,
                            index = i,
                            zOrder = zOrder,
                            hidden = hiddenIndex == i,
                            tileSidePx = tileSidePx,
                            tileSideDp = tileSideDp,
                            reflectionHeightPx = reflectionHeightPx,
                            reflectionHeightDp = reflectionHeightDp,
                            contentWidthPx = contentWidthPx,
                            firstNeighbourOffsetPx = firstNeighbourOffsetPx,
                            sideStepPx = sideStepPx,
                            coverVerticalOffsetPx = coversTopPx,
                            position = position,
                        )
                    }
                }
            }

            if (likedIndex != null) {
                CoverFlowIndexingLine(
                    likedIndex = likedIndex,
                    contentHeightPx = contentHeightPx,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(with(density) { indexLineHeightPx.toDp() }),
                )
            }

            // The text block, over the reflection floor: the cover the ribbon is centred on right
            // now (it swaps as each cover crosses the centre, so a fast spin streams the titles
            // past with the covers and the two never disagree).
            val highlightedItem = items.getOrNull(centreIndex)
            if (highlightedItem != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = contentHeight * COVER_POSITION_BOTTOM_PAD),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CoverFlowText(
                        item = highlightedItem,
                        contentHeightPx = contentHeightPx,
                        contentWidthPx = contentWidthPx,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(contentHeight * COVER_TEXT_TOP_GAP))
                    CoverFlowPosition(
                        itemIndex = centreIndex,
                        itemCount = items.size,
                        contentHeightPx = contentHeightPx,
                        contentWidthPx = contentWidthPx,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

// ── Single cover tile ───────────────────────────────────────────────────────

@Composable
private fun CoverFlowTile(
    item: LcdItem,
    index: Int,
    zOrder: Float,
    /** The centre cover while it flies into Now Playing: the overlay draws it, this slot stays empty. */
    hidden: Boolean,
    tileSidePx: Float,
    tileSideDp: Dp,
    reflectionHeightPx: Float,
    reflectionHeightDp: Dp,
    contentWidthPx: Float,
    firstNeighbourOffsetPx: Float,
    sideStepPx: Float,
    coverVerticalOffsetPx: Float,
    position: Animatable<Float, *>,
) {
    val context = LocalContext.current
    val artUrl = item.artUrl.orEmpty()

    val imageRequest = remember(artUrl) {
        ImageRequest.Builder(context)
            .data(artUrl.ifBlank { null })
            .crossfade(COVER_ART_CROSSFADE_MS)
            .build()
    }

    val totalTileHeightDp = tileSideDp + reflectionHeightDp
    val pivotY = (tileSidePx / 2f) / (tileSidePx + reflectionHeightPx)

    val edgeCornerDp = tileSideDp * COVER_EDGE_CORNER_FRACTION

    // All spatial transforms read `position.value` INSIDE graphicsLayer -- draw-phase only,
    // no recomposition per frame. The tile's layout size is constant (tileSide x totalTileHeight);
    // all shrinkage is via scaleX/scaleY in the layer.
    Box(
        modifier = Modifier
            .size(tileSideDp, totalTileHeightDp)
            .zIndex(zOrder)
            .graphicsLayer {
                // The pose is ONE function of the signed distance from the animated centre —
                // tilePose — shared with CoverFlowGeometry.poseOf so a flight takes off from
                // exactly where the tile is drawn.
                val pose = tilePose(index.toFloat() - position.value, firstNeighbourOffsetPx, sideStepPx)
                rotationY = pose.rotationY
                cameraDistance = COVER_CAMERA_DISTANCE
                transformOrigin = TransformOrigin(0.5f, pivotY)
                alpha = if (hidden) 0f else 1f
                scaleX = pose.scale
                scaleY = pose.scale
                // translationX places the tile's centre at the pose offset from the ribbon's centre;
                // translationY is the covers' top in the content.
                translationX = contentWidthPx / 2f - tileSidePx / 2f + pose.offsetX
                translationY = coverVerticalOffsetPx
            },
    ) {
        // Art square with hairline edge.
        Box(
            modifier = Modifier
                .size(tileSideDp)
                .border(
                    width = 0.5.dp,
                    color = IPodColors.CoverEdge,
                    shape = RoundedCornerShape(edgeCornerDp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            ArtPlaceholder(Modifier.fillMaxSize(), tileSideDp)
            if (artUrl.isNotBlank()) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Reflection beneath the art.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .width(tileSideDp)
                .height(reflectionHeightDp)
                .clipToBounds(),
        ) {
            Box(
                modifier = Modifier
                    // The full square must overflow the clip box with its TOP at the box's top.
                    // Neither size nor requiredSize does that: a child that violates its
                    // constraints is reported at the coerced size and its content CENTRED in the
                    // slot, which showed the art's middle band. wrapContentSize(TopStart,
                    // unbounded) measures the square unconstrained and pins it top-start.
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .size(tileSideDp)
                    .graphicsLayer {
                        scaleY = -1f
                        alpha = COVER_REFLECTION_ALPHA
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = reflectionMask(
                                heightFraction = COVER_REFLECTION_HEIGHT,
                                midAlpha = COVER_REFLECTION_MID_ALPHA,
                                farAlpha = COVER_REFLECTION_FAR_ALPHA,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                ArtPlaceholder(Modifier.fillMaxSize(), tileSideDp)
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

// ── Text labels ─────────────────────────────────────────────────────────────

@Composable
private fun CoverFlowText(
    item: LcdItem,
    contentHeightPx: Float,
    contentWidthPx: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val titleFontSize = with(density) { (contentHeightPx * COVER_TITLE_FRACTION).toSp() }
    val subtitleFontSize = with(density) { (contentHeightPx * COVER_SUBTITLE_FRACTION).toSp() }
    val sidePad = with(density) { (contentWidthPx * 0.08f).toDp() }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Title (bold, centred, single-line ellipsised).
        Text(
            text = item.title.resolve(),
            fontFamily = IPodFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = titleFontSize,
            color = IPodColors.LcdText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = sidePad),
        )

        // Subtitle (centred, single-line ellipsised).
        val subtitleText = item.subtitle?.resolve().orEmpty()
        if (subtitleText.isNotEmpty()) {
            Text(
                text = subtitleText,
                fontFamily = IPodFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = subtitleFontSize,
                color = IPodColors.LcdTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sidePad),
            )
        }
    }
}

/** "N of M", centred, at the bottom of the content with its own margin. */
@Composable
private fun CoverFlowPosition(
    itemIndex: Int,
    itemCount: Int,
    contentHeightPx: Float,
    contentWidthPx: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val positionFontSize = with(density) { (contentHeightPx * COVER_POSITION_FRACTION).toSp() }
    val sidePad = with(density) { (contentWidthPx * 0.08f).toDp() }
    Text(
        text = stringResource(R.string.ipod_now_playing_position, itemIndex + 1, itemCount),
        fontFamily = IPodFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = positionFontSize,
        color = IPodColors.LcdTextSecondary,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(horizontal = sidePad),
    )
}

/** "Indexing N of M…" — centred above the covers, shown only while the liked list is still filling. */
@Composable
private fun CoverFlowIndexingLine(
    likedIndex: LcdIndexStatus,
    contentHeightPx: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val fontSize = with(density) { (contentHeightPx * COVER_POSITION_FRACTION).toSp() }
    Text(
        text = stringResource(
            R.string.ipod_coverflow_indexing,
            likedIndex.indexed,
            likedIndex.total,
        ),
        fontFamily = IPodFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = fontSize,
        color = IPodColors.LcdTextSecondary,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = modifier.wrapContentHeight(Alignment.CenterVertically),
    )
}

/**
 * The DstIn mask that fades a flipped copy of the art into its reflection. In the flipped
 * square's own coordinates 1f is the seam (opaque) and (1 − heightFraction) is the far edge
 * (transparent); [midAlpha] / [farAlpha] shape the fall-off between them. Shared with the flight
 * overlay so a cover's reflection can be morphed continuously into Now Playing's.
 */
internal fun reflectionMask(heightFraction: Float, midAlpha: Float, farAlpha: Float): Brush =
    Brush.verticalGradient(
        0f to Color.Transparent,
        (1f - heightFraction) to Color.Transparent,
        (1f - heightFraction * 0.85f) to Color.Black.copy(alpha = farAlpha),
        (1f - heightFraction * 0.5f) to Color.Black.copy(alpha = midAlpha),
        1f to Color.Black,
    )

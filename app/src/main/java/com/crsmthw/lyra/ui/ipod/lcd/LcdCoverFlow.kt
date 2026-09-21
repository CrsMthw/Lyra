package com.crsmthw.lyra.ui.ipod.lcd

import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
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

// ── Tunables ────────────────────────────────────────────────────────────────
// All meant for the device pass. Listed individually in the structured report.

/** Rotation angle (degrees) of side covers toward the centre. */
private const val COVER_SIDE_ANGLE = 65f
/** Camera distance for the perspective tilt -- NOT multiplied by density. */
private const val COVER_CAMERA_DISTANCE = 7f
/** Centre cover side is this fraction of the content height. */
private const val COVER_TILE_FRACTION = 0.50f
/** Scale of side covers relative to the centre cover (1.0 = same size). */
private const val COVER_SIDE_SCALE = 0.82f
/** Gap between the centre cover edge and the first side cover, as fraction of tile side. */
private const val COVER_CENTRE_GAP = 0.08f
/** Step between successive side covers, as fraction of tile side (< 1 means overlap). */
private const val COVER_SIDE_STEP = 0.22f
/** Height of the reflection as a fraction of the tile side. */
private const val COVER_REFLECTION_HEIGHT = 0.35f
/** Starting alpha of the reflection at the seam. */
private const val COVER_REFLECTION_ALPHA = 0.35f
/** Duration of the position animation per detent (finite tween, never a spring). */
private const val COVER_SLIDE_MILLIS = 180
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
private const val COVER_ART_CROSSFADE_MS = 150
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
@Composable
internal fun LcdCoverFlowContent(
    entry: IPodStackEntry,
    likedIndex: LcdIndexStatus?,
    contentHeight: Dp,
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
) {
    val density = LocalDensity.current
    val contentHeightPx = with(density) { contentHeight.toPx() }

    // The animated centre position. Changes per detent; mid-glide it interpolates smoothly.
    val position = remember { Animatable(selectedIndex.toFloat()) }
    LaunchedEffect(selectedIndex) {
        val target = selectedIndex.toFloat()
        if (abs(target - position.value) > COVER_SNAP_THRESHOLD) {
            position.snapTo(target)
        } else {
            position.animateTo(target, tween(COVER_SLIDE_MILLIS, easing = FastOutSlowInEasing))
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val contentWidthPx = constraints.maxWidth.toFloat()

        // Tile geometry -- constant per layout, never per frame.
        val tileSidePx = contentHeightPx * COVER_TILE_FRACTION
        val reflectionHeightPx = tileSidePx * COVER_REFLECTION_HEIGHT
        val totalTileHeightPx = tileSidePx + reflectionHeightPx
        val centreGapPx = tileSidePx * COVER_CENTRE_GAP
        val sideStepPx = tileSidePx * COVER_SIDE_STEP
        val tileSideDp = with(density) { tileSidePx.toDp() }
        val reflectionHeightDp = with(density) { reflectionHeightPx.toDp() }
        val totalTileHeightDp = with(density) { totalTileHeightPx.toDp() }

        // Window of tiles: composition-phase, changes only per detent (not per animation frame).
        val centreIndex = selectedIndex.coerceIn(items.indices)
        val windowStart = (centreIndex - COVERS_PER_SIDE).coerceAtLeast(0)
        val windowEnd = (centreIndex + COVERS_PER_SIDE).coerceAtMost(items.lastIndex)

        // Layout: a Column with the covers box taking remaining space and the text at the bottom.
        // The covers sit vertically centred inside their box via translationY in the graphicsLayer;
        // the text sits at the bottom by layout, so no speculative height calculation is needed.
        Column(Modifier.fillMaxSize()) {
            // Covers box: takes all remaining vertical space above the text. Tiles position
            // themselves via graphicsLayer translationX/Y, centred vertically and horizontally.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                // The Box takes the remaining height above the text. Tiles position via
                // graphicsLayer translationX/Y to centre horizontally and vertically.
                val boxHeightPx = constraints.maxHeight.toFloat()
                val coverVerticalCentrePx = ((boxHeightPx - totalTileHeightPx) / 2f)
                    .coerceAtLeast(0f)

                for (i in windowStart..windowEnd) {
                    val item = items.getOrNull(i) ?: continue
                    val zOrder = -(abs(i - centreIndex).toFloat())
                    androidx.compose.runtime.key(item.id) {
                        CoverFlowTile(
                            item = item,
                            index = i,
                            zOrder = zOrder,
                            tileSidePx = tileSidePx,
                            tileSideDp = tileSideDp,
                            reflectionHeightPx = reflectionHeightPx,
                            reflectionHeightDp = reflectionHeightDp,
                            contentWidthPx = contentWidthPx,
                            centreGapPx = centreGapPx,
                            sideStepPx = sideStepPx,
                            coverVerticalOffsetPx = coverVerticalCentrePx,
                            position = position,
                        )
                    }
                }
            }

            // Text block below the covers, at the bottom of the content area.
            // The highlighted item for the text labels -- use the detent's selectedIndex, not the
            // animated position, so labels swap at the detent with no mid-glide flicker.
            val highlightedItem = items.getOrNull(centreIndex)
            if (highlightedItem != null) {
                Spacer(Modifier.height(contentHeight * COVER_TEXT_TOP_GAP))
                CoverFlowText(
                    item = highlightedItem,
                    itemIndex = centreIndex,
                    itemCount = items.size,
                    likedIndex = likedIndex,
                    contentHeightPx = contentHeightPx,
                    contentWidthPx = contentWidthPx,
                    modifier = Modifier.fillMaxWidth(),
                )
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
    tileSidePx: Float,
    tileSideDp: Dp,
    reflectionHeightPx: Float,
    reflectionHeightDp: Dp,
    contentWidthPx: Float,
    centreGapPx: Float,
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
                val posVal = position.value
                val d = index.toFloat() - posVal
                val c = d.coerceIn(-1f, 1f)

                // Rotation: a tile to the RIGHT of centre (d > 0) presents its LEFT edge
                // toward the viewer, which is negative rotationY (positive rotationY brings
                // the right edge nearer). This is the opposite of the Now Playing art, which
                // faces right.
                rotationY = -c * COVER_SIDE_ANGLE
                cameraDistance = COVER_CAMERA_DISTANCE
                transformOrigin = TransformOrigin(0.5f, pivotY)

                val scale = 1f - abs(c) * (1f - COVER_SIDE_SCALE)
                scaleX = scale
                scaleY = scale

                // Translation: centre gap for the immediate neighbours, then packed side steps.
                // translationX centres the tile horizontally and offsets by the pose function.
                // translationY centres the tile vertically in the weighted Box above the text.
                val tx = c * centreGapPx + (d - c) * sideStepPx
                translationX = contentWidthPx / 2f - tileSidePx / 2f + tx
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
                            brush = Brush.verticalGradient(
                                0f to Color.Transparent,
                                (1f - COVER_REFLECTION_HEIGHT) to Color.Transparent,
                                (1f - COVER_REFLECTION_HEIGHT * 0.5f) to Color.Black.copy(alpha = 0.3f),
                                1f to Color.Black,
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
    itemIndex: Int,
    itemCount: Int,
    likedIndex: LcdIndexStatus?,
    contentHeightPx: Float,
    contentWidthPx: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val titleFontSize = with(density) { (contentHeightPx * COVER_TITLE_FRACTION).toSp() }
    val subtitleFontSize = with(density) { (contentHeightPx * COVER_SUBTITLE_FRACTION).toSp() }
    val positionFontSize = with(density) { (contentHeightPx * COVER_POSITION_FRACTION).toSp() }
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

        Spacer(Modifier.height(with(density) { (contentHeightPx * COVER_TEXT_TOP_GAP).toDp() }))

        // Bottom row: "N of M" right, indexing line left.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = sidePad),
        ) {
            // Position counter -- bottom right.
            Text(
                text = stringResource(
                    R.string.ipod_now_playing_position,
                    itemIndex + 1,
                    itemCount,
                ),
                fontFamily = IPodFontFamily,
                fontSize = positionFontSize,
                color = IPodColors.LcdTextSecondary,
                maxLines = 1,
                modifier = Modifier.align(Alignment.CenterEnd),
            )

            // Indexing status -- bottom left, only while the list is incomplete.
            if (likedIndex != null) {
                Text(
                    text = stringResource(
                        R.string.ipod_coverflow_indexing,
                        likedIndex.indexed,
                        likedIndex.total,
                    ),
                    fontFamily = IPodFontFamily,
                    fontSize = positionFontSize,
                    color = IPodColors.LcdTextSecondary,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
        }
    }
}

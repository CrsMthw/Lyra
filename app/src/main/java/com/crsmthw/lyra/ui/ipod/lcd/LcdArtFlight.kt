package com.crsmthw.lyra.ui.ipod.lcd

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.util.lerp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlin.math.roundToInt

/**
 * One SELECT on Cover Flow: the centre cover flies from its slot in the ribbon into Now Playing's
 * art slot — sliding left, growing, turning through Now Playing's tilt, its reflection morphing
 * from the ribbon's into Now Playing's — while the rest of Cover Flow fades out and Now Playing's
 * text fades in underneath (LcdScreen's CoverFlow → NowPlaying transition). Both real arts are
 * hidden for the duration; the overlay lands EXACTLY on Now Playing's measured art rect and is
 * removed in the same composition that shows the real art, so nothing pops.
 */
internal class ArtFlight(
    val id: Int,
    val artUrl: String,
    /** The centre cover's art square (no reflection) in ROOT coordinates at take-off. */
    val from: Rect,
)

/** The flight's duration — also the fade of everything else on the two screens. Finite, never a spring. */
internal const val ART_FLIGHT_MILLIS = 320

@Composable
internal fun ArtFlightOverlay(
    flight: ArtFlight,
    /** Now Playing's art column rect (art + reflection, untilted) in ROOT coordinates; null until measured. */
    target: State<Rect?>,
    /** The LCD content box's root position — the overlay is placed relative to it. */
    containerOrigin: State<Offset>,
    progress: Animatable<Float, AnimationVector1D>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val p = progress.value
    val from = flight.from
    // Until Now Playing has been measured (its first layout, one frame in), aim at the take-off.
    val to = target.value
    val toSide = to?.width ?: from.width
    val toLeft = to?.left ?: from.left
    val toTop = to?.top ?: from.top

    // Pose at this progress: position, size, tilt, reflection — every one a lerp between the
    // ribbon's centre cover and Now Playing's art.
    val side = lerp(from.width, toSide, p)
    val left = lerp(from.left, toLeft, p) - containerOrigin.value.x
    val top = lerp(from.top, toTop, p) - containerOrigin.value.y
    val rotation = lerp(0f, ART_ROTATION_Y, p)
    val camera = lerp(COVER_CAMERA_DISTANCE, ART_CAMERA_DISTANCE, p)
    val reflectionFraction = lerp(COVER_REFLECTION_HEIGHT, REFLECTION_HEIGHT_FRACTION, p)
    val reflectionAlpha = lerp(COVER_REFLECTION_ALPHA, REFLECTION_ALPHA, p)
    val midAlpha = lerp(COVER_REFLECTION_MID_ALPHA, REFLECTION_MID_ALPHA, p)
    val farAlpha = lerp(COVER_REFLECTION_FAR_ALPHA, 0f, p)
    val reflectionPx = side * reflectionFraction
    val pivotY = (side / 2f) / (side + reflectionPx)

    val sideDp = with(density) { side.toDp() }
    val reflectionDp = with(density) { reflectionPx.toDp() }

    // The SAME request shape as the Cover Flow tile (no explicit size, the same crossfade), so the
    // bitmap the tile already decoded is a memory-cache hit and the first frame is never blank.
    val imageRequest = remember(flight.artUrl) {
        ImageRequest.Builder(context)
            .data(flight.artUrl)
            .crossfade(COVER_ART_CROSSFADE_MS)
            .build()
    }

    Box(
        modifier = modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(sideDp, sideDp + reflectionDp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationY = rotation
                    cameraDistance = camera
                    transformOrigin = TransformOrigin(0.5f, pivotY)
                },
        ) {
            Box(Modifier.size(sideDp), contentAlignment = Alignment.Center) {
                ArtPlaceholder(Modifier.fillMaxSize(), sideDp)
                AsyncImage(
                    model = imageRequest,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .width(sideDp)
                    .height(reflectionDp)
                    .clipToBounds(),
            ) {
                Box(
                    modifier = Modifier
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .size(sideDp)
                        .graphicsLayer {
                            scaleY = -1f
                            alpha = reflectionAlpha
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = reflectionMask(reflectionFraction, midAlpha, farAlpha),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    ArtPlaceholder(Modifier.fillMaxSize(), sideDp)
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

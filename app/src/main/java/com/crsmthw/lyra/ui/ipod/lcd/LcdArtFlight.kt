package com.crsmthw.lyra.ui.ipod.lcd

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.util.lerp
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.memory.MemoryCache
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
    /** The tile that is flying — hidden in the ribbon for the duration. */
    val coverIndex: Int,
    /** The cover's pose at take-off: its unscaled art rect in ROOT coordinates, scale and tilt (mid-glide it is not at rest). */
    val from: CoverPose,
)

/** The flight's duration — also the fade of everything else on the two screens. Finite, never a spring. */
internal const val ART_FLIGHT_MILLIS = 320

/**
 * After landing, the overlay fades out over the now-visible real art. The two are the same rect,
 * tilt and mask, but the overlay's bitmap was decoded at the TILE's size (a memory-cache hit at
 * take-off) while Now Playing's is decoded at its own, larger size — swapped in one frame that
 * read as a flash; crossfaded it is a sharpening nobody notices.
 */
internal const val ART_LANDING_FADE_MILLIS = 120

@Composable
internal fun ArtFlightOverlay(
    flight: ArtFlight,
    /** Now Playing's art column rect (art + reflection, untilted) in ROOT coordinates; null until measured. */
    target: State<Rect?>,
    /** The LCD content box's root position — the overlay is placed relative to it. */
    containerOrigin: State<Offset>,
    progress: Animatable<Float, AnimationVector1D>,
    /** 1 during the flight; animated to 0 over [ART_LANDING_FADE_MILLIS] once landed, over the real art. */
    overlayAlpha: Animatable<Float, AnimationVector1D>,
    /**
     * True once landed and the real art is showing underneath. The overlay's REFLECTION goes out
     * at that instant: two half-transparent reflections stacked through the crossfade read as one
     * brighter reflection that then dims — a flash. Only the opaque art crossfades.
     */
    landed: State<Boolean>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // The tile's bitmap, straight from Coil's memory cache, drawn synchronously. An AsyncImage
    // resolves its size from the first layout and delivers even a memory-cache hit a frame later,
    // so the overlay's first frame showed the grey placeholder — a flash at take-off. The key is
    // the url (no transformations → no extras); a miss (evicted, or the tile never loaded) falls
    // back to the AsyncImage path below.
    val cachedBitmap = remember(flight.artUrl) {
        val loader = SingletonImageLoader.get(context)
        (loader.memoryCache?.get(MemoryCache.Key(flight.artUrl))?.image as? BitmapImage)
            ?.bitmap?.asImageBitmap()
    }
    val p = progress.value
    val from = flight.from.artRect
    // Until Now Playing has been measured (its first layout, one frame in), aim at the take-off.
    val to = target.value
    val toSide = to?.width ?: from.width
    // Both ends scale and turn about the ART's centre (the tile's and Now Playing's transform
    // origin), so the flight lerps that centre and lays the box out around it.
    val toCentreX = to?.let { it.left + it.width / 2f } ?: from.center.x
    val toCentreY = to?.let { it.top + it.width / 2f } ?: from.center.y

    // Pose at this progress: position, size, tilt, reflection — every one a lerp between the
    // selected cover as it is drawn right now and Now Playing's art.
    val side = lerp(from.width, toSide, p)
    val centreX = lerp(from.center.x, toCentreX, p) - containerOrigin.value.x
    val centreY = lerp(from.center.y, toCentreY, p) - containerOrigin.value.y
    val left = centreX - side / 2f
    val top = centreY - side / 2f
    val extraScale = lerp(flight.from.scale, 1f, p)
    val rotation = lerp(flight.from.rotationY, ART_ROTATION_Y, p)
    val camera = lerp(COVER_CAMERA_DISTANCE, ART_CAMERA_DISTANCE, p)
    val reflectionFraction = lerp(COVER_REFLECTION_HEIGHT, REFLECTION_HEIGHT_FRACTION, p)
    val reflectionAlpha = lerp(COVER_REFLECTION_ALPHA, REFLECTION_ALPHA, p)
    val midAlpha = lerp(COVER_REFLECTION_MID_ALPHA, REFLECTION_MID_ALPHA, p)
    val farAlpha = lerp(COVER_REFLECTION_FAR_ALPHA, REFLECTION_FAR_ALPHA, p)
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
            .size(sideDp, sideDp + reflectionDp)
            .graphicsLayer { alpha = overlayAlpha.value },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationY = rotation
                    cameraDistance = camera
                    transformOrigin = TransformOrigin(0.5f, pivotY)
                    scaleX = extraScale
                    scaleY = extraScale
                },
        ) {
            Box(Modifier.size(sideDp), contentAlignment = Alignment.Center) {
                ArtPlaceholder(Modifier.fillMaxSize(), sideDp)
                FlightArt(cachedBitmap, imageRequest, Modifier.fillMaxSize())
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
                            // Gone the instant the real reflection appears (see [landed]).
                            alpha = if (landed.value) 0f else reflectionAlpha
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
                    FlightArt(cachedBitmap, imageRequest, Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** The memory-cached bitmap when there is one (first frame, no load); otherwise Coil's own path. */
@Composable
private fun FlightArt(cachedBitmap: ImageBitmap?, request: ImageRequest, modifier: Modifier) {
    if (cachedBitmap != null) {
        Image(
            painter = BitmapPainter(cachedBitmap),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    }
}

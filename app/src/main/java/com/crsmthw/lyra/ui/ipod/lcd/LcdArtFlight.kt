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
 * One flight between Cover Flow and Now Playing: forward (SELECT on a cover) or reverse
 * (MENU from Now Playing back to the same song on the ribbon).
 *
 * Forward: the centre cover flies from its slot in the ribbon into Now Playing's art slot.
 * Reverse: the art flies from Now Playing back into the ribbon's selected slot.
 *
 * Both share the same overlay mechanics — a Coil memory-cache bitmap, position/size/tilt/reflection
 * lerped over [ART_FLIGHT_MILLIS], landing crossfade [ART_LANDING_FADE_MILLIS] — and the
 * same two Animatables (progress, overlayAlpha), fresh per flight (keyed on [ArtFlight.id]).
 */
internal class ArtFlight(
    val id: Int,
    val artUrl: String,
    /** The tile that is flying — hidden in the ribbon for the duration. */
    val coverIndex: Int,
    /** The cover's pose at take-off (forward) or the Now Playing pose at take-off (reverse). */
    val from: CoverPose,
    /** Forward = CoverFlow->NowPlaying; reverse = NowPlaying->CoverFlow. */
    val direction: ArtFlightDirection,
)

internal enum class ArtFlightDirection { FORWARD, REVERSE }

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
    /**
     * The LANDING pose, live from the incoming screen: Now Playing's art rect (forward) or the
     * Cover Flow tile's live pose (reverse). Null until the incoming child is measured — aim at
     * the take-off until then.
     */
    target: State<CoverPose?>,
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
    // Until the INCOMING screen has been measured (its first layout, one frame in), aim at the take-off.
    val to = target.value
    val toRect = to?.artRect ?: from
    val toSide = toRect.width
    val toScale = to?.scale ?: flight.from.scale
    val toRotation = to?.rotationY ?: flight.from.rotationY
    // Camera: forward goes Cover Flow → Now Playing; reverse goes the other way.
    val (fromCamera, toCamera) = when (flight.direction) {
        ArtFlightDirection.FORWARD -> COVER_CAMERA_DISTANCE to ART_CAMERA_DISTANCE
        ArtFlightDirection.REVERSE -> ART_CAMERA_DISTANCE to COVER_CAMERA_DISTANCE
    }
    // Reflection: forward goes Cover Flow → Now Playing; reverse goes the other way.
    val (fromReflFrac, toReflFrac) = when (flight.direction) {
        ArtFlightDirection.FORWARD -> COVER_REFLECTION_HEIGHT to REFLECTION_HEIGHT_FRACTION
        ArtFlightDirection.REVERSE -> REFLECTION_HEIGHT_FRACTION to COVER_REFLECTION_HEIGHT
    }
    val (fromReflAlpha, toReflAlpha) = when (flight.direction) {
        ArtFlightDirection.FORWARD -> COVER_REFLECTION_ALPHA to REFLECTION_ALPHA
        ArtFlightDirection.REVERSE -> REFLECTION_ALPHA to COVER_REFLECTION_ALPHA
    }
    val (fromMidAlpha, toMidAlpha) = when (flight.direction) {
        ArtFlightDirection.FORWARD -> COVER_REFLECTION_MID_ALPHA to REFLECTION_MID_ALPHA
        ArtFlightDirection.REVERSE -> REFLECTION_MID_ALPHA to COVER_REFLECTION_MID_ALPHA
    }
    val (fromFarAlpha, toFarAlpha) = when (flight.direction) {
        ArtFlightDirection.FORWARD -> COVER_REFLECTION_FAR_ALPHA to REFLECTION_FAR_ALPHA
        ArtFlightDirection.REVERSE -> REFLECTION_FAR_ALPHA to COVER_REFLECTION_FAR_ALPHA
    }
    // Both ends scale and turn about the ART's centre (the tile's and Now Playing's transform
    // origin), so the flight lerps that centre and lays the box out around it.
    // NB: artRect.height may be > artRect.width (art + reflection); the ART's centre is at width/2
    // from the top, not height/2 — using center.y would place the take-off ~21% of the art side
    // too low on a reverse flight. from.width == from.width for a square rect (forward), so this
    // is bit-identical to from.center on the forward path.
    val fromCentreX = from.left + from.width / 2f
    val fromCentreY = from.top + from.width / 2f
    val toCentreX = toRect.left + toRect.width / 2f
    val toCentreY = toRect.top + toRect.width / 2f

    // Pose at this progress: position, size, tilt, reflection — every one a lerp between the
    // take-off and the landing.
    val side = lerp(from.width, toSide, p)
    val centreX = lerp(fromCentreX, toCentreX, p) - containerOrigin.value.x
    val centreY = lerp(fromCentreY, toCentreY, p) - containerOrigin.value.y
    val left = centreX - side / 2f
    val top = centreY - side / 2f
    val extraScale = lerp(flight.from.scale, toScale, p)
    val rotation = lerp(flight.from.rotationY, toRotation, p)
    val camera = lerp(fromCamera, toCamera, p)
    val reflectionFraction = lerp(fromReflFrac, toReflFrac, p)
    val reflectionAlpha = lerp(fromReflAlpha, toReflAlpha, p)
    val midAlpha = lerp(fromMidAlpha, toMidAlpha, p)
    val farAlpha = lerp(fromFarAlpha, toFarAlpha, p)
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

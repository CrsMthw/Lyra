package com.crsmthw.lyra.ui.ipod.lcd

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.crsmthw.lyra.R
import kotlinx.coroutines.delay
import com.crsmthw.lyra.ui.ipod.IPodColors
import com.crsmthw.lyra.ui.ipod.IPodDimens
import com.crsmthw.lyra.ui.ipod.IPodFontFamily
import com.crsmthw.lyra.ui.ipod.nav.IPodScreen
import com.crsmthw.lyra.ui.ipod.nav.IPodStackEntry
import com.crsmthw.lyra.ui.ipod.nav.IPodUiState
import com.crsmthw.lyra.ui.ipod.nav.LcdItem
import com.crsmthw.lyra.ui.ipod.nav.LcdLabel
import com.crsmthw.lyra.ui.ipod.nav.LcdIndexStatus
import com.crsmthw.lyra.ui.ipod.nav.LcdNavDirection

// ── Transition key ──────────────────────────────────────────────────────────

/**
 * Stable identity for the [AnimatedContent] driving the LCD slide. Keyed on the stack depth and
 * the screen object, NOT on the entry itself -- so a list update or a progress tick does not
 * restart the slide animation.
 */
private data class LcdContentKey(val depth: Int, val screen: IPodScreen)

/**
 * Holds the last resolved [IPodStackEntry] for a given content key during a transition. Assigned
 * during composition (not from an effect) so the outgoing child sees its own entry, not the
 * incoming screen's.
 */
private class EntryHolder(var value: IPodStackEntry)

/**
 * Plain (non-snapshot) bookkeeping for the Cover Flow ↔ Now Playing flight, assigned during
 * composition / layout: the last content key (to detect the push/pop), the centre cover's latest
 * take-off rect, and the next flight id.
 */
private class FlightBookkeeping {
    var lastKey: LcdContentKey? = null
    var geometry: CoverFlowGeometry? = null
    var nextId = 0
    /** Mirrors `flight != null` so the detection block never READS the snapshot state it may write. */
    var active = false
    /** Now Playing's art rect in root coordinates, reported by [LcdNowPlayingContent.onArtBounds]. */
    var artRect: Rect? = null
    /** True when the transitionSpec should use a crossfade for the current NowPlaying→CoverFlow pop. */
    var useReverseCrossfade = false
}

// ── LcdScreen ───────────────────────────────────────────────────────────────

/**
 * The iPod's LCD: bezel + status bar (title, play/pause glyph, battery) + the current stack
 * entry, which slides in from the right on a push and out to the left on a pop
 * ([IPodUiState.direction]). Lists are display-only -- no touch scrolling, the highlight is
 * moved by the wheel -- and an empty list reads "No <Title>" as the Classic does.
 *
 * The caller (IPodRoot) sizes it 4:3 INCLUDING the bezel.
 */
@Composable
fun LcdScreen(
    state: IPodUiState,
    battery: BatteryState,
    modifier: Modifier = Modifier,
) {
    val accessibilityDesc = stringResource(R.string.ipod_cd_lcd)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(IPodDimens.LcdCornerRadius))
            .border(
                width = IPodDimens.LcdBezelWidth,
                color = IPodColors.LcdBezel,
                shape = RoundedCornerShape(IPodDimens.LcdCornerRadius),
            )
            .semantics { contentDescription = accessibilityDesc },
    ) {
        // Bezel highlight: a thin lighter line at the very top of the bezel.
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(IPodColors.LcdBezelHighlight),
        )

        // The inner LCD panel.
        val bezel = IPodDimens.LcdBezelWidth
        Box(
            Modifier
                .padding(bezel)
                .fillMaxSize()
                .clip(RoundedCornerShape(IPodDimens.LcdCornerRadius / 2))
                .background(IPodColors.LcdBackground),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val panelHeight = constraints.maxHeight
                val panelWidth = constraints.maxWidth
                val density = LocalDensity.current
                val statusBarHeight = (panelHeight * STATUS_BAR_FRACTION).toInt()
                val contentHeight = panelHeight - statusBarHeight

                // Status bar -- reads only title, isPlaying flag, battery.
                val titleText = state.current.title
                val isPlaying = state.nowPlaying?.isPlaying
                LcdStatusBar(
                    title = titleText,
                    isPlaying = isPlaying,
                    battery = battery,
                    heightPx = statusBarHeight,
                    widthPx = panelWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { statusBarHeight.toDp() }),
                )

                // Content area below the status bar.
                val contentHeightDp = with(density) { contentHeight.toDp() }
                // ── Cover Flow ↔ Now Playing flight state (see LcdArtFlight.kt) ──
                val bookkeeping = remember { FlightBookkeeping() }
                var flight by remember { mutableStateOf<ArtFlight?>(null) }
                /** The landing pose: Now Playing's art (forward) or a Cover Flow tile (reverse). */
                val flightTarget = remember { mutableStateOf<CoverPose?>(null) }
                val contentOrigin = remember { mutableStateOf(Offset.Zero) }
                /** True once the cover has landed: the real art shows while the overlay fades out over it. */
                val flightLanded = remember { mutableStateOf(false) }
                Box(
                    Modifier
                        .padding(top = with(density) { statusBarHeight.toDp() })
                        .fillMaxWidth()
                        .height(contentHeightDp)
                        .onGloballyPositioned { contentOrigin.value = it.positionInRoot() },
                ) {
                    val direction = state.direction
                    val currentKey = LcdContentKey(
                        depth = state.stack.size,
                        screen = state.current.screen,
                    )

                    // ── Flight detection (composition-phase, no snapshot reads that may write) ──
                    //
                    // Forward (SELECT on Cover Flow pushes Now Playing): the centre cover flies
                    // into Now Playing's art slot.
                    // Reverse (MENU from Now Playing pops to Cover Flow, same song still playing):
                    // the art flies back into the ribbon's selected slot.
                    val prevKey = bookkeeping.lastKey
                    if (prevKey != null && prevKey != currentKey) {
                        val coverToNowPlaying = prevKey.screen is IPodScreen.CoverFlow &&
                            currentKey.screen is IPodScreen.NowPlaying &&
                            currentKey.depth == prevKey.depth + 1 &&
                            direction == LcdNavDirection.FORWARD
                        val nowPlayingToCoverFlow = prevKey.screen is IPodScreen.NowPlaying &&
                            currentKey.screen is IPodScreen.CoverFlow &&
                            currentKey.depth == prevKey.depth - 1 &&
                            direction == LcdNavDirection.BACK

                        when {
                            coverToNowPlaying -> {
                                // The SELECTED cover — the detent's index, which the ribbon may still be
                                // gliding toward — at the pose it is drawn in right now.
                                val coverEntry = state.stack.getOrNull(prevKey.depth - 1)
                                val coverIndex = coverEntry?.list?.selectedIndex
                                val geometry = bookkeeping.geometry
                                val artUrl = coverEntry?.list?.items?.getOrNull(coverIndex ?: -1)?.artUrl
                                    ?.takeIf { it.isNotBlank() }
                                    ?: state.nowPlaying?.artUrl?.takeIf { it.isNotBlank() }
                                if (geometry != null && coverIndex != null && !artUrl.isNullOrBlank()) {
                                    flightTarget.value = null
                                    flightLanded.value = false
                                    bookkeeping.useReverseCrossfade = false
                                    flight = ArtFlight(
                                        id = bookkeeping.nextId++,
                                        artUrl = artUrl,
                                        coverIndex = coverIndex,
                                        from = geometry.poseOf(coverIndex),
                                        direction = ArtFlightDirection.FORWARD,
                                    )
                                    bookkeeping.active = true
                                }
                            }
                            nowPlayingToCoverFlow && !bookkeeping.active -> {
                                // Reverse flight: only if the same song is still playing.
                                val currentEntry = state.current // the Cover Flow entry we're popping to
                                val coverIndex = currentEntry.list.selectedIndex
                                val coverItem = currentEntry.list.items.getOrNull(coverIndex)
                                // Gate on the LIVE Cover Flow item, not the snapshotted uri from
                                // the forward push: a reconcile prepend can shift the highlight, and
                                // the landing is geo.poseOf(selectedIndex) — both must agree.
                                val sameSong = state.nowPlaying?.uri != null &&
                                    state.nowPlaying.uri == coverItem?.id
                                val artRect = bookkeeping.artRect
                                val artUrl = coverItem?.artUrl?.takeIf { it.isNotBlank() }
                                    ?: state.nowPlaying?.artUrl?.takeIf { it.isNotBlank() }
                                if (sameSong && artRect != null && coverItem != null && !artUrl.isNullOrBlank()) {
                                    flightTarget.value = null
                                    flightLanded.value = false
                                    bookkeeping.useReverseCrossfade = true
                                    flight = ArtFlight(
                                        id = bookkeeping.nextId++,
                                        artUrl = artUrl,
                                        coverIndex = coverIndex,
                                        from = CoverPose(artRect, 1f, ART_ROTATION_Y),
                                        direction = ArtFlightDirection.REVERSE,
                                    )
                                    bookkeeping.active = true
                                } else {
                                    // Song changed — ordinary slide.
                                    bookkeeping.useReverseCrossfade = false
                                }
                            }
                            bookkeeping.active && currentKey.screen !is IPodScreen.NowPlaying -> {
                                // Left Now Playing mid-flight (MENU during the 320 ms): drop the overlay.
                                flight = null
                                flightLanded.value = false
                                bookkeeping.active = false
                                bookkeeping.useReverseCrossfade = false
                            }
                        }
                    }
                    bookkeeping.lastKey = currentKey

                    // FRESH animation values per flight, created in the composition that starts it.
                    // They used to be remembered once and reset inside the LaunchedEffect below — which
                    // runs AFTER the first frame is drawn — so every flight after the first drew its
                    // first frame with the previous flight's end state: progress 1, overlay alpha 0,
                    // and the tile already hidden. Cris's recording showed it: one pure-white frame
                    // where the cover should be, on every press but the first.
                    val flightProgress = remember(flight?.id) { Animatable(0f) }
                    val overlayAlpha = remember(flight?.id) { Animatable(1f) }

                    LaunchedEffect(flight?.id) {
                        val f = flight ?: return@LaunchedEffect
                        flightProgress.animateTo(1f, tween(ART_FLIGHT_MILLIS, easing = FastOutSlowInEasing))
                        // Landed: show the real art and fade the overlay out over it (same rect,
                        // tilt and mask — only the decode's sharpness differs).
                        if (flight?.id == f.id) flightLanded.value = true
                        overlayAlpha.animateTo(0f, tween(ART_LANDING_FADE_MILLIS))
                        if (flight?.id == f.id) {
                            flight = null
                            flightLanded.value = false
                            bookkeeping.active = false
                            bookkeeping.useReverseCrossfade = false
                        }
                    }

                    AnimatedContent(
                        targetState = currentKey,
                        transitionSpec = {
                            val millis = IPodDimens.LcdSlideMillis
                            // Cover Flow → Now Playing: no slide — the cover flies (the overlay) and
                            // everything else crossfades under it, over the flight's duration.
                            val coverToNowPlaying = initialState.screen is IPodScreen.CoverFlow &&
                                targetState.screen is IPodScreen.NowPlaying &&
                                direction == LcdNavDirection.FORWARD
                            // Now Playing → Cover Flow (reverse flight): same crossfade, no slide —
                            // gated on the flag the detection block set, so a song-changed pop still
                            // slides normally.
                            val nowPlayingToCoverFlow = initialState.screen is IPodScreen.NowPlaying &&
                                targetState.screen is IPodScreen.CoverFlow &&
                                direction == LcdNavDirection.BACK &&
                                bookkeeping.useReverseCrossfade
                            when {
                                coverToNowPlaying || nowPlayingToCoverFlow ->
                                    fadeIn(tween(ART_FLIGHT_MILLIS)) togetherWith
                                        fadeOut(tween(ART_FLIGHT_MILLIS)) using
                                        null as SizeTransform?

                                direction == LcdNavDirection.FORWARD ->
                                    slideInHorizontally(tween(millis)) { it } togetherWith
                                        slideOutHorizontally(tween(millis)) { -it } using
                                        null as SizeTransform?

                                direction == LcdNavDirection.BACK ->
                                    slideInHorizontally(tween(millis)) { -it } togetherWith
                                        slideOutHorizontally(tween(millis)) { it } using
                                        null as SizeTransform?

                                else ->
                                    EnterTransition.None togetherWith
                                        ExitTransition.None using
                                        null as SizeTransform?
                            }
                        },
                        contentKey = { it },
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(bottomStart = IPodDimens.LcdCornerRadius / 2, bottomEnd = IPodDimens.LcdCornerRadius / 2)),
                        label = "lcd_content",
                    ) { animatingKey ->
                        // Resolve the entry for THIS child's key, holding the last valid one
                        // so the outgoing pane renders its own content during the slide.
                        val liveEntry = state.stack.getOrNull(animatingKey.depth - 1)
                            ?.takeIf { it.screen == animatingKey.screen }
                        val holder = remember(animatingKey) { EntryHolder(liveEntry ?: state.current) }
                        if (liveEntry != null) holder.value = liveEntry
                        val entry = holder.value

                        // Direction-aware hiding:
                        // Forward: Now Playing's art hidden during the flight until landed; tile hidden.
                        // Reverse: Now Playing's art hidden for the whole flight INCLUDING the landing
                        //   fade (the element that appears at landing is the tile); tile hidden only
                        //   until landed, then the overlay fades over the real tile.
                        val currentFlight = flight
                        val isForward = currentFlight?.direction == ArtFlightDirection.FORWARD
                        val isReverse = currentFlight?.direction == ArtFlightDirection.REVERSE
                        val hideNowPlayingArt = when {
                            currentFlight == null -> false
                            isForward -> !flightLanded.value
                            // Reverse: hide art for the WHOLE flight + landing fade.
                            isReverse -> true
                            else -> false
                        }
                        val hideCoverFlowTile = when {
                            currentFlight == null -> null
                            isForward -> currentFlight.coverIndex
                            // Reverse: hide the tile until landed, then the overlay fades over it.
                            isReverse && !flightLanded.value -> currentFlight.coverIndex
                            else -> null
                        }

                        when (entry.screen) {
                            is IPodScreen.NowPlaying -> LcdNowPlayingContent(
                                nowPlaying = state.nowPlaying,
                                contentHeight = contentHeightDp,
                                hideArt = hideNowPlayingArt,
                                onArtBounds = { rect ->
                                    // Forward: this is the landing target.
                                    if (isForward || currentFlight == null) {
                                        flightTarget.value = CoverPose(rect, 1f, ART_ROTATION_Y)
                                    }
                                    // Always track for reverse take-off (non-snapshot bookkeeping).
                                    bookkeeping.artRect = rect
                                },
                            )
                            is IPodScreen.CoverFlow -> LcdCoverFlowContent(
                                entry = entry,
                                likedIndex = state.likedIndex,
                                contentHeight = contentHeightDp,
                                hiddenIndex = hideCoverFlowTile,
                                onGeometry = { geo ->
                                    bookkeeping.geometry = geo
                                    // Reverse: the incoming Cover Flow reports its geometry; use the
                                    // selected tile's live pose as the landing target.
                                    if (isReverse) {
                                        flightTarget.value = geo.poseOf(currentFlight!!.coverIndex)
                                    }
                                },
                            )
                            else -> LcdMenuList(
                                entry = entry,
                                contentHeight = contentHeightDp,
                            )
                        }
                    }

                    // The flying cover, above both screens, clipped with the content.
                    flight?.let { f ->
                        ArtFlightOverlay(
                            flight = f,
                            target = flightTarget,
                            containerOrigin = contentOrigin,
                            progress = flightProgress,
                            overlayAlpha = overlayAlpha,
                            landed = flightLanded,
                        )
                    }
                }
            }
        }
    }
}

private const val STATUS_BAR_FRACTION = 0.12f

// ── Status Bar ──────────────────────────────────────────────────────────────

@Composable
private fun LcdStatusBar(
    title: LcdLabel,
    isPlaying: Boolean?,
    battery: BatteryState,
    heightPx: Int,
    widthPx: Int,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val resolvedTitle = title.resolve()

    // Pre-compute text sizes from the panel height so the LCD scales.
    val titleFontSize = with(density) { (heightPx * 0.55f).toSp() }
    val titleStyle = TextStyle(
        fontFamily = IPodFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = titleFontSize,
        color = IPodColors.LcdText,
    )

    Canvas(modifier = modifier) {
        // Glossy background: white at the top through a pale mid to grey — the Classic's aqua bar.
        drawRect(
            brush = Brush.verticalGradient(
                0f to IPodColors.LcdStatusGlossTop,
                0.5f to IPodColors.LcdStatusGlossMid,
                1f to IPodColors.LcdStatusGlossLow,
            ),
        )
        // 1px divider line at the bottom.
        drawLine(
            color = IPodColors.LcdStatusLine,
            start = Offset(0f, size.height - 1f),
            end = Offset(size.width, size.height - 1f),
            strokeWidth = 1f,
        )

        // Centre: title. (The pre-split-screen Classic firmware centres it; the later left-aligned
        // bar belongs to the split-screen menus, which this is not.)
        val titleLayout = textMeasurer.measure(
            text = resolvedTitle,
            style = titleStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            constraints = androidx.compose.ui.unit.Constraints(maxWidth = (widthPx * 0.6f).toInt()),
        )
        drawText(
            textLayoutResult = titleLayout,
            topLeft = Offset(
                x = (size.width - titleLayout.size.width) / 2f,
                y = (size.height - 1f - titleLayout.size.height) / 2f,
            ),
        )

        // Right: glossy battery.
        val batteryHeight = heightPx * 0.42f
        drawBattery(
            right = size.width - heightPx * 0.35f,
            centerY = (size.height - 1f) / 2f,
            height = batteryHeight,
            battery = battery,
        )

        // Left: the blue play/pause glyph.
        if (isPlaying != null) {
            val glyphSize = heightPx * 0.36f
            val glyphLeft = heightPx * 0.35f
            val glyphTop = (size.height - 1f - glyphSize) / 2f
            val glyphBrush = Brush.verticalGradient(
                colors = listOf(IPodColors.PlayGlyphTop, IPodColors.PlayGlyphBottom),
                startY = glyphTop,
                endY = glyphTop + glyphSize,
            )
            if (isPlaying) {
                drawPlayTriangle(glyphLeft, glyphTop, glyphSize, glyphBrush)
            } else {
                drawPauseBars(glyphLeft, glyphTop, glyphSize, glyphBrush)
            }
        }
    }
}

private fun DrawScope.drawPlayTriangle(left: Float, top: Float, size: Float, brush: Brush) {
    val path = Path().apply {
        moveTo(left, top)
        lineTo(left + size, top + size / 2f)
        lineTo(left, top + size)
        close()
    }
    drawPath(path, brush, style = Fill)
}

private fun DrawScope.drawPauseBars(left: Float, top: Float, size: Float, brush: Brush) {
    val barWidth = size * 0.3f
    val gap = size * 0.2f
    drawRect(brush, Offset(left, top), Size(barWidth, size))
    drawRect(brush, Offset(left + barWidth + gap, top), Size(barWidth, size))
}

private fun DrawScope.drawBattery(
    right: Float,
    centerY: Float,
    height: Float,
    battery: BatteryState,
) {
    val bodyWidth = height * 1.8f
    val bodyHeight = height
    val nubWidth = height * 0.12f
    val nubHeight = height * 0.4f
    val borderWidth = height * 0.08f

    val bodyLeft = right - bodyWidth - nubWidth
    val bodyTop = centerY - bodyHeight / 2f

    // Body outline.
    drawRoundRect(
        color = IPodColors.BatteryOutline,
        topLeft = Offset(bodyLeft, bodyTop),
        size = Size(bodyWidth, bodyHeight),
        cornerRadius = CornerRadius(height * 0.12f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(borderWidth),
    )

    // Nub.
    drawRoundRect(
        color = IPodColors.BatteryOutline,
        topLeft = Offset(bodyLeft + bodyWidth, centerY - nubHeight / 2f),
        size = Size(nubWidth, nubHeight),
        cornerRadius = CornerRadius(nubWidth * 0.3f),
    )

    // Fill.
    val inset = borderWidth * 1.5f
    val fillMaxWidth = bodyWidth - inset * 2f
    val fillWidth = fillMaxWidth * (battery.percent / 100f)
    val fillTop = bodyTop + inset
    val fillHeight = bodyHeight - inset * 2f
    if (fillWidth > 0f) {
        // Glossy fill: pale at the top through the body colour, plus a white sheen on the upper half.
        val (top, bottom) = if (battery.isCharging) {
            IPodColors.ProgressGlassTop to IPodColors.BatteryCharging
        } else {
            IPodColors.BatteryGreenTop to IPodColors.BatteryGreenBottom
        }
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(top, bottom),
                startY = fillTop,
                endY = fillTop + fillHeight,
            ),
            topLeft = Offset(bodyLeft + inset, fillTop),
            size = Size(fillWidth, fillHeight),
            cornerRadius = CornerRadius(height * 0.06f),
        )
        drawRoundRect(
            brush = Brush.verticalGradient(
                0f to IPodColors.HighlightText.copy(alpha = 0.55f),
                1f to IPodColors.HighlightText.copy(alpha = 0f),
                startY = fillTop,
                endY = fillTop + fillHeight * 0.5f,
            ),
            topLeft = Offset(bodyLeft + inset, fillTop),
            size = Size(fillWidth, fillHeight * 0.5f),
            cornerRadius = CornerRadius(height * 0.06f),
        )
    }

    // Bolt icon when charging.
    if (battery.isCharging) {
        val boltHeight = bodyHeight * 0.6f
        val boltWidth = boltHeight * 0.5f
        val boltCx = bodyLeft + bodyWidth / 2f
        val boltCy = centerY
        val path = Path().apply {
            moveTo(boltCx + boltWidth * 0.1f, boltCy - boltHeight / 2f)
            lineTo(boltCx - boltWidth * 0.3f, boltCy + boltHeight * 0.05f)
            lineTo(boltCx + boltWidth * 0.05f, boltCy + boltHeight * 0.05f)
            lineTo(boltCx - boltWidth * 0.1f, boltCy + boltHeight / 2f)
            lineTo(boltCx + boltWidth * 0.3f, boltCy - boltHeight * 0.05f)
            lineTo(boltCx - boltWidth * 0.05f, boltCy - boltHeight * 0.05f)
            close()
        }
        drawPath(path, IPodColors.BatteryBolt, style = Fill)
    }
}

// ── Menu list ───────────────────────────────────────────────────────────────

@Composable
internal fun LcdMenuList(
    entry: IPodStackEntry,
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
        else -> LcdItemList(
            items = items,
            selectedIndex = list.selectedIndex,
            firstVisibleIndex = list.firstVisibleIndex,
            visibleRows = list.visibleRows,
            contentHeight = contentHeight,
        )
    }
}

@Composable
internal fun LcdCentredMessage(text: String, contentHeight: Dp) {
    val density = LocalDensity.current
    val fontSize = with(density) { (contentHeight.toPx() * 0.06f).toSp() }
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = text,
            fontFamily = IPodFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            color = IPodColors.LcdTextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A pure-function list: draws exactly the rows in [firstVisibleIndex, firstVisibleIndex + visibleRows)
 * with the highlight on [selectedIndex]. No LazyColumn, no scroll state, no effects -- the highlight
 * and the rows move in the SAME frame because both come from the same snapshot of [LcdListState].
 */
@Composable
private fun LcdItemList(
    items: List<LcdItem>,
    selectedIndex: Int,
    firstVisibleIndex: Int,
    visibleRows: Int,
    contentHeight: Dp,
) {
    val density = LocalDensity.current
    val contentHeightPx = with(density) { contentHeight.toPx() }
    val hasSubtitles = remember(items) { items.any { it.subtitle != null } }
    val rowHeightPx = contentHeightPx / visibleRows
    val rowHeight = with(density) { rowHeightPx.toDp() }

    val endIndex = minOf(firstVisibleIndex + visibleRows, items.size)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            for (i in firstVisibleIndex until endIndex) {
                LcdRow(
                    item = items[i],
                    isHighlighted = i == selectedIndex,
                    rowHeight = rowHeight,
                    hasSubtitles = hasSubtitles,
                    contentHeightPx = contentHeightPx,
                )
            }
            // Blank space below when fewer rows than the visible window.
            val drawn = endIndex - firstVisibleIndex
            if (drawn < visibleRows) {
                Spacer(Modifier.weight(1f))
            }
        }

        // Scrollbar -- only when items exceed visible rows.
        if (items.size > visibleRows) {
            LcdScrollbar(
                firstVisibleIndex = firstVisibleIndex,
                selectedIndex = selectedIndex,
                totalItems = items.size,
                visibleRows = visibleRows,
                contentHeightPx = contentHeightPx,
            )
        }
    }
}

@Composable
private fun LcdRow(
    item: LcdItem,
    isHighlighted: Boolean,
    rowHeight: Dp,
    hasSubtitles: Boolean,
    contentHeightPx: Float,
) {
    val density = LocalDensity.current
    val titleFontSize = with(density) { (contentHeightPx * 0.048f).toSp() }
    val subtitleFontSize = with(density) { (contentHeightPx * 0.038f).toSp() }
    val chevronFontSize = with(density) { (contentHeightPx * 0.04f).toSp() }
    val valueFontSize = with(density) { (contentHeightPx * 0.042f).toSp() }

    val textColor = if (isHighlighted) IPodColors.HighlightText else IPodColors.LcdText
    val secondaryColor = if (isHighlighted) IPodColors.HighlightText.copy(alpha = 0.8f) else IPodColors.LcdTextSecondary
    val chevronColor = if (isHighlighted) IPodColors.HighlightText else IPodColors.Chevron

    val bgModifier = if (isHighlighted) {
        Modifier.background(
            Brush.verticalGradient(
                colors = listOf(IPodColors.HighlightTop, IPodColors.HighlightBottom),
            ),
        )
    } else {
        Modifier
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .then(bgModifier),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = with(density) { (contentHeightPx * 0.03f).toDp() }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Title + subtitle column.
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = if (hasSubtitles) Arrangement.Center else Arrangement.Center,
            ) {
                androidx.compose.material3.Text(
                    text = item.title.resolve(),
                    fontFamily = IPodFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = titleFontSize,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.subtitle != null) {
                    androidx.compose.material3.Text(
                        text = item.subtitle.resolve(),
                        fontFamily = IPodFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = subtitleFontSize,
                        color = secondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Right side: value or chevron.
            if (item.value != null) {
                androidx.compose.material3.Text(
                    text = item.value.resolve(),
                    fontFamily = IPodFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = valueFontSize,
                    color = secondaryColor,
                    maxLines = 1,
                )
            }
            if (item.hasSubmenu) {
                Spacer(Modifier.width(4.dp))
                androidx.compose.material3.Text(
                    text = "›", // single right-pointing angle quotation mark
                    fontFamily = IPodFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = chevronFontSize,
                    color = chevronColor,
                )
            }
        }

        // Divider line between rows (not on highlighted row).
        if (!isHighlighted) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(IPodColors.LcdDivider),
            )
        }
    }
}

// ── Scrollbar ───────────────────────────────────────────────────────────────

/** How long the scrollbar stays after the wheel stops before it fades. */
private const val SCROLLBAR_HIDE_DELAY_MS = 1_500L
/** The scrollbar's fade-out. Finite, never a spring. */
private const val SCROLLBAR_FADE_MILLIS = 350
/** Thumb width as a fraction of the content height. */
private const val SCROLLBAR_WIDTH_FRACTION = 0.016f
/** The thumb's glide between positions — finite, retargeted per detent so a fast spin flows. */
private const val SCROLLBAR_SLIDE_MILLIS = 160

/**
 * A translucent black capsule at the right edge with no track (Cris, round D pass). It reads its
 * position from [firstVisibleIndex] (state), not from a LazyListState, so it tracks the window in
 * the same frame as the rows; it shows whenever the wheel moves the highlight or the window and
 * fades out [SCROLLBAR_HIDE_DELAY_MS] after the last detent. The alpha is read in the draw phase.
 */
@Composable
private fun LcdScrollbar(
    firstVisibleIndex: Int,
    selectedIndex: Int,
    totalItems: Int,
    visibleRows: Int,
    contentHeightPx: Float,
) {
    val density = LocalDensity.current
    val thumbWidthPx = contentHeightPx * SCROLLBAR_WIDTH_FRACTION
    val thumbWidth = with(density) { thumbWidthPx.toDp() }

    // Visible on every detent (a new window OR a new highlight), gone a moment after the last.
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(firstVisibleIndex, selectedIndex) {
        alpha.snapTo(1f)
        delay(SCROLLBAR_HIDE_DELAY_MS)
        alpha.animateTo(0f, tween(SCROLLBAR_FADE_MILLIS))
    }

    // The thumb GLIDES to each new window position instead of jumping a row at a time, as the
    // Classic's does (Cris, round D pass). The value is read in the draw phase only.
    val targetFraction = if (totalItems > visibleRows) {
        firstVisibleIndex.toFloat() / (totalItems - visibleRows)
    } else {
        0f
    }
    val position = remember { Animatable(targetFraction) }
    LaunchedEffect(targetFraction) {
        position.animateTo(targetFraction, tween(SCROLLBAR_SLIDE_MILLIS, easing = FastOutSlowInEasing))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(end = 2.dp, top = 2.dp, bottom = 2.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        Spacer(
            modifier = Modifier
                .width(thumbWidth)
                .fillMaxHeight()
                .drawBehind {
                    val thumbFraction = (visibleRows.toFloat() / totalItems).coerceIn(0.05f, 1f)
                    val thumbHeight = size.height * thumbFraction
                    val thumbTop = position.value.coerceIn(0f, 1f) * (size.height - thumbHeight)
                    drawRoundRect(
                        color = IPodColors.ScrollbarThumb,
                        topLeft = Offset(0f, thumbTop),
                        size = Size(size.width, thumbHeight),
                        cornerRadius = CornerRadius(size.width / 2f),
                        alpha = alpha.value,
                    )
                },
        )
    }
}

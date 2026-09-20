package com.crsmthw.lyra.ui.ipod.lcd

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
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
import com.crsmthw.lyra.ui.ipod.IPodColors
import com.crsmthw.lyra.ui.ipod.IPodDimens
import com.crsmthw.lyra.ui.ipod.IPodFontFamily
import com.crsmthw.lyra.ui.ipod.nav.IPodScreen
import com.crsmthw.lyra.ui.ipod.nav.IPodStackEntry
import com.crsmthw.lyra.ui.ipod.nav.IPodUiState
import com.crsmthw.lyra.ui.ipod.nav.LcdItem
import com.crsmthw.lyra.ui.ipod.nav.LcdLabel
import com.crsmthw.lyra.ui.ipod.nav.LcdListState
import com.crsmthw.lyra.ui.ipod.nav.LcdNavDirection
import com.crsmthw.lyra.ui.ipod.nav.LcdNowPlaying

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
    BoxWithConstraints(
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
                Box(
                    Modifier
                        .padding(top = with(density) { statusBarHeight.toDp() })
                        .fillMaxWidth()
                        .height(contentHeightDp),
                ) {
                    val direction = state.direction
                    val currentKey = LcdContentKey(
                        depth = state.stack.size,
                        screen = state.current.screen,
                    )

                    AnimatedContent(
                        targetState = currentKey,
                        transitionSpec = {
                            val millis = IPodDimens.LcdSlideMillis
                            when (direction) {
                                LcdNavDirection.FORWARD ->
                                    slideInHorizontally(tween(millis)) { it } togetherWith
                                        slideOutHorizontally(tween(millis)) { -it } using
                                        null as SizeTransform?

                                LcdNavDirection.BACK ->
                                    slideInHorizontally(tween(millis)) { -it } togetherWith
                                        slideOutHorizontally(tween(millis)) { it } using
                                        null as SizeTransform?

                                LcdNavDirection.NONE ->
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

                        when (entry.screen) {
                            is IPodScreen.NowPlaying -> LcdNowPlayingContent(
                                nowPlaying = state.nowPlaying,
                                contentHeight = contentHeightDp,
                            )
                            else -> LcdMenuList(
                                entry = entry,
                                contentHeight = contentHeightDp,
                            )
                        }
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
        // Gradient background.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(IPodColors.LcdStatusTop, IPodColors.LcdStatusBottom),
            ),
        )
        // 1px divider line at the bottom.
        drawLine(
            color = IPodColors.LcdStatusLine,
            start = Offset(0f, size.height - 1f),
            end = Offset(size.width, size.height - 1f),
            strokeWidth = 1f,
        )

        // Centre: title.
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

        // Left: play/pause indicator.
        if (isPlaying != null) {
            val glyphSize = heightPx * 0.35f
            val glyphLeft = heightPx * 0.3f
            val glyphTop = (size.height - 1f - glyphSize) / 2f
            if (isPlaying) {
                drawPlayTriangle(glyphLeft, glyphTop, glyphSize, IPodColors.LcdText)
            } else {
                drawPauseBars(glyphLeft, glyphTop, glyphSize, IPodColors.LcdText)
            }
        }

        // Right: battery.
        drawBattery(
            right = size.width - heightPx * 0.3f,
            centerY = (size.height - 1f) / 2f,
            height = heightPx * 0.4f,
            battery = battery,
        )
    }
}

private fun DrawScope.drawPlayTriangle(left: Float, top: Float, size: Float, color: Color) {
    val path = Path().apply {
        moveTo(left, top)
        lineTo(left + size, top + size / 2f)
        lineTo(left, top + size)
        close()
    }
    drawPath(path, color, style = Fill)
}

private fun DrawScope.drawPauseBars(left: Float, top: Float, size: Float, color: Color) {
    val barWidth = size * 0.3f
    val gap = size * 0.2f
    drawRect(color, Offset(left, top), Size(barWidth, size))
    drawRect(color, Offset(left + barWidth + gap, top), Size(barWidth, size))
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
        color = IPodColors.BatteryBody,
        topLeft = Offset(bodyLeft, bodyTop),
        size = Size(bodyWidth, bodyHeight),
        cornerRadius = CornerRadius(height * 0.12f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(borderWidth),
    )

    // Nub.
    drawRoundRect(
        color = IPodColors.BatteryBody,
        topLeft = Offset(bodyLeft + bodyWidth, centerY - nubHeight / 2f),
        size = Size(nubWidth, nubHeight),
        cornerRadius = CornerRadius(nubWidth * 0.3f),
    )

    // Fill.
    val inset = borderWidth * 1.5f
    val fillMaxWidth = bodyWidth - inset * 2f
    val fillWidth = fillMaxWidth * (battery.percent / 100f)
    val fillColor = if (battery.isCharging) IPodColors.BatteryCharging else IPodColors.BatteryFill
    if (fillWidth > 0f) {
        drawRoundRect(
            color = fillColor,
            topLeft = Offset(bodyLeft + inset, bodyTop + inset),
            size = Size(fillWidth, bodyHeight - inset * 2f),
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
        drawPath(path, Color.White, style = Fill)
    }
}

// ── Menu list ───────────────────────────────────────────────────────────────

@Composable
private fun LcdMenuList(
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
            contentHeight = contentHeight,
        )
    }
}

@Composable
private fun LcdCentredMessage(text: String, contentHeight: Dp) {
    val density = LocalDensity.current
    val fontSize = with(density) { (contentHeight.toPx() * 0.06f).toSp() }
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = text,
            fontFamily = IPodFontFamily,
            fontSize = fontSize,
            color = IPodColors.LcdTextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LcdItemList(
    items: List<LcdItem>,
    selectedIndex: Int,
    contentHeight: Dp,
) {
    val density = LocalDensity.current
    val contentHeightPx = with(density) { contentHeight.toPx() }
    val hasSubtitles = remember(items) { items.any { it.subtitle != null } }
    val visibleRows = if (hasSubtitles) VISIBLE_ROWS_TWO_LINE else VISIBLE_ROWS_SINGLE_LINE
    val rowHeightPx = contentHeightPx / visibleRows
    val rowHeight = with(density) { rowHeightPx.toDp() }

    val listState = rememberLazyListState()

    // Classic-style scroll: snap the highlight into view.
    LaunchedEffect(selectedIndex) {
        val first = listState.firstVisibleItemIndex
        if (selectedIndex >= first + visibleRows) {
            listState.scrollToItem(selectedIndex - visibleRows + 1)
        } else if (selectedIndex < first) {
            listState.scrollToItem(selectedIndex)
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            userScrollEnabled = false,
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(
                items = items,
                key = { _, item -> item.id },
            ) { index, item ->
                LcdRow(
                    item = item,
                    isHighlighted = index == selectedIndex,
                    rowHeight = rowHeight,
                    hasSubtitles = hasSubtitles,
                    contentHeightPx = contentHeightPx,
                )
            }
        }

        // Scrollbar -- only when items exceed visible rows.
        if (items.size > visibleRows) {
            LcdScrollbar(
                listState = listState,
                totalItems = items.size,
                visibleRows = visibleRows,
                contentHeightPx = contentHeightPx,
            )
        }
    }
}

private const val VISIBLE_ROWS_SINGLE_LINE = 9
private const val VISIBLE_ROWS_TWO_LINE = 6

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
                    fontWeight = FontWeight.Normal,
                    fontSize = titleFontSize,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.subtitle != null) {
                    androidx.compose.material3.Text(
                        text = item.subtitle.resolve(),
                        fontFamily = IPodFontFamily,
                        fontWeight = FontWeight.Normal,
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
                    fontSize = valueFontSize,
                    color = secondaryColor,
                    maxLines = 1,
                )
            }
            if (item.hasSubmenu) {
                Spacer(Modifier.width(4.dp))
                androidx.compose.material3.Text(
                    text = "›", // single right-pointing angle quotation mark (›)
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

@Composable
private fun LcdScrollbar(
    listState: androidx.compose.foundation.lazy.LazyListState,
    totalItems: Int,
    visibleRows: Int,
    contentHeightPx: Float,
) {
    val density = LocalDensity.current
    val trackWidth = with(density) { (contentHeightPx * 0.02f).toDp() }
    val trackWidthPx = contentHeightPx * 0.02f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(end = 1.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        // Draw scrollbar track + thumb in drawBehind so the firstVisibleItemIndex read is
        // a draw-phase read and cannot trigger a recomposition of the list itself.
        Spacer(
            modifier = Modifier
                .width(trackWidth)
                .height(with(density) { contentHeightPx.toDp() })
                .drawBehind {
                    // Track.
                    drawRoundRect(
                        color = IPodColors.ScrollbarTrack,
                        cornerRadius = CornerRadius(trackWidthPx / 2f),
                    )

                    // Thumb.
                    val thumbFraction = (visibleRows.toFloat() / totalItems).coerceIn(0.05f, 1f)
                    val thumbHeight = size.height * thumbFraction
                    val scrollFraction = if (totalItems > visibleRows) {
                        listState.firstVisibleItemIndex.toFloat() / (totalItems - visibleRows)
                    } else {
                        0f
                    }
                    val thumbTop = scrollFraction * (size.height - thumbHeight)

                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                IPodColors.ScrollbarThumbTop,
                                IPodColors.ScrollbarThumbBottom,
                            ),
                            startY = thumbTop,
                            endY = thumbTop + thumbHeight,
                        ),
                        topLeft = Offset(0f, thumbTop),
                        size = Size(size.width, thumbHeight),
                        cornerRadius = CornerRadius(trackWidthPx / 2f),
                    )
                },
        )
    }
}

// ── Now Playing placeholder ─────────────────────────────────────────────────

@Composable
private fun LcdNowPlayingContent(
    nowPlaying: LcdNowPlaying?,
    contentHeight: Dp,
) {
    val density = LocalDensity.current
    val contentHeightPx = with(density) { contentHeight.toPx() }
    val titleFontSize = with(density) { (contentHeightPx * 0.06f).toSp() }
    val bodyFontSize = with(density) { (contentHeightPx * 0.048f).toSp() }
    val stateFontSize = with(density) { (contentHeightPx * 0.042f).toSp() }

    if (nowPlaying == null) {
        LcdCentredMessage(
            text = stringResource(R.string.ipod_now_playing_empty),
            contentHeight = contentHeight,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = with(density) { (contentHeightPx * 0.05f).toDp() }),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Title (bold).
        androidx.compose.material3.Text(
            text = nowPlaying.title,
            fontFamily = IPodFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = titleFontSize,
            color = IPodColors.LcdText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        // Artist.
        androidx.compose.material3.Text(
            text = nowPlaying.artist,
            fontFamily = IPodFontFamily,
            fontSize = bodyFontSize,
            color = IPodColors.LcdTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(2.dp))
        // Album.
        androidx.compose.material3.Text(
            text = nowPlaying.album,
            fontFamily = IPodFontFamily,
            fontSize = bodyFontSize,
            color = IPodColors.LcdTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        // Play/pause state.
        val stateText = if (nowPlaying.isPlaying) {
            stringResource(R.string.ipod_now_playing_playing)
        } else {
            stringResource(R.string.ipod_now_playing_paused)
        }
        androidx.compose.material3.Text(
            text = stateText,
            fontFamily = IPodFontFamily,
            fontSize = stateFontSize,
            color = IPodColors.LcdTextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

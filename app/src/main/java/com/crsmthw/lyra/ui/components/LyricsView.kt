package com.crsmthw.lyra.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.crsmthw.lyra.util.LyricLine
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

@Composable
fun SyncedLyricsView(
    lines            : List<LyricLine>,
    currentLineIndex : Int,
    textColor        : Color,
    modifier         : Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    BoxWithConstraints(modifier = modifier) {
        val halfHeight   = maxHeight / 2
        val density      = LocalDensity.current
        val halfHeightPx = with(density) { halfHeight.roundToPx() }

        LaunchedEffect(currentLineIndex) {
            if (currentLineIndex < 0) return@LaunchedEffect
            try {
                // If the target line isn't visible yet, snap to it first so layoutInfo is populated.
                if (listState.layoutInfo.visibleItemsInfo.none { it.index == currentLineIndex }) {
                    listState.scrollToItem(currentLineIndex)
                }
                val info = listState.layoutInfo
                val item = info.visibleItemsInfo.firstOrNull { it.index == currentLineIndex }
                if (item != null) {
                    // viewportStartOffset is ≈ −halfHeightPx when contentPadding is active,
                    // so viewportCenter must include it — using viewportSize.height/2 alone is wrong.
                    val viewportCenter = info.viewportStartOffset + info.viewportSize.height / 2
                    val itemCenter     = item.offset + item.size / 2
                    listState.animateScrollBy((itemCenter - viewportCenter).toFloat())
                }
            } catch (_: CancellationException) { }
        }

        LazyColumn(
            state               = listState,
            contentPadding      = PaddingValues(vertical = halfHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier            = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(
                items = lines,
                key   = { index, _ -> index },
            ) { index, line ->
                if (line.text.isEmpty()) {
                    Spacer(Modifier.height(12.dp))
                } else {
                    val isActive  = index == currentLineIndex
                    val distance  = if (currentLineIndex >= 0) abs(index - currentLineIndex) else Int.MAX_VALUE
                    Text(
                        text      = line.text,
                        style     = when {
                            isActive      -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            distance == 1 -> MaterialTheme.typography.titleMedium
                            else          -> MaterialTheme.typography.bodyLarge
                        },
                        color     = textColor.copy(alpha = when {
                            isActive      -> 1f
                            distance == 1 -> 0.65f
                            distance == 2 -> 0.35f
                            else          -> 0.18f
                        }),
                        textAlign = TextAlign.Center,
                        modifier  = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun PlainLyricsView(
    text     : String,
    textColor: Color,
    modifier : Modifier = Modifier,
) {
    Box(
        modifier         = modifier.verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            text      = text,
            style     = MaterialTheme.typography.bodyLarge,
            color     = textColor.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
            modifier  = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
        )
    }
}

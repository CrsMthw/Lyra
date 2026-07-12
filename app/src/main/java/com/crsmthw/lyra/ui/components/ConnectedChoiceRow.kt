package com.crsmthw.lyra.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.crsmthw.lyra.util.press

/**
 * Connected single-choice picker (Library filter, Stats range, Settings theme/visualizer pickers)
 * built on the M3 Expressive [ButtonGroup].
 *
 * Segments are hand-rolled `customItem`s instead of `toggleableItem`: alpha22's `toggleableItem`
 * renders the selected segment's label off-centre once the group is stretched wide (unfolded
 * Library filter / Stats range picker), so each segment is a [ToggleButton] with an explicitly
 * centred full-width label. Everything else `toggleableItem` provided is kept: `weight(1f)`
 * segments, the inter-button press-squeeze (`animateWidth`, default compression limit — same as
 * `toggleableItem`'s), and a real overflow indicator (an empty overflow mis-measures in tight
 * layouts; see docs/MATERIAL3.md → ButtonGroup).
 *
 * The group is also width-capped at [maxWidth] and centred — segments on a wide unfolded pane
 * otherwise stretch to ~3× their folded width. (`widthIn` must precede `fillMaxWidth` in the
 * chain: the reverse order fixes the width first, making the cap a no-op.)
 *
 * Picking fires a `press` haptic; [onSelect] only fires on a genuine selection change.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> ConnectedChoiceRow(
    options : List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 420.dp,
) {
    val haptics = LocalHapticFeedback.current
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        ButtonGroup(
            overflowIndicator = { menuState -> ButtonGroupDefaults.OverflowIndicator(menuState) },
            modifier          = Modifier.widthIn(max = maxWidth).fillMaxWidth(),
        ) {
            options.forEach { (value, label) ->
                val checked = selected == value
                customItem(
                    buttonGroupContent = {
                        val interaction = remember { MutableInteractionSource() }
                        ToggleButton(
                            checked           = checked,
                            onCheckedChange   = { isChecked ->
                                if (isChecked && !checked) { haptics.press(); onSelect(value) }
                            },
                            modifier          = Modifier.weight(1f).animateWidth(interaction),
                            interactionSource = interaction,
                        ) {
                            Text(
                                text      = label,
                                maxLines  = 1,
                                softWrap  = false,
                                overflow  = TextOverflow.Visible,
                                textAlign = TextAlign.Center,
                                modifier  = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                    menuContent = { menuState ->
                        DropdownMenuItem(
                            text    = { Text(label) },
                            onClick = {
                                menuState.dismiss()
                                if (!checked) { haptics.press(); onSelect(value) }
                            },
                        )
                    },
                )
            }
        }
    }
}

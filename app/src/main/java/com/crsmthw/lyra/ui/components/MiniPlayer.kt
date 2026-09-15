package com.crsmthw.lyra.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionDefaults
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import com.crsmthw.lyra.util.confirm
import com.crsmthw.lyra.util.rememberMorphDiag
import com.crsmthw.lyra.util.screenTransitionSpec
import com.crsmthw.lyra.util.press
import androidx.compose.ui.Modifier
import kotlin.math.abs
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.crsmthw.lyra.R
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import com.crsmthw.lyra.util.rememberArtBoundsTransform

/** Non-snapshot cell for the last non-null track the bar showed — see its use site. */
private class LastTrackHolder(var value: SpotifyTrack?)

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun MiniPlayer(
    currentTrack          : SpotifyTrack?,
    isPlaying             : Boolean,
    progress              : Float,
    onPlayPause           : () -> Unit,
    onSkipNext            : () -> Unit,
    onExpand              : () -> Unit,
    modifier              : Modifier = Modifier,
    isWakingUp            : Boolean = false,
    /**
     * The bar's presence on screen, owned by [PlayerPanelHost]: a child of the one seekable
     * transition that owns BOTH floating surfaces, so a predictive-back GESTURE — off
     * `PlayerScreen`, or out of the pop-out panel — seeks it with the finger. `true` means "bar on
     * screen"; the host folds the route gate, the pop-out panel and "is there a track at all" into
     * that single Boolean, so this composable no longer decides its own visibility.
     */
    barTransition         : Transition<Boolean>,
    accentColor             : Color = Color.Unspecified,
    surfaceAccentColor      : Color = Color.Unspecified,
    // The AnimatedVisibility below — a CHILD of [barTransition] — is the AnimatedVisibilityScope for
    // BOTH registrations. The mini player is hosted outside every nav destination (see
    // PlayerPanelHost), so there is no AnimatedContentScope to borrow, and its own show/hide IS the
    // enter/exit that the nav-level "album-art" morph rides on. Only the SharedTransitionScopes
    // differ, and a key is matched per scope, so registering the same key in two of them from one
    // AnimatedVisibilityScope is fine.
    // Because that scope's transition is a DESCENDANT of a SEEKABLE one, the bounds animation this
    // art registers is seeked too: `Modifier.sharedElement` calls `sharedBoundsImpl(parentTransition
    // = animatedVisibilityScope.transition)`, which does `parentTransition.createChildTransition(key)`
    // (SharedTransitionScope.kt), and `SeekableTransitionState.seekTo` drives every descendant by
    // fraction (`seekToFraction()` -> `transition.seekAnimations(playTimeNanos)`, which recurses).
    sharedTransitionScope      : SharedTransitionScope? = null,
    // Each scope gets its own SharedContentConfig, so the caller can leave the modifier in place
    // for the composable's whole life and still say WHICH transitions the "album-art" element may
    // match across — see PlayerPanelHost's rememberMatchWhenConfig.
    sharedContentConfig        : SharedTransitionScope.SharedContentConfig = SharedTransitionDefaults.SharedContentConfig,
    // Secondary scope — used when both local (mini↔panel) and nav (mini↔PlayerScreen) are needed.
    navSharedTransitionScope   : SharedTransitionScope? = null,
    navSharedContentConfig     : SharedTransitionScope.SharedContentConfig = SharedTransitionDefaults.SharedContentConfig,
) {
    val haptics               = LocalHapticFeedback.current
    val resolvedAccent        = if (accentColor == Color.Unspecified) MaterialTheme.colorScheme.primary else accentColor
    val resolvedSurfaceAccent = if (surfaceAccentColor == Color.Unspecified) resolvedAccent else surfaceAccentColor
    val density    = LocalDensity.current
    val navBarPx   = WindowInsets.navigationBars.getBottom(density)
    val miniSlideSpec = screenTransitionSpec<IntOffset>()

    // The bar keeps rendering the LAST track it had while it slides away. Two reasons:
    //   1. `currentTrack` and the host's `hasTrack` gate are two separate collections of the same
    //      StateFlow and can be one frame out of step; a null here while the transition already
    //      targets "visible" would put a ZERO-SIZE "album-art" participant into a live nav morph.
    //   2. When playback ends the bar now slides out with its content instead of collapsing to
    //      nothing mid-exit.
    // Non-snapshot cell, assigned during composition — the same idiom as the Library's
    // `PaneStateHolder`: the scope that writes it also reads `currentTrack`, so it can never be
    // stale, and a MutableState write read in the same pass would schedule an extra recomposition.
    val lastTrack = remember { LastTrackHolder(currentTrack) }
    if (currentTrack != null) lastTrack.value = currentTrack
    val track = currentTrack ?: lastTrack.value

    barTransition.AnimatedVisibility(
        visible  = { it },
        modifier = modifier,
        enter    = slideInVertically(miniSlideSpec)  { it + navBarPx },
        exit     = slideOutVertically(miniSlideSpec) { it + navBarPx },
    ) {
        val effectiveScope: AnimatedVisibilityScope = this

        // Compose nothing at all unless the bar is genuinely part of the current or the target
        // state. `AnimatedVisibility` ALSO composes its content while its transition reports
        // `hasInitialValueAnimations` — and since the bar and the pop-out panel became two children
        // of ONE transition (see [PlayerSurface]), an INTERRUPTED change on the other half sets
        // that flag here too: `SeekableTransitionState.moveAnimationToInitialState()` hands the
        // interrupted animation to `Transition.setInitialAnimations`, which recurses into every
        // child transition. The bar would then compose in `PreEnter` and register a second,
        // non-target `"album-art"` participant in the local scope — the exact ambiguity that broke
        // the mini → panel morph in the first place. Not dead code: that interruption is device
        // report 48 (close the panel, then open Settings before it lands).
        if (!barTransition.currentState && !barTransition.targetState) return@AnimatedVisibility

        val shownTrack = track ?: return@AnimatedVisibility

        val shape   = RoundedCornerShape(20.dp)
        val bgColor = MaterialTheme.colorScheme.surfaceContainerHigh

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .shadow(elevation = 12.dp, shape = shape,
                    ambientColor = resolvedAccent.copy(alpha = 0.15f))
                .clip(shape)
                .background(bgColor)
                .background(resolvedAccent.copy(alpha = 0.10f))
                .clickable(onClick = { haptics.confirm(); onExpand() }),
        ) {
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Album art — chains up to two shared element scopes when both are provided.
                // `rememberMorphDiag` is TEMPORARY instrumentation (util/MorphDiag.kt): it logs
                // this copy's key, match state and placed bounds. NOTE for whoever reads the log:
                // on a NARROW/folded screen the "primary" slot IS the nav scope and there is no
                // secondary, so `mini/primary` is the participant that morphs with PlayerScreen.
                val artModifier = if (sharedTransitionScope != null) {
                    with(sharedTransitionScope) {
                        val artState = rememberSharedContentState("album-art", sharedContentConfig)
                        Modifier.sharedElement(
                            sharedContentState      = artState,
                            animatedVisibilityScope = effectiveScope,
                            boundsTransform         = rememberArtBoundsTransform(),
                        ).then(rememberMorphDiag("mini/primary", artState))
                    }
                } else Modifier
                val navArtModifier = if (navSharedTransitionScope != null) {
                    with(navSharedTransitionScope) {
                        val artState = rememberSharedContentState("album-art", navSharedContentConfig)
                        Modifier.sharedElement(
                            sharedContentState      = artState,
                            animatedVisibilityScope = effectiveScope,
                            boundsTransform         = rememberArtBoundsTransform(),
                        ).then(rememberMorphDiag("mini/nav", artState))
                    }
                } else Modifier
                // A pass-through layout modifier that re-measures this art after every settle of
                // the floating player surface — the same reader `PlayerCardContent` and
                // `PlayerScreen` carry, and for the same reason: the shared-element state machine
                // only re-reads its target bounds provider when a node holding the key is measured
                // again. Here it is INSURANCE — the bar is not provably the survivor of any
                // cancelled seek that had a partner, but the argument for that rests entirely on
                // `LocalPlayerRouteVisible` disabling its nav entry, so see
                // [LocalPlayerArtSettleCount]'s KDoc. Appended AFTER the shared modifiers so their
                // place at the head of the chain is unchanged; the invalidation lands on the same
                // LayoutNode either way.
                val artSettleMod = rememberArtSettleInvalidation()

                // This art is a shared-element participant: the same "album-art" element morphs into
                // the full player / pop-out panel, where it is drawn at ~600px. Two things conspired
                // to make that morph blurry:
                //   1. `thumbnailUrl` is Spotify's SMALLEST image (images.last() — 64px), while the
                //      player uses `artUrl` (images.first() — 640px). During the morph Compose renders
                //      ONE participant across the animating bounds, and on a pop the target is the
                //      mini player — so a 64px bitmap was being upscaled ~10x.
                //   2. Coil sizes a decode to the composable's own box, which here is 44.dp, so even
                //      the full-size URL would decode too small to survive the morph.
                // Hence: full-size URL AND an explicit natural-size decode. The extra memory is one
                // bitmap for the currently-playing track, which the full player loads anyway.
                val context = LocalContext.current
                val artRequest = remember(shownTrack.artUrl, shownTrack.thumbnailUrl) {
                    ImageRequest.Builder(context)
                        .data(shownTrack.artUrl.takeIf { it.isNotBlank() } ?: shownTrack.thumbnailUrl)
                        .size(Size.ORIGINAL)
                        .build()
                }
                AsyncImage(
                    model              = artRequest,
                    contentDescription = shownTrack.album?.name,
                    contentScale       = ContentScale.Crop,
                    modifier           = artModifier.then(navArtModifier).then(artSettleMod)
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text     = shownTrack.name,
                        style    = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text     = shownTrack.allArtists,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.width(4.dp))

                // Play/pause wrapped in CircularWavyProgressIndicator
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(52.dp)) {
                    val progressAnim = remember { Animatable(progress) }
                    LaunchedEffect(progress, isPlaying) {
                        if (!isPlaying || abs(progress - progressAnim.value) > 0.5f) {
                            progressAnim.snapTo(progress)
                        } else {
                            progressAnim.animateTo(progress, tween(1100, easing = LinearEasing))
                        }
                    }
                    if (isWakingUp) {
                        CircularWavyProgressIndicator(
                            modifier   = Modifier.size(52.dp),
                            color      = resolvedSurfaceAccent,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    } else {
                        CircularWavyProgressIndicator(
                            progress   = { progressAnim.value },
                            modifier   = Modifier.size(52.dp),
                            color      = resolvedSurfaceAccent,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            amplitude  = { p -> if (isPlaying) WavyProgressIndicatorDefaults.indicatorAmplitude(p) else 0f },
                        )
                    }
                    IconButton(
                        onClick  = { haptics.press(); onPlayPause() },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector        = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) stringResource(R.string.player_pause) else stringResource(R.string.player_play),
                            modifier           = Modifier.size(22.dp),
                        )
                    }
                }

                IconButton(
                    onClick  = { haptics.press(); onSkipNext() },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = stringResource(R.string.player_next),
                        modifier           = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

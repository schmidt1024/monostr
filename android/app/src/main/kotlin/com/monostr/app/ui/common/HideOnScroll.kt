package com.monostr.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import kotlin.math.roundToInt

/**
 * X: the header slides away while the list scrolls down and comes back as soon as it scrolls up.
 * The header takes the scroll first, so the content moves with the finger and never twice as fast.
 * Spec 2026-10-16 §2: the bottom bar follows the same finger, without taking any scroll.
 */
class HideOnScrollState {
    internal var heightPx by mutableFloatStateOf(0f)

    /** 0 = fully shown, -[heightPx] = hidden. */
    internal var offsetPx by mutableFloatStateOf(0f)

    /** The bottom bar's height without the navigation-bar inset; 0 on screens without [HideOnScrollFooter]. */
    internal var bottomHeightPx by mutableFloatStateOf(0f)

    /** 0 = fully shown, [bottomHeightPx] = hidden. */
    internal var bottomOffsetPx by mutableFloatStateOf(0f)

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // the bar slides with the finger but consumes nothing: the list's top edge does not move with it
            bottomOffsetPx = (bottomOffsetPx - available.y).coerceIn(0f, bottomHeightPx)
            val before = offsetPx
            offsetPx = (offsetPx + available.y).coerceIn(-heightPx, 0f)
            return Offset(0f, offsetPx - before)
        }
    }

    /** 0 = bottom bar fully shown … 1 = hidden; follows the finger like the bar (the feed's compose button fades with it). */
    val hiddenFraction: Float get() = if (bottomHeightPx > 0f) bottomOffsetPx / bottomHeightPx else 0f

    /** Brings header and bottom bar back, e.g. when the list jumps to the top or the current tab is tapped. */
    fun show() {
        offsetPx = 0f
        bottomOffsetPx = 0f
    }
}

@Composable
fun rememberHideOnScroll(): HideOnScrollState = remember { HideOnScrollState() }

/** Only a scroll up brings hidden bars back, so they are stuck once nothing can scroll (no list, or one that fits). */
internal fun barsStuck(hasList: Boolean, canScrollForward: Boolean, canScrollBackward: Boolean): Boolean =
    !hasList || (!canScrollForward && !canScrollBackward)

/**
 * Spec 2026-10-16 §2: brings header and bottom bar back whenever the content can no longer scroll (emptied,
 * replaced by a spinner or an empty state, too short), else the tabs would be out of reach. [hasList] = false
 * while [listState]'s list is not on screen; a change of [contentKey] (e.g. a new search mode) also shows them.
 */
@Composable
fun ShowBarsWhenStuck(state: HideOnScrollState, listState: LazyListState, hasList: Boolean = true, contentKey: Any? = null) {
    val stuck by remember(listState, hasList) {
        derivedStateOf { barsStuck(hasList, listState.canScrollForward, listState.canScrollBackward) }
    }
    LaunchedEffect(stuck) { if (stuck) state.show() }
    LaunchedEffect(contentKey) { state.show() }
}

/**
 * Wraps the header: it is laid out shorter by the hidden part, so the content below grows into the room.
 * The status bar area stays put and opaque; only the bar below it slides. Bars inside take no insets
 * of their own (`windowInsets = WindowInsets(0)`).
 */
@Composable
fun HideOnScrollHeader(state: HideOnScrollState, content: @Composable () -> Unit) {
    Box(
        Modifier
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .clipToBounds()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                state.heightPx = placeable.height.toFloat()
                val offset = state.offsetPx.coerceAtLeast(-state.heightPx).roundToInt()
                layout(placeable.width, placeable.height + offset) { placeable.place(0, offset) }
            }
            .testTag("hide-on-scroll-header"),
    ) { content() }
}

/**
 * Spec 2026-10-16 §2: wraps the bottom bar like [HideOnScrollHeader] wraps the header. It is laid out
 * shorter by the hidden part, so the Scaffold's content and FAB follow it down. The navigation-bar area
 * stays put and opaque; only the bar above it slides. The bar inside takes no insets of its own
 * (`MonostrBottomBar(windowInsets = WindowInsets(0))`).
 */
@Composable
fun HideOnScrollFooter(state: HideOnScrollState, content: @Composable () -> Unit) {
    Box(
        Modifier
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .clipToBounds()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                state.bottomHeightPx = placeable.height.toFloat()
                val hidden = state.bottomOffsetPx.coerceIn(0f, state.bottomHeightPx).roundToInt()
                // placed at the top of a shorter box: the bar's lower part leaves through the clip, it slides down
                layout(placeable.width, placeable.height - hidden) { placeable.place(0, 0) }
            }
            .testTag("hide-on-scroll-footer"),
    ) { content() }
}

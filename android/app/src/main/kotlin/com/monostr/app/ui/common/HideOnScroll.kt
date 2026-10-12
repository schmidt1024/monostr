package com.monostr.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
 */
class HideOnScrollState {
    internal var heightPx by mutableFloatStateOf(0f)

    /** 0 = fully shown, -[heightPx] = hidden. */
    internal var offsetPx by mutableFloatStateOf(0f)

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val before = offsetPx
            offsetPx = (offsetPx + available.y).coerceIn(-heightPx, 0f)
            return Offset(0f, offsetPx - before)
        }
    }

    /** Brings the header back, e.g. when the list jumps to the top. */
    fun show() {
        offsetPx = 0f
    }
}

@Composable
fun rememberHideOnScroll(): HideOnScrollState = remember { HideOnScrollState() }

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

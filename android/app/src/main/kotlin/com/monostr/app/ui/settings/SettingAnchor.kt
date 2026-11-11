package com.monostr.app.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import kotlinx.coroutines.delay

/** The setting a search result opened the current page at (null otherwise); provided by the page scaffold. */
val LocalSettingFocus = compositionLocalOf<String?> { null }

/** Called once the focused anchor has scrolled into view and flashed; the scaffold then provides no focus any more. */
val LocalSettingFocusDone = compositionLocalOf<() -> Unit> { {} }

/**
 * Marks a control as the anchor of the index entry [key]. When [key] is the [focus] it is scrolled into view once and
 * flashes (primary at 12 % fading out over about a second), then reports [LocalSettingFocusDone]; otherwise this is a no-op.
 */
fun Modifier.settingAnchor(key: String, focus: String?): Modifier {
    if (focus == null || key != focus) return this
    return composed {
        val requester = remember { BringIntoViewRequester() }
        val flash = remember { Animatable(0.12f) }
        val done = LocalSettingFocusDone.current
        LaunchedEffect(Unit) {
            delay(120) // lets lists that load a moment later settle before the scroll
            requester.bringIntoView()
            flash.animateTo(0f, tween(durationMillis = 1000))
            done()
        }
        val color = MaterialTheme.colorScheme.primary
        Modifier.bringIntoViewRequester(requester).drawBehind { drawRect(color.copy(alpha = flash.value)) }
    }
}

/** [settingAnchor] with the focus of the surrounding page. */
@Composable
fun Modifier.settingAnchor(key: String): Modifier = settingAnchor(key, LocalSettingFocus.current)

/** Wraps [content] in a full-width box that is the anchor of [key]. */
@Composable
fun SettingAnchored(key: String, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().settingAnchor(key)) { content() }
}

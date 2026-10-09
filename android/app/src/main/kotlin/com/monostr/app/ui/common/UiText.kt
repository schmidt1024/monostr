package com.monostr.app.ui.common

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * A text the UI resolves in the current locale (spec 3.2). Controllers hold these instead of
 * strings so that JVM tests need no Android context and a runtime language change reaches
 * states that are already on screen. [Plain] is for data from the network (relay URLs,
 * profile names), never for the app's own wording.
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plain(val text: String) : UiText
    /** Spec 9: a count with a word in the right plural form of the current locale. */
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = emptyList()) : UiText
}

fun uiText(@StringRes id: Int, vararg args: Any): UiText.Res = UiText.Res(id, args.toList())

fun pluralText(@PluralsRes id: Int, count: Int, vararg args: Any): UiText.Plural = UiText.Plural(id, count, args.toList())

@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Res -> if (args.isEmpty()) stringResource(id) else stringResource(id, *args.toTypedArray())
    is UiText.Plain -> text
    is UiText.Plural -> pluralStringResource(id, count, *args.toTypedArray())
}

/** For code without a composition: the worker's notifications. */
fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Res -> if (args.isEmpty()) context.getString(id) else context.getString(id, *args.toTypedArray())
    is UiText.Plain -> text
    is UiText.Plural -> context.resources.getQuantityString(id, count, *args.toTypedArray())
}

package com.monostr.app.ui.compose

import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Whether the clipboard holds a picture. Only the clip's description is looked at: that is no read of the
 * clipboard, so Android (12+) shows no "pasted" note while the composer is merely open. Checked again on
 * every resume (copied in another app) and on every change while monostr is in front.
 */
@Composable
internal fun rememberClipboardHasImage(): Boolean {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    var has by remember { mutableStateOf(clipboard.hasImage()) }
    DisposableEffect(clipboard) {
        val listener = ClipboardManager.OnPrimaryClipChangedListener { has = clipboard.hasImage() }
        clipboard.addPrimaryClipChangedListener(listener)
        onDispose { clipboard.removePrimaryClipChangedListener(listener) }
    }
    LifecycleResumeEffect(clipboard) {
        has = clipboard.hasImage()
        onPauseOrDispose {}
    }
    return has
}

private fun ClipboardManager?.hasImage(): Boolean = this?.primaryClipDescription?.hasMimeType("image/*") == true

/**
 * The pictures on the clipboard, read on the user's tap (the read grants monostr access to their
 * `content://` URIs). Another app writes the clipboard, so only another app's `content://` pictures are
 * taken: a `file://` URI or one of monostr's own authorities could point at monostr's private files,
 * which would then be uploaded for everyone to see. The type must come from the resolver, not the clip.
 */
internal fun clipboardImages(context: Context): List<Uri> {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return emptyList()
    return (0 until clip.itemCount)
        .mapNotNull { clip.getItemAt(it).uri }
        .filter { uri ->
            uri.scheme == ContentResolver.SCHEME_CONTENT &&
                uri.authority?.startsWith(context.packageName) != true &&
                context.contentResolver.getType(uri)?.startsWith("image/") == true
        }
}

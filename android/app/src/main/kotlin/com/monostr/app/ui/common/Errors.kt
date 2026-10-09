package com.monostr.app.ui.common

import com.monostr.app.R
import com.monostr.app.media.UploadException
import com.monostr.nostr.NOTE_NOT_IN_DATABASE
import com.monostr.nostr.SigningRejectedException
import com.monostr.nostr.repo.FollowError
import com.monostr.tips.watcher.WatcherException
import kotlinx.coroutines.CancellationException

/**
 * The fixed text shown for a failed action, as a resource id the UI resolves in the current
 * locale. Exception messages never reach the UI: they can carry raw UniFFI or relay text.
 * Cancellation is rethrown, never mapped.
 */
fun Throwable.userMessage(): UiText = when {
    this is CancellationException -> throw this
    this is SigningRejectedException -> uiText(R.string.error_signing_rejected)
    this is FollowError.NoList -> uiText(R.string.follow_no_list)
    this is WatcherException.Network -> uiText(R.string.error_watcher_unreachable)
    this is WatcherException.Http && status == 429 -> uiText(R.string.error_watcher_rate_limited)
    this is WatcherException.Http -> uiText(R.string.error_watcher_rejected)
    this is WatcherException.Protocol -> uiText(R.string.error_watcher_invalid)
    (this is IllegalArgumentException || this is IllegalStateException) && message == NOTE_NOT_IN_DATABASE -> uiText(R.string.error_note_unavailable)
    else -> uiText(R.string.error_send_failed)
}

/** Like [userMessage], for a failed picture upload (spec 5.5): one fixed text per kind of failure. */
fun Throwable.uploadMessage(): UiText = when (this) {
    is CancellationException -> throw this
    is SigningRejectedException -> uiText(R.string.upload_error_signing)
    is UploadException.Nsfw -> uiText(R.string.upload_error_nsfw)
    is UploadException.TooLarge -> uiText(R.string.upload_error_too_large)
    is UploadException.Quota -> uiText(R.string.upload_error_quota)
    is UploadException.Rate -> uiText(R.string.upload_error_rate)
    is UploadException.Type -> uiText(R.string.upload_error_type)
    is UploadException.Banned -> uiText(R.string.upload_error_banned)
    is UploadException.Auth -> uiText(R.string.upload_error_auth)
    is UploadException.Unavailable -> uiText(R.string.upload_error_unavailable)
    is UploadException.Network -> uiText(R.string.upload_error_network)
    is UploadException.Unreadable -> uiText(R.string.upload_error_unreadable)
    else -> uiText(R.string.upload_error_failed)
}

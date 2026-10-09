package com.monostr.app.ui
import com.monostr.app.ui.common.uiText
import com.monostr.app.R

import com.monostr.app.ui.common.UiText
import com.monostr.app.media.UploadException
import com.monostr.app.ui.common.uploadMessage
import com.monostr.app.ui.common.userMessage
import com.monostr.nostr.NOTE_NOT_IN_DATABASE
import com.monostr.nostr.SigningRejectedException
import com.monostr.nostr.repo.FollowError
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class ErrorsTest {
    @Test
    fun `maps exceptions to fixed resource texts without leaking their text`() {
        assertEquals(uiText(R.string.error_signing_rejected), SigningRejectedException().userMessage())
        assertEquals(uiText(R.string.follow_no_list), FollowError.NoList().userMessage())
        assertEquals(uiText(R.string.error_send_failed), FollowError.NotAccepted().userMessage())
        assertEquals(uiText(R.string.error_note_unavailable), IllegalArgumentException(NOTE_NOT_IN_DATABASE).userMessage())
        assertEquals(uiText(R.string.error_note_unavailable), IllegalStateException(NOTE_NOT_IN_DATABASE).userMessage())
        assertEquals(uiText(R.string.error_send_failed), IllegalStateException("nsec1secret in a message").userMessage())
        assertEquals(uiText(R.string.error_send_failed), RuntimeException("UnexpectedUniFFICallbackError(reason: …)").userMessage())
        assertTrue(IllegalStateException("nsec1secret").userMessage() is UiText.Res)
        assertThrows(CancellationException::class.java) { CancellationException("x").userMessage() }
    }

    @Test
    fun `upload failures get their own fixed texts`() {
        assertEquals(uiText(R.string.upload_error_nsfw), UploadException.Nsfw().uploadMessage())
        assertEquals(uiText(R.string.upload_error_too_large), UploadException.TooLarge().uploadMessage())
        assertEquals(uiText(R.string.upload_error_quota), UploadException.Quota().uploadMessage())
        assertEquals(uiText(R.string.upload_error_rate), UploadException.Rate().uploadMessage())
        assertEquals(uiText(R.string.upload_error_type), UploadException.Type().uploadMessage())
        assertEquals(uiText(R.string.upload_error_banned), UploadException.Banned().uploadMessage())
        assertEquals(uiText(R.string.upload_error_auth), UploadException.Auth().uploadMessage())
        assertEquals(uiText(R.string.upload_error_unavailable), UploadException.Unavailable().uploadMessage())
        assertEquals(uiText(R.string.upload_error_network), UploadException.Network(IOException("10.0.0.7 refused")).uploadMessage())
        assertEquals(uiText(R.string.upload_error_unreadable), UploadException.Unreadable().uploadMessage())
        assertEquals(uiText(R.string.upload_error_failed), UploadException.Rejected(402).uploadMessage())
        // a refused signature names the way out for Amber users
        assertEquals(uiText(R.string.upload_error_signing), SigningRejectedException().uploadMessage())
        assertEquals(uiText(R.string.upload_error_failed), IllegalStateException("raw text").uploadMessage())
        assertThrows(CancellationException::class.java) { CancellationException("x").uploadMessage() }
    }
}

package com.monostr.app.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import coil3.network.HttpException
import com.monostr.app.R
import com.monostr.app.ui.theme.Spacing

/** Why a picture (or a video's poster) did not load, as far as the placeholder tells it. */
enum class MediaFailure { BROKEN, TOR_BLOCKED }

object MediaFailures {
    /**
     * What hosts answer a Tor exit with when they refuse it (owner 2026-11-12, images.pexels.com: 403): 403 Forbidden
     * (Cloudflare and most CDNs), 429 (an exit shares its address with many users, rate limits hit it first) and 451.
     * Not 401 (a login, not the route), not 5xx (the host's own trouble).
     */
    private val TOR_REFUSALS = setOf(403, 429, 451)

    fun of(error: Throwable?, torActive: Boolean): MediaFailure {
        val code = (error as? HttpException)?.response?.code ?: return MediaFailure.BROKEN
        return if (torActive && code in TOR_REFUSALS) MediaFailure.TOR_BLOCKED else MediaFailure.BROKEN
    }
}

/** The broken-picture mark; with [MediaFailure.TOR_BLOCKED] a short line under it says the site refuses Tor. */
@Composable
fun MediaFailedMark(failure: MediaFailure, modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.outline
    if (failure == MediaFailure.BROKEN) {
        Icon(Icons.Filled.BrokenImage, contentDescription = stringResource(R.string.media_failed), tint = tint, modifier = modifier)
        return
    }
    Column(modifier.padding(Spacing.s), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        // the text says it: no second description on the icon
        Icon(Icons.Filled.BrokenImage, contentDescription = null, tint = tint)
        Text(
            stringResource(R.string.media_tor_blocked), style = MaterialTheme.typography.labelSmall, color = tint,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

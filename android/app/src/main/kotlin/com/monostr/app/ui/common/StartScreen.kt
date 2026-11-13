package com.monostr.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.monostr.app.R
import com.monostr.app.ui.theme.Spacing
import kotlinx.coroutines.delay

/** Spec 3: after this long without a session the start screen shows a spinner. */
const val START_SPINNER_DELAY_MS = 1_500L
/** The coin as the Android 12+ splash draws it: 288 dp canvas, 33.3 % inset on each side (res/drawable/splash_coin.xml). */
private val START_COIN = 96.dp

/**
 * Spec 3, owner 2026-11-12 (start variant C): shown while the session restores ([com.monostr.app.session.SessionState.Loading]).
 * Only the coin, as large as the system splash draws it ([START_COIN]), centred in the whole window: the system splash shows the same coin at the same place, so the
 * hand-over does not move it. No animation; the only timer is the spinner delay, and it ends with the composition. The
 * spinner sits below the coin and never moves it.
 */
@Composable
fun StartScreen(spinnerDelayMs: Long = START_SPINNER_DELAY_MS) {
    val logoSize = START_COIN
    var spinner by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(spinnerDelayMs)
        spinner = true
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("start-screen")) {
        Image(
            painter = painterResource(R.drawable.ic_logo_mark),
            contentDescription = null, // decorative
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
            modifier = Modifier.align(Alignment.Center).size(logoSize).testTag("start-logo"),
        )
        if (spinner) {
            CircularProgressIndicator(
                Modifier.align(Alignment.Center).offset(y = logoSize / 2 + Spacing.xl + SPINNER_SIZE / 2).size(SPINNER_SIZE).testTag("start-spinner"),
                strokeWidth = 2.dp,
            )
        }
    }
}

private val SPINNER_SIZE = 24.dp

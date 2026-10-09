package com.monostr.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.monostr.app.R
import kotlinx.coroutines.delay

/** Spec 3: after this long without a session the start screen shows a spinner. */
const val START_SPINNER_DELAY_MS = 1_500L
private val LOGO_HEIGHT = 96.dp
private const val GOLDEN_SECTION = 0.382f

/**
 * Spec 3: shown while the session restores ([com.monostr.app.session.SessionState.Loading]). Same
 * background as the system splash (which shows no icon), so the hand-over does not jump. No
 * animation; the only timer is the spinner delay, and it ends with the composition.
 */
@Composable
fun StartScreen(spinnerDelayMs: Long = START_SPINNER_DELAY_MS) {
    var spinner by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(spinnerDelayMs)
        spinner = true
    }
    val ink = ColorFilter.tint(MaterialTheme.colorScheme.onBackground)
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("start-screen")) {
        val logoTop = (maxHeight * GOLDEN_SECTION - LOGO_HEIGHT / 2).coerceAtLeast(0.dp)
        Column(Modifier.fillMaxWidth().padding(top = logoTop), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.ic_logo_mark),
                contentDescription = null, // decorative; the wordmark carries the name
                colorFilter = ink,
                modifier = Modifier.height(LOGO_HEIGHT).testTag("start-logo"),
            )
            Spacer(Modifier.height(16.dp))
            Image(
                painter = painterResource(R.drawable.ic_wordmark),
                contentDescription = stringResource(R.string.app_name),
                colorFilter = ink,
                modifier = Modifier.height(40.dp),
            )
            if (spinner) {
                Spacer(Modifier.height(24.dp))
                CircularProgressIndicator(Modifier.size(24.dp).testTag("start-spinner"), strokeWidth = 2.dp)
            }
        }
    }
}

package com.monostr.app.ui.accounts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.monostr.app.R
import com.monostr.app.net.FreshRoute
import androidx.compose.ui.unit.dp
import com.monostr.app.ui.common.MonostrSnackbar
import com.monostr.app.ui.common.SNACKBAR_MARGIN
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How long «Now using …» stays once nothing waits for a fresh Tor route. */
internal const val SWITCH_NOTICE_MS = 2_000L

/**
 * Where the notice sits over the app: like the feed's own snackbar, left of the 56 dp compose button (16 dp from the end) and
 * above the 60 dp tab bar; [SNACKBAR_MARGIN] surrounds the card.
 */
val SWITCH_NOTICE_START = 16.dp - SNACKBAR_MARGIN
val SWITCH_NOTICE_END = 16.dp + 56.dp + 12.dp - SNACKBAR_MARGIN
val SWITCH_NOTICE_BOTTOM = 60.dp + 16.dp + 4.dp - SNACKBAR_MARGIN

/**
 * Spec «Switcher UI» / «Tor wait»: the notice after a switch. «Now using [name]»; while the new account waits for tor's NEWNYM
 * window ([FreshRoute.Waiting]) it counts down each second, after 15 s without an acknowledgement ([FreshRoute.Stalled]) it waits
 * without a number. Once [freshRoute] is (or comes back to) Ready it shows the name for [SWITCH_NOTICE_MS], then [onDone]; a route
 * that starts waiting meanwhile keeps it on screen. [freshRoute] null: no Tor, the name only.
 */
@Composable
fun SwitchNotice(name: String, freshRoute: StateFlow<FreshRoute>?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val flow = freshRoute ?: remember { MutableStateFlow(FreshRoute.Ready) }
    val route by flow.collectAsState()
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(route) {
        if (route == FreshRoute.Ready) {
            delay(SWITCH_NOTICE_MS)
            done()
        }
    }
    val text = when (val r = route) {
        FreshRoute.Ready -> stringResource(R.string.accounts_now_using, name)
        is FreshRoute.Waiting -> stringResource(R.string.accounts_now_using_tor, name, r.secondsLeft)
        FreshRoute.Stalled -> stringResource(R.string.accounts_tor_stalled)
    }
    MonostrSnackbar(text, modifier.testTag("switch-notice"))
}

/** How long a message about an account step stays ([AccountNoticeHost]). */
internal const val ACCOUNT_NOTICE_MS = 4_000L

/** A message about an account step that must outlive the sheet or screen that started it. */
sealed interface AccountNotice {
    /** The switch did not happen; the previous account runs again (final review M5: the sheet left composition meanwhile). */
    data object SwitchFailed : AccountNotice
    /** The removal from the switcher did not happen. */
    data object RemoveFailed : AccountNotice
    /** The key or Amber of the account that runs was entered again (final review M8). */
    data class AlreadyUsing(val pubkey: String) : AccountNotice
}

/** Process-wide, like the switch notice: a failed switch replaces the sheet by the start screen and back. */
object AccountNotices {
    val current = MutableStateFlow<AccountNotice?>(null)

    fun post(notice: AccountNotice) { current.value = notice }
}

/**
 * Shows [AccountNotices.current] for [ACCOUNT_NOTICE_MS] where the switch notice sits, then clears it (a newer one stays). [nameOf]
 * gives an account's shown name (registry name or short npub).
 */
@Composable
fun AccountNoticeHost(nameOf: suspend (String) -> String, modifier: Modifier = Modifier) {
    val notice by AccountNotices.current.collectAsState()
    val shown = notice ?: return
    val name by produceState<String?>(null, shown) { value = (shown as? AccountNotice.AlreadyUsing)?.let { nameOf(it.pubkey) } ?: "" }
    val n = name ?: return
    LaunchedEffect(shown) {
        delay(ACCOUNT_NOTICE_MS)
        AccountNotices.current.compareAndSet(shown, null)
    }
    val text = when (shown) {
        AccountNotice.SwitchFailed -> stringResource(R.string.accounts_switch_failed)
        AccountNotice.RemoveFailed -> stringResource(R.string.accounts_remove_failed)
        is AccountNotice.AlreadyUsing -> stringResource(R.string.accounts_already_using, n)
    }
    MonostrSnackbar(text, modifier.testTag("account-notice"))
}

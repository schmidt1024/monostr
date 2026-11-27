package com.monostr.app.ui.settings.pages

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalContext
import com.monostr.app.ui.common.copyToClipboard
import com.monostr.app.ui.settings.AccountSummaryRow
import com.monostr.app.ui.theme.SettingsIcons
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel
import com.monostr.app.ui.accounts.AccountsList
import com.monostr.app.ui.settings.LogoutRow
import com.monostr.app.ui.accounts.AccountsViewModel
import com.monostr.app.ui.accounts.Caption
import com.monostr.app.ui.accounts.LocalOpenAddAccount
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monostr.app.R
import com.monostr.app.ui.common.GuardedAlertDialog
import com.monostr.app.ui.settings.SettingsCardDivider
import com.monostr.app.ui.settings.SettingsGroup
import com.monostr.app.ui.settings.SettingsPage
import com.monostr.app.ui.settings.SettingsViewModel
import com.monostr.app.ui.settings.ShowKeyGate
import com.monostr.app.ui.settings.KeyLock
import com.monostr.app.ui.settings.LocalScreenLock
import com.monostr.app.ui.settings.rememberKeyUnlock
import com.monostr.nostr.Npub

/**
 * Account & key (UX P2-12): who is logged in like on the overview (avatar, name, npub with a copy button), then the key
 * card: the signer's permissions (Amber) or the private key row with its warning (key login), each a row with a key icon.
 */
@Composable
fun AccountPage(vm: SettingsViewModel, focus: String?, onBack: () -> Unit, onShowKey: () -> Unit, onLogout: () -> Unit = {}) {
    SettingsPageScaffold(SettingsPage.ACCOUNT, focus, onBack, vm) {
        SettingsGroup(stringResource(R.string.settings_logged_in_as), first = true) {
            val context = LocalContext.current
            val copied = stringResource(R.string.note_copied)
            val npub = remember(vm.pubkey) { runCatching { Npub.encode(vm.pubkey) }.getOrNull() }
            AccountSummaryRow(vm, Modifier.testTag("settings-account-profile"), noName = stringResource(R.string.settings_no_name)) {
                if (npub != null) {
                    IconButton(onClick = { copyToClipboard(context, npub, copied) }, modifier = Modifier.testTag("settings-copy-npub")) {
                        Icon(SettingsIcons.Copy, contentDescription = stringResource(R.string.settings_copy_npub), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        SettingsGroup(stringResource(R.string.settings_label_key)) {
            if (vm.isAmber) {
                val renewBusy by vm.renewBusy.collectAsStateWithLifecycle()
                SettingsLinkRow(
                    stringResource(R.string.settings_amber_renew), null, "settings-amber-renew", enabled = !renewBusy, anchor = "settings-amber-renew", chevron = false,
                    leading = { Icon(SettingsIcons.Key, contentDescription = null) }, onClick = vm::renewAmber,
                )
            } else {
                ShowKeyRow(onShowKey)
                SettingsCardDivider()
                KeyLockRow()
            }
        }
        // owner 2026-11-26: «Log out» belongs to the account it ends, not to the bottom of the overview
        SettingsGroup(null) {
            val unlock = rememberKeyUnlock()
            LogoutRow(vm, onLogout, onShowKey = { unlock(false) { ShowKeyGate.grant(); onShowKey() } })
        }
        // owner 2026-11-26: the active account and the account list are one Settings entry; the feed avatar's long-press keeps the sheet
        val accounts: AccountsViewModel = hiltViewModel()
        LaunchedEffect(accounts) { accounts.clearMessage() }
        val openAdd = LocalOpenAddAccount.current
        SettingsGroup(stringResource(R.string.accounts_other)) {
            // a switch recreates the activity: nothing to do here afterwards
            AccountsList(accounts, onAdd = openAdd, onActive = {}, onSwitched = {}, skipActive = true)
        }
        Caption(stringResource(R.string.accounts_page_hint))
    }
}

/**
 * Settings → "Show key" for a key login (spec 2026-10-17 §6/§11): the warning alone opens the key screen.
 * No screen-lock prompt unless the user turned [KeyLockRow] on (user decision 2026-10-17: the old confirm-credential
 * screen often offers only the PIN, which locked people out of their own key).
 */
@Composable
private fun ShowKeyRow(onShowKey: () -> Unit) {
    var warn by rememberSaveable { mutableStateOf(false) }
    val unlock = rememberKeyUnlock()
    SettingsLinkRow(
        stringResource(R.string.settings_show_key), null, "settings-show-key", subtitle = stringResource(R.string.settings_show_key_sub), anchor = "settings-show-key",
        leading = { Icon(SettingsIcons.Key, contentDescription = null) },
    ) { warn = true }
    if (warn) {
        GuardedAlertDialog(
            onDismissRequest = { warn = false },
            text = { Text(stringResource(R.string.settings_show_key_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    warn = false
                    unlock(false) {
                        ShowKeyGate.grant()
                        onShowKey()
                    }
                }, modifier = Modifier.testTag("show-key-confirm")) { Text(stringResource(R.string.settings_show_key_confirm)) }
            },
            dismissButton = { TextButton(onClick = { warn = false }) { Text(stringResource(R.string.settings_show_key_cancel)) } },
            modifier = Modifier.testTag("show-key-warning"),
        )
    }
}

/**
 * The switch for [KeyLock]. Turning it on or off asks for the screen lock itself: on proves the prompt works on this
 * phone before the key depends on it, off must not be open to whoever holds the unlocked phone.
 */
@Composable
private fun KeyLockRow() {
    val context = LocalContext.current
    val lock = LocalScreenLock.current
    val secure = remember(context, lock) { lock.deviceSecure(context) }
    var on by remember { mutableStateOf(KeyLock.enabled(context)) }
    val unlock = rememberKeyUnlock()
    SettingSwitch(
        stringResource(R.string.settings_key_lock),
        stringResource(if (secure) R.string.settings_key_lock_hint else R.string.settings_key_lock_none),
        checked = on && secure, onChange = { want -> unlock(true) { KeyLock.set(context, want); on = want } }, tag = "settings-key-lock", enabled = secure,
    )
}

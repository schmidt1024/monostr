package com.monostr.app.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monostr.app.R
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.monostr.app.ui.common.GuardedDropdownMenu
import com.monostr.app.ui.common.GuardedModalBottomSheet
import com.monostr.app.ui.common.ConfirmDialog
import com.monostr.app.ui.common.asString
import com.monostr.app.ui.theme.Spacing

/** Opens the account switcher (long-press on the own avatar in the feed, the accounts row in Settings); a no-op outside the host. */
val LocalOpenAccounts = staticCompositionLocalOf<() -> Unit> { {} }

/** Opens the add sheet directly (the Accounts page in Settings); a no-op outside the host. */
val LocalOpenAddAccount = staticCompositionLocalOf<() -> Unit> { {} }

private enum class AccountSheet { NONE, SWITCHER, ADD }

/**
 * Spec «Switcher UI»: one switcher and one add sheet for the logged-in nav host, opened through [LocalOpenAccounts]. The key login
 * and the create flow are full screens of the host ([onLoginWithKey], [onCreate]).
 */
@Composable
fun AccountSwitcherHost(onLoginWithKey: () -> Unit, onCreate: () -> Unit, vm: AccountsViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    var sheet by rememberSaveable { mutableStateOf(AccountSheet.NONE) }
    val open = remember(vm) { { vm.clearMessage(); sheet = AccountSheet.SWITCHER } }
    val openAdd = remember(vm) { { vm.clearMessage(); sheet = AccountSheet.ADD } }
    CompositionLocalProvider(LocalOpenAccounts provides open, LocalOpenAddAccount provides openAdd) { content() }
    when (sheet) {
        AccountSheet.NONE -> Unit
        AccountSheet.SWITCHER -> AccountSwitcherSheet(
            vm,
            onAdd = { sheet = AccountSheet.ADD },
            onDismiss = { sheet = AccountSheet.NONE },
        )
        AccountSheet.ADD -> AddAccountSheet(
            vm,
            onLoginWithKey = { sheet = AccountSheet.NONE; onLoginWithKey() },
            onCreate = { sheet = AccountSheet.NONE; onCreate() },
            onDismiss = { sheet = AccountSheet.NONE },
        )
    }
}

/**
 * The accounts on this device with avatar, name and short npub, a check mark on the active one, «Add account» at the end (X as
 * design reference). Tap = switch; while a send of the active account runs the rows wait. An account whose key is gone reads
 * «Sign in again» and leads to the add sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSwitcherSheet(vm: AccountsViewModel, onAdd: () -> Unit, onDismiss: () -> Unit) {
    AccountSheet(onDismiss, "account-switcher") {
        Text(
            stringResource(R.string.accounts_title), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
        )
        AccountsList(vm, onAdd = onAdd, onActive = onDismiss, onSwitched = onDismiss)
    }
}

/**
 * The rows of the switcher sheet and of Settings › Accounts (owner 2026-11-26: one page, the active account above, [skipActive]
 * leaves it out there): every account, tap = switch ([onActive] for the active one), «⋮» = remove, «Add account» at the end.
 */
@Composable
internal fun ColumnScope.AccountsList(vm: AccountsViewModel, onAdd: () -> Unit, onActive: () -> Unit, onSwitched: () -> Unit, skipActive: Boolean = false) {
    val state by vm.state.collectAsStateWithLifecycle()
    val switching by vm.switching.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val canSwitch = !state.busy && !switching
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    state.accounts.filter { !(skipActive && it.active) }.forEach { row ->
        AccountRowItem(
            row,
            enabled = row.active || canSwitch,
            onClick = {
                when {
                    row.active -> onActive()
                    row.needsSignIn -> onAdd()
                    else -> vm.switchTo(row.pubkey, onDone = onSwitched)
                }
            },
            // final review I2: every account not shown can go from here, also one whose key is gone (it can never run again)
            onRemove = if (row.active) null else ({ removing = row.pubkey }),
        )
    }
    state.accounts.firstOrNull { it.pubkey == removing }?.let { row ->
        RemoveAccountDialog(row, onConfirm = { keep -> removing = null; vm.remove(row.pubkey, keep) }, onDismiss = { removing = null })
    }
    if (state.busy) Caption(stringResource(R.string.accounts_busy))
    message?.let { Caption(it.asString(), error = true) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(enabled = canSwitch, role = Role.Button, onClick = onAdd)
            .alpha(if (canSwitch) 1f else DISABLED_ALPHA)
            .padding(horizontal = Spacing.l, vertical = Spacing.m)
            .testTag("account-add"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(AVATAR), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, contentDescription = null) }
        Spacer(Modifier.width(Spacing.m))
        Text(stringResource(R.string.accounts_add), style = MaterialTheme.typography.bodyLarge)
    }
    Spacer(Modifier.size(Spacing.l))
}

/** Removal is irreversible: it asks first, naming the account; a key that may exist only here is warned about (no «Show key»: not the shown account). */
@Composable
private fun RemoveAccountDialog(row: AccountRow, onConfirm: (keepSettings: Boolean) -> Unit, onDismiss: () -> Unit) {
    // an account whose key is gone has nothing to seal its settings with
    val canKeep = !row.needsSignIn
    var keep by rememberSaveable { mutableStateOf(true) }
    val name = row.name ?: row.npubShort
    val text = stringResource(R.string.accounts_remove_text, name) +
        if (!row.amber && !row.needsSignIn) "\n\n" + stringResource(R.string.settings_logout_key_warning) else ""
    ConfirmDialog(
        title = stringResource(R.string.settings_logout_q), text = text,
        confirm = stringResource(R.string.settings_logout), confirmTag = "account-remove-confirm", destructive = true,
        onConfirm = { onConfirm(canKeep && keep) }, onDismiss = onDismiss,
        extra = if (canKeep) ({ com.monostr.app.ui.common.KeepSettingsSwitch(keep) { keep = it } }) else null,
    )
}

@Composable
private fun AccountRowItem(row: AccountRow, enabled: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(horizontal = Spacing.l, vertical = Spacing.s)
            .testTag("account-row-" + row.pubkey.take(8)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountAvatar(row.picture, row.active)
        Spacer(Modifier.width(Spacing.m))
        Column(Modifier.weight(1f)) {
            Text(row.name ?: row.npubShort, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (row.needsSignIn) {
                Text(stringResource(R.string.accounts_sign_in_again), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, maxLines = 1)
            } else if (row.name != null) {
                Text(row.npubShort, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        if (row.active) {
            Spacer(Modifier.width(Spacing.s))
            Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.accounts_active), tint = MaterialTheme.colorScheme.primary)
        }
        if (onRemove != null) {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.testTag("account-menu-" + row.pubkey.take(8))) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.note_menu))
                }
                GuardedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_logout), color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onRemove() },
                        modifier = Modifier.testTag("account-remove-" + row.pubkey.take(8)),
                    )
                }
            }
        }
    }
}

/**
 * Whether the switcher may fetch an account's picture over the network: only the active account's. An inactive account's picture
 * comes from the image caches or not at all, so the active account's connection never asks for another account's picture.
 */
internal fun switcherAvatarNetwork(active: Boolean): CachePolicy = if (active) CachePolicy.ENABLED else CachePolicy.DISABLED

/** The account's picture over a person glyph (as the Settings account card); the glyph stays when there is none or it is not cached. */
@Composable
internal fun AccountAvatar(picture: String?, active: Boolean) {
    Box(Modifier.size(AVATAR).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!picture.isNullOrBlank()) {
            val context = LocalContext.current
            val request = remember(picture, active) {
                ImageRequest.Builder(context).data(picture).networkCachePolicy(switcherAvatarNetwork(active)).build()
            }
            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
    }
}

/**
 * The switcher's and the add sheet's frame: fully expanded at once (a short list, no half step) and its content scrolls when it is
 * taller than the screen (many accounts, a large font), so every row and «Add account» stay reachable above the navigation bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountSheet(onDismiss: () -> Unit, tag: String, content: @Composable ColumnScope.() -> Unit) {
    GuardedModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag(tag),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("$tag-content"), content = content)
    }
}

@Composable
internal fun Caption(text: String, error: Boolean = false) {
    Text(
        text, style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
    )
}

internal val AVATAR = 40.dp
internal const val DISABLED_ALPHA = 0.38f

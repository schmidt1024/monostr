package com.monostr.app.ui.accounts

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monostr.app.R
import com.monostr.app.ui.common.asString
import com.monostr.app.ui.theme.Spacing

/**
 * Spec «Adding an account»: the first login's ways in, in order: Amber (recommended; not installed, a hint to get it, never a
 * block), the key login (nsec/ncryptsec) and «Create another account». While a send of the active account runs nothing starts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountSheet(vm: AccountsViewModel, onLoginWithKey: () -> Unit, onCreate: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val amber by vm.amberState.collectAsStateWithLifecycle()
    val amberInstalled = remember { vm.amberInstalled() }
    val enabled = !state.busy && !amber.busy
    // final review M8: Amber answered with the account that runs; «Already using …» tells it, the sheet closes
    LaunchedEffect(amber.alreadyActive) {
        if (amber.alreadyActive) {
            vm.amber.alreadyActiveShown()
            onDismiss()
        }
    }
    AccountSheet(onDismiss, "add-account-sheet") {
        Text(
            stringResource(R.string.accounts_add), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
        )
        if (amberInstalled) {
            AddRow(
                Icons.Outlined.Shield, stringResource(R.string.add_account_amber), null, enabled, "add-account-amber",
                badge = stringResource(R.string.add_account_recommended), busy = amber.busy, onClick = vm::loginWithAmber,
            )
            amber.error?.let { Caption(it.asString(), error = true) }
        } else {
            AddRow(
                Icons.Outlined.Download, stringResource(R.string.add_account_get_amber), stringResource(R.string.add_account_get_amber_hint),
                enabled = true, tag = "add-account-get-amber", badge = stringResource(R.string.add_account_recommended), onClick = { openAmberStore(context) },
            )
        }
        AddRow(Icons.Outlined.Key, stringResource(R.string.add_account_key), null, enabled, "add-account-key", onClick = onLoginWithKey)
        AddRow(Icons.Filled.PersonAdd, stringResource(R.string.add_account_create), null, enabled, "add-account-create", onClick = onCreate)
        if (state.busy) Caption(stringResource(R.string.accounts_busy))
        Spacer(Modifier.size(Spacing.l))
    }
}

@Composable
private fun AddRow(
    icon: ImageVector, title: String, subtitle: String?, enabled: Boolean, tag: String,
    badge: String? = null, busy: Boolean = false, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(horizontal = Spacing.l, vertical = Spacing.m)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(AVATAR), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(icon, contentDescription = null)
        }
        Spacer(Modifier.width(Spacing.m))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                if (badge != null) {
                    Spacer(Modifier.width(Spacing.s))
                    Text(
                        badge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1,
                        modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small).padding(horizontal = Spacing.s, vertical = Spacing.xs / 2),
                    )
                }
            }
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Amber's package (NIP-55 signer). */
private const val AMBER_PKG = "com.greenart7c3.nostrsigner"

/** Amber's page in the store the user has (the Offline Translator pattern), its releases on GitHub without one. */
private fun openAmberStore(context: Context) {
    val market = Intent(Intent.ACTION_VIEW, "market://details?id=$AMBER_PKG".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(market)
    } catch (_: ActivityNotFoundException) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/greenart7c3/Amber/releases".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

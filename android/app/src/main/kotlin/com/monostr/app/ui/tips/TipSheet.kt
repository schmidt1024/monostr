package com.monostr.app.ui.tips

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monostr.app.R
import com.monostr.app.data.Presets
import com.monostr.app.ui.common.QrCode
import com.monostr.app.ui.common.asString
import com.monostr.app.ui.theme.MonostrFonts

/** Opens a `monero:` URI in a wallet app. Must be called with an Activity context (spec 5.9; Espresso intercepts only these). */
object WalletOpener {
    fun open(context: Context, uri: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

/** Bottom sheet driven by a [TipSheetController]; opens the wallet once the intent is published. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TipSheetHost(controller: TipSheetController) {
    val state by controller.state.collectAsStateWithLifecycle()
    if (!state.visible) return
    val context = LocalContext.current
    val pay = state.phase as? TipPhase.Pay
    LaunchedEffect(pay?.uri) {
        if (pay != null && pay.walletMissing == null) controller.walletResult(WalletOpener.open(context, pay.uri))
    }
    ModalBottomSheet(onDismissRequest = controller::dismiss) {
        TipSheetContent(
            state = state,
            onSelect = controller::select,
            onCustom = controller::setCustom,
            onAnonymous = controller::setAnonymous,
            onSend = controller::send,
            onRetryWallet = { pay?.let { controller.walletResult(WalletOpener.open(context, it.uri)) } },
            onDone = controller::dismiss,
        )
    }
}

/** Stateless sheet content, testable without Hilt or a controller. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TipSheetContent(
    state: TipSheetState,
    onSelect: (Long) -> Unit,
    onCustom: (String) -> Unit,
    onAnonymous: (Boolean) -> Unit,
    onSend: () -> Unit,
    onRetryWallet: () -> Unit,
    onDone: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
        Text(stringResource(R.string.tip_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        when (val phase = state.phase) {
            TipPhase.Loading -> CircularProgressIndicator(Modifier.testTag("tip-loading"))
            TipPhase.NoMonero -> {
                Text(TipSheetController.NO_MONERO.asString(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("tip-no-monero"))
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onDone, modifier = Modifier.align(Alignment.End).testTag("tip-done")) { Text(stringResource(R.string.action_close)) }
            }
            TipPhase.Ready, TipPhase.Sending -> {
                // only the amount error belongs to the amount field; every other error sits below the switch
                val error = state.error
                val amountError = error == TipSheetController.ERROR_AMOUNT
                Text(stringResource(R.string.tip_amount), style = MaterialTheme.typography.labelLarge)
                // numbers only (the unit sits in the heading); FlowRow wraps instead of clipping at large font scales
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.presets.forEachIndexed { i, p ->
                        val selected = state.selected == p
                        FilterChip(
                            selected = selected, onClick = { onSelect(p) },
                            label = { Text(Presets.format(p), fontFamily = MonostrFonts.mono, maxLines = 1) },
                            leadingIcon = if (selected) { { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) } } else null,
                            border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, selectedBorderColor = MaterialTheme.colorScheme.primary, selectedBorderWidth = 1.dp),
                            modifier = Modifier.testTag("tip-preset-$i"),
                        )
                    }
                }
                OutlinedTextField(
                    value = state.custom, onValueChange = onCustom, singleLine = true,
                    label = { Text(stringResource(R.string.tip_custom_amount)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = amountError,
                    supportingText = if (amountError && error != null) { { Text(error.asString()) } } else null,
                    modifier = Modifier.fillMaxWidth().testTag("tip-custom"),
                )
                // the whole row toggles; the Switch itself only shows the state. Locked while sending:
                // the display must not differ from what is being sent
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = state.anonymous, enabled = phase == TipPhase.Ready, role = Role.Switch, onValueChange = onAnonymous)
                        .padding(vertical = 8.dp)
                        .testTag("tip-anonymous"),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.tip_anonymous_switch), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.tip_anonymous_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = state.anonymous, onCheckedChange = null)
                }
                if (error != null && !amountError) {
                    Text(error.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("tip-error"))
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onSend, enabled = phase == TipPhase.Ready, modifier = Modifier.fillMaxWidth().testTag("tip-send")) {
                    Text(stringResource(if (phase == TipPhase.Sending) R.string.state_sending else R.string.tip_pay))
                }
            }
            is TipPhase.Pay -> {
                if (phase.arrived) {
                    // the receipt is in: the offline notice and the pay-by-hand fallback no longer matter
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("tip-arrived")) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.tip_arrived), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    state.notice?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error); Spacer(Modifier.height(8.dp)) }
                    if (phase.walletMissing == true) {
                        Text(stringResource(R.string.tip_no_wallet_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("tip-fallback"))
                        Text(stringResource(R.string.tip_no_wallet_text), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { uriHandler.openUri("https://www.monerujo.io") }) { Text("Monerujo") }
                            TextButton(onClick = { uriHandler.openUri("https://cakewallet.com") }) { Text("Cake Wallet") }
                        }
                        QrCode(phase.uri, Modifier.size(200.dp).align(Alignment.CenterHorizontally))
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.tip_xmr_amount, Presets.format(phase.amount)), style = MaterialTheme.typography.titleMedium, fontFamily = MonostrFonts.mono, modifier = Modifier.testTag("tip-fallback-amount"))
                        Text(phase.address, style = MaterialTheme.typography.bodySmall, fontFamily = MonostrFonts.mono, modifier = Modifier.testTag("tip-fallback-address"))
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(phase.address)) }, modifier = Modifier.testTag("tip-copy-address")) { Text(stringResource(R.string.action_copy_address)) }
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(phase.uri)) }, modifier = Modifier.testTag("tip-copy-uri")) { Text(stringResource(R.string.action_copy_uri)) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onRetryWallet, modifier = Modifier.fillMaxWidth().testTag("tip-open-wallet")) { Text(stringResource(R.string.tip_open_wallet_again)) }
                    } else {
                        Text(stringResource(R.string.tip_sent), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("tip-sent"))
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDone, modifier = Modifier.align(Alignment.End).testTag("tip-done")) { Text(stringResource(R.string.action_done)) }
            }
        }
    }
}

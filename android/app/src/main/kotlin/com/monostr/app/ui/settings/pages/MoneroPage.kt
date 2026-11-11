package com.monostr.app.ui.settings.pages

import com.monostr.app.ui.settings.settingAnchor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.monostr.app.ui.settings.SettingsConfirmDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monostr.app.R
import com.monostr.app.data.AmountUnit
import com.monostr.app.ui.common.asString
import com.monostr.app.ui.common.copyToClipboard
import com.monostr.app.ui.common.label
import com.monostr.app.ui.settings.PersonalWatcherLine
import com.monostr.app.ui.settings.SettingsPage
import com.monostr.app.ui.settings.SettingsViewModel
import com.monostr.app.ui.theme.Spacing
import com.monostr.app.ui.settings.SettingsCardDivider
import com.monostr.app.ui.settings.SettingsGroup

/** The Monero actions that ask before they run; [tag] is the confirm button's test tag (the row's tag plus `-confirm`). */
private enum class MoneroConfirm(val title: Int, val text: Int, val confirm: Int, val tag: String) {
    DISABLE(R.string.settings_monero_disable_q, R.string.settings_monero_disable_text, R.string.settings_monero_disable_action, "settings-monero-disable-confirm"),
    DELETE_VIEW_KEY(R.string.settings_monero_delete_view_key_q, R.string.settings_monero_delete_view_key_text, R.string.settings_monero_delete_view_key_action, "settings-monero-delete-view-key-confirm"),
    REMOVE_PAYTO(R.string.settings_monero_payto_remove_q, R.string.settings_monero_payto_remove_text, R.string.action_remove, "settings-monero-payto-remove-confirm"),
}

/**
 * Monero & tips: where tips arrive (status card, set up or switch, the upgrade from a payto-only address, amounts
 * public), how the user sends them (unit, presets), the payto address, and the destructive actions last.
 */
@Composable
fun MoneroPage(vm: SettingsViewModel, focus: String?, onBack: () -> Unit, onMoneroSetup: () -> Unit, onMoneroUpgrade: () -> Unit) {
    SettingsPageScaffold(SettingsPage.MONERO, focus, onBack, vm) {
        val monero by vm.monero.state.collectAsStateWithLifecycle()
        // back from the setup screen: an address-only setup, a change or a removal there leaves the stored setup alone
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.monero.resumed() }
        val setup = monero.setup
        val payto = monero.paytoOnly
        val copied = stringResource(R.string.note_copied)
        // wave A review: the danger zone and the payto removal ask first, like the relay list actions
        var asking by rememberSaveable { mutableStateOf<MoneroConfirm?>(null) }
        asking?.let { a ->
            SettingsConfirmDialog(
                title = stringResource(a.title), text = stringResource(a.text), confirm = stringResource(a.confirm), confirmTag = a.tag,
                destructive = true,
                onConfirm = {
                    asking = null
                    when (a) {
                        MoneroConfirm.DISABLE -> vm.monero.disable()
                        MoneroConfirm.DELETE_VIEW_KEY -> vm.monero.deleteViewKey()
                        MoneroConfirm.REMOVE_PAYTO -> vm.monero.removePayto()
                    }
                },
                onDismiss = { asking = null },
            )
        }
        val context = LocalContext.current
        // round 2: one card per section; receiving first, then its privacy switch, sending, the payto address, the danger zone last
        SettingsGroup(stringResource(R.string.settings_label_receiving_tips), first = true) {
            val announced = monero.announced
            if (setup == null && (payto != null || announced != null)) {
                // owner 4: an address announced (kind 10037) or in payto already receives tips; no big «Set up» here
                val address = payto ?: announced!!
                Row(Modifier.fillMaxWidth().padding(start = Spacing.l, end = Spacing.s, top = Spacing.m, bottom = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                        Text(stringResource(R.string.settings_monero_active), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("settings-monero-active"))
                        Text(
                            stringResource(if (payto != null) R.string.settings_monero_active_payto else R.string.settings_monero_active_elsewhere, address.take(8), address.takeLast(6)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag(if (payto != null) "settings-monero-payto" else "settings-monero-announced"),
                        )
                    }
                    TextButton(
                        onClick = { copyToClipboard(context, address, copied) },
                        modifier = Modifier.testTag(if (payto != null) "settings-monero-copy-payto" else "settings-monero-copy-announced"),
                    ) { Text(stringResource(R.string.action_copy_address)) }
                }
                SettingsCardDivider()
                // the quiet way to receipts: the upgrade from a payto address, else the setup flow (it replaces the other device's address)
                if (payto != null) {
                    Box(Modifier.testTag("settings-monero-upgrade")) {
                        SettingsLinkRow(
                            stringResource(R.string.settings_monero_receipts), null, "settings-monero-upgrade-action",
                            subtitle = stringResource(R.string.setup_upgrade_text), onClick = onMoneroUpgrade,
                        )
                    }
                } else {
                    SettingsLinkRow(
                        stringResource(R.string.settings_monero_receipts), null, "settings-monero-setup",
                        subtitle = stringResource(R.string.settings_monero_receipts_replaces), anchor = "settings-monero-setup", onClick = onMoneroSetup,
                    )
                }
            } else if (setup == null) {
                Column(Modifier.fillMaxWidth().padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(stringResource(R.string.settings_monero_not_set_up), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Spacing.s))
                    Button(onClick = onMoneroSetup, modifier = Modifier.fillMaxWidth().settingAnchor("settings-monero-setup").testTag("settings-monero-setup"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.settings_monero_set_up)) }
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    // launch feedback: people expected tips at a Monero address in their bio; say plainly where they go
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings_monero_address, setup.address.take(8), setup.address.takeLast(6), setup.network), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false).testTag("settings-monero-address"))
                        TextButton(onClick = { copyToClipboard(context, setup.address, copied) }, modifier = Modifier.testTag("settings-monero-copy-address")) { Text(stringResource(R.string.action_copy_address)) }
                    }
                    Text(stringResource(R.string.settings_monero_wallet_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("settings-monero-wallet-hint"))
                    if (setup.isPersonal) {
                        val name = com.monostr.nostr.Npub.short(setup.watcherPubkey)
                        val line = when (val l = monero.personalLine) {
                            PersonalWatcherLine.Unknown -> stringResource(R.string.settings_monero_personal, name)
                            PersonalWatcherLine.NotFound -> stringResource(R.string.settings_monero_personal_unknown, name)
                            PersonalWatcherLine.Online -> stringResource(R.string.settings_monero_personal_online, name)
                            PersonalWatcherLine.Mismatch -> stringResource(R.string.settings_monero_personal_mismatch, name)
                            is PersonalWatcherLine.Offline -> stringResource(R.string.settings_monero_personal_offline, name, com.monostr.app.ui.monero.localTime(l.since))
                        }
                        Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("settings-monero-watcher"))
                    } else Text(stringResource(R.string.settings_monero_watcher, setup.watcherUrl), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("settings-monero-watcher"))
                }
                SettingsCardDivider()
                SettingsLinkRow(stringResource(R.string.settings_monero_switch_watcher), null, "settings-monero-setup", anchor = "settings-monero-watcher", onClick = onMoneroSetup)
            }
        }
        if (setup != null) {
            // privacy rule: who sees the amounts sits right under the status, not among the sending options
            SettingsGroup(stringResource(R.string.settings_label_privacy)) {
                SettingSwitch(
                    stringResource(R.string.settings_monero_amounts_public),
                    stringResource(
                        when {
                            monero.amountsSupported == false -> R.string.settings_monero_amounts_unsupported
                            monero.amountsSupported == null && !setup.amountsHidden -> R.string.settings_monero_amounts_checking
                            else -> R.string.settings_monero_amounts_public_hint
                        },
                    ),
                    !setup.amountsHidden, vm.monero::setAmountsPublic, "settings-monero-amounts",
                    enabled = (monero.amountsSupported == true || setup.amountsHidden) && !monero.busy,
                )
            }
        }
        SettingsGroup(stringResource(R.string.settings_label_sending_tips)) {
            SettingBlock(stringResource(R.string.settings_amount_unit), stringResource(R.string.settings_amount_unit_hint)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().settingAnchor("settings-amount-unit").testTag("settings-amount-unit")) {
                    AmountUnit.entries.forEachIndexed { i, u ->
                        SegmentedButton(
                            selected = monero.unit == u, onClick = { vm.monero.setUnit(u) },
                            shape = SegmentedButtonDefaults.itemShape(i, AmountUnit.entries.size, baseShape = MaterialTheme.shapes.medium), modifier = Modifier.testTag("settings-amount-unit-${u.name.lowercase()}"),
                        ) { Text(u.label) }
                    }
                }
            }
            SettingsCardDivider()
            // UX P2-13: the locale's decimal separator in the fields (both are accepted), «Save» only while there is a change
            // the app's language (per-app locale), not the JVM default, decides the separator
            val locale = LocalConfiguration.current.locales[0]
            val separator = remember(locale) { java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator }
            Column(Modifier.fillMaxWidth().settingAnchor("settings-presets-save").padding(Spacing.l)) {
                Text(stringResource(R.string.settings_presets, monero.unit.label), style = MaterialTheme.typography.bodyLarge)
                monero.presets.forEachIndexed { i, v ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = if (separator == ',') v.replace('.', ',') else v, onValueChange = { nv -> vm.monero.editPreset(i, nv) }, singleLine = true,
                            label = { Text(stringResource(R.string.settings_preset_n, i + 1)) }, isError = monero.error != null, modifier = Modifier.weight(1f).testTag("settings-preset-$i"),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = MaterialTheme.shapes.medium,
                        )
                        IconButton(onClick = { vm.monero.removePreset(i) }, enabled = monero.presets.size > 1, modifier = Modifier.testTag("settings-preset-remove-$i")) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_preset_remove))
                        }
                    }
                }
                TextButton(onClick = vm.monero::addPreset, enabled = monero.canAddPreset, modifier = Modifier.testTag("settings-preset-add")) { Text(stringResource(R.string.settings_preset_add)) }
                monero.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (monero.presetsDirty) {
                    Button(onClick = { vm.monero.savePresets() }, modifier = Modifier.fillMaxWidth().testTag("settings-presets-save"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.settings_presets_save)) }
                }
            }
        }
        if (setup == null && payto != null) {
            SettingsGroup(stringResource(R.string.settings_label_payto), modifier = Modifier.settingAnchor("settings-monero-payto")) {
                SettingsLinkRow(stringResource(R.string.settings_monero_payto_change), null, "settings-monero-payto-change", onClick = onMoneroSetup)
                SettingsCardDivider()
                SettingsLinkRow(stringResource(R.string.settings_monero_payto_remove), null, "settings-monero-payto-remove", enabled = !monero.busy, chevron = false, onClick = { asking = MoneroConfirm.REMOVE_PAYTO })
            }
        }
        if (setup != null || monero.hasViewKey) {
            SettingsGroup(stringResource(R.string.settings_label_danger)) {
                val red = MaterialTheme.colorScheme.error
                if (setup != null) {
                    SettingsLinkRow(stringResource(R.string.settings_monero_disable), null, "settings-monero-disable", enabled = !monero.busy, titleColor = red, anchor = "settings-monero-disable", chevron = false, onClick = { asking = MoneroConfirm.DISABLE })
                }
                if (setup != null && monero.hasViewKey) SettingsCardDivider()
                if (monero.hasViewKey) {
                    SettingsLinkRow(stringResource(R.string.settings_monero_delete_view_key), null, "settings-monero-delete-view-key", titleColor = red, anchor = "settings-monero-delete-view-key", chevron = false, onClick = { asking = MoneroConfirm.DELETE_VIEW_KEY })
                }
            }
        }
    }
}

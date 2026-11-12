package com.monostr.app.ui.settings.pages

import com.monostr.app.ui.settings.settingAnchor
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monostr.app.R
import com.monostr.app.data.POW_CHOICES
import com.monostr.app.data.RelaySuggestion
import com.monostr.app.data.RelaySuggestions
import com.monostr.app.ui.common.GuardedDropdownMenu
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.asString
import com.monostr.app.ui.settings.RelayListActions
import com.monostr.app.ui.settings.RelayListDiffHint
import com.monostr.app.ui.settings.SettingsPage
import com.monostr.app.ui.settings.SettingsViewModel
import com.monostr.app.ui.settings.isDefaultRelayList
import com.monostr.app.ui.settings.relayInfoFor
import com.monostr.app.ui.settings.relayShownUrl
import com.monostr.app.ui.settings.relayStatusLine
import com.monostr.app.ui.settings.RelayTone
import com.monostr.app.ui.settings.relayTone
import com.monostr.app.ui.theme.SettingsIcons
import com.monostr.app.ui.theme.StatusColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.monostr.app.ui.theme.Spacing
import com.monostr.app.ui.settings.SettingsCard
import com.monostr.app.ui.settings.SettingsCardDivider
import com.monostr.app.ui.settings.SettingsGroup
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import com.monostr.nostr.model.RelayInfo
import com.monostr.nostr.model.RelayMode
import com.monostr.tips.RelayHosts

/**
 * Relays (round 2, UX «Proposed order»): my relays; the author-relay switch right under them (privacy rule: the one setting
 * here that changes who sees the IP); the NIP-65 list; the DM and search lists as pages of their own; the paid suggestions;
 * PoW when sending. One card per section.
 */
@Composable
fun RelaysPage(vm: SettingsViewModel, focus: String?, onBack: () -> Unit, onOpen: (SettingsPage) -> Unit) {
    SettingsPageScaffold(SettingsPage.RELAYS, focus, onBack, vm) {
        val state by vm.controller.state.collectAsStateWithLifecycle()
        val monero by vm.monero.state.collectAsStateWithLifecycle()
        val live by vm.relayStates.collectAsStateWithLifecycle(initialValue = emptyList<RelayInfo>())
        val relaySeen by vm.relaySeen.collectAsStateWithLifecycle()
        val relayModes by vm.relayModes.collectAsStateWithLifecycle()
        SectionLabel(stringResource(R.string.settings_label_my_relays), first = true)
        RelayListEditor(
            relays = state.relays, live = live, error = state.error,
            onAdd = vm.controller::add, onRemove = { url, done -> vm.controller.remove(url, done) }, tagPrefix = "settings-relay", seen = relaySeen,
            modes = relayModes, onMode = vm::setRelayMode, onUndo = vm::restoreRelay,
        )
        SettingsGroup(stringResource(R.string.settings_label_connections)) {
            val authorRelays by vm.authorRelays.collectAsStateWithLifecycle()
            SettingSwitch(stringResource(R.string.settings_author_relays), stringResource(R.string.settings_author_relays_hint), authorRelays, vm::setAuthorRelays, "settings-author-relays")
        }
        SettingsGroup(stringResource(R.string.settings_label_relay_list)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s)) {
                val relayListBusy by vm.relayListBusy.collectAsStateWithLifecycle()
                val relayDiff by vm.relayListDiff.collectAsStateWithLifecycle()
                relayDiff?.let { RelayListDiffHint(it) }
                RelayListActions(
                    isDefault = isDefaultRelayList(state.relays, modes = relayModes), busy = relayListBusy || monero.busy,
                    onPublish = { vm.publishRelays() }, onAdopt = vm.monero::adoptRelayList, onReset = { vm.resetRelays() },
                )
            }
        }
        SettingsGroup(stringResource(R.string.settings_label_other_lists)) {
            val dmState by vm.dm.state.collectAsStateWithLifecycle()
            val searchState by vm.search.state.collectAsStateWithLifecycle()
            SettingsLinkRow(stringResource(R.string.settings_dm_relays), dmState.relays.size.toString(), "settings-open-dm-relays", subtitle = stringResource(R.string.settings_dm_relays_sub)) { onOpen(SettingsPage.DM_RELAYS) }
            SettingsCardDivider()
            SettingsLinkRow(stringResource(R.string.settings_search_relays), searchState.relays.size.toString(), "settings-open-search-relays") { onOpen(SettingsPage.SEARCH_RELAYS) }
        }
        SuggestedRelays(suggestions = RelaySuggestions.PAID_MONERO.filter { it.url !in state.relays }, onAdd = { vm.controller.add(it) })
        SettingsGroup(stringResource(R.string.settings_label_sending)) {
            val pow by vm.powBits.collectAsStateWithLifecycle()
            SettingBlock(stringResource(R.string.settings_pow), stringResource(R.string.settings_pow_hint)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().settingAnchor("settings-pow").testTag("settings-pow")) {
                    POW_CHOICES.forEachIndexed { i, bits ->
                        SegmentedButton(
                            selected = pow == bits, onClick = { vm.setPowBits(bits) },
                            shape = SegmentedButtonDefaults.itemShape(i, POW_CHOICES.size, baseShape = MaterialTheme.shapes.medium), modifier = Modifier.testTag("settings-pow-$bits"),
                        ) { Text(if (bits == 0) stringResource(R.string.settings_pow_off) else stringResource(R.string.settings_pow_bits, bits)) }
                    }
                }
            }
        }
    }
}

/** The colour of a relay's dot and status line ([RelayTone.NONE]: not in the pool, grey). */
@Composable
internal fun RelayTone.color(): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (this) {
        RelayTone.OK -> Color(if (dark) StatusColors.DarkOk else StatusColors.LightOk)
        RelayTone.WARN -> Color(if (dark) StatusColors.DarkWarn else StatusColors.LightWarn)
        RelayTone.BAD -> MaterialTheme.colorScheme.error
        RelayTone.PENDING, RelayTone.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** The name of a relay's mode. */
@Composable
private fun relayModeLabel(mode: RelayMode): String = stringResource(
    when (mode) { RelayMode.BOTH -> R.string.relay_mode_both; RelayMode.READ -> R.string.relay_mode_read; RelayMode.WRITE -> R.string.relay_mode_write },
)

/** A compact menu on the relay row: a 48 dp button naming the relay and its mode, the three modes to choose from. */
@Composable
private fun RelayModeMenu(url: String, mode: RelayMode, onMode: (String, RelayMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.relay_mode_description, relayShownUrl(url), relayModeLabel(mode))
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("relay-mode-$url")) {
            Icon(Icons.Filled.MoreVert, contentDescription = description)
        }
        GuardedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // the row may shorten the address: here it reads in full
            Text(
                url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s).testTag("relay-mode-$url-address"),
            )
            RelayMode.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(relayModeLabel(m), fontWeight = if (m == mode) FontWeight.Bold else null) },
                    onClick = { open = false; if (m != mode) onMode(url, m) },
                    modifier = Modifier.testTag("relay-mode-$url-$m"),
                )
            }
        }
    }
}

/**
 * One line with the middle left out: the end of a host says whose it is (`relay.damus.io.evil.example`), so it is not cut
 * off there. A long press shows the whole address, a screen reader always reads it in full.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RelayAddress(url: String, tagPrefix: String) {
    var full by remember(url) { mutableStateOf(false) }
    Text(
        if (full) url else relayShownUrl(url), style = MaterialTheme.typography.bodyMedium,
        maxLines = if (full) Int.MAX_VALUE else 1, overflow = if (full) TextOverflow.Clip else TextOverflow.MiddleEllipsis,
        modifier = Modifier
            .combinedClickable(onClick = {}, onLongClick = { full = !full })
            .clearAndSetSemantics { contentDescription = url }
            .testTag("$tagPrefix-address-${url.hashCode().toUInt()}"),
    )
}

/** One relay list as a card: a row per relay with its status lines (optional) and a remove icon, the add field last. */
@Composable
internal fun RelayListEditor(
    relays: List<String>,
    live: List<RelayInfo>?,
    error: UiText?,
    onAdd: (String) -> Boolean,
    /** Removes the relay; the callback runs once the removal took effect (then «Relay removed · Undo» shows). */
    onRemove: (String, () -> Unit) -> Unit,
    tagPrefix: String,
    /** 0.24.3: epoch seconds each relay was last seen connected (the feed relays only). */
    seen: Map<String, Long> = emptyMap(),
    /** The main list only: each relay's stored mode (a relay not in it reads and writes) and the change of it; null hides the control. */
    modes: Map<String, RelayMode>? = null,
    onMode: ((String, RelayMode) -> Unit)? = null,
    /** Puts a removed relay back at its index with its former mode (the snackbar's «Undo»); null: no undo offered. */
    onUndo: ((String, Int, RelayMode?) -> Unit)? = null,
) {
    var newRelay by rememberSaveable(tagPrefix) { mutableStateOf("") }
    val snackbar = LocalSettingsSnackbar.current
    val scope = rememberCoroutineScope()
    val removedText = stringResource(R.string.relay_removed)
    val undoText = stringResource(R.string.mute_undo)
    val remove: (String, Int) -> Unit = { url, index ->
        // the mode as it was, so «Undo» brings a read-only or write-only relay back as such
        val mode = modes?.get(url)
        onRemove(url) {
            if (snackbar != null && onUndo != null) scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                if (snackbar.showSnackbar(removedText, undoText, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) onUndo(url, index, mode)
            }
        }
    }
    SettingsCard {
        relays.forEachIndexed { i, url ->
            if (i > 0) SettingsCardDivider()
            val info = live?.let { relayInfoFor(it, url) }
            val line = live?.let { relayStatusLine(info, seen[(info?.url ?: url).trimEnd('/')], System.currentTimeMillis() / 1000, onion = RelayHosts.isOnionHost(url)) }
            val toneColor = line?.let { relayTone(info, it).color() }
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = Spacing.l, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                if (toneColor != null) {
                    // UX P2-15: a dot as the non-text cue; the line below says the same for screen readers
                    Box(Modifier.size(12.dp).clip(CircleShape).background(toneColor))
                    Spacer(Modifier.width(Spacing.m))
                }
                Column(Modifier.weight(1f).padding(vertical = Spacing.s)) {
                    RelayAddress(url, tagPrefix)
                    // a relay that reads and writes needs no note; a one-way relay says what it does
                    val mode = modes?.get(url)
                    if (mode != null && mode != RelayMode.BOTH) Text(relayModeLabel(mode), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (line != null && toneColor != null) {
                        val parts = listOfNotNull(
                            line.state.asString(),
                            line.lastSeenAgo?.let { stringResource(R.string.relay_last_seen, it.asString()) },
                            if (line.neverSeen) stringResource(R.string.relay_never_seen) else null,
                        )
                        Text(
                            parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = toneColor,
                            modifier = Modifier.testTag("$tagPrefix-status-${url.hashCode().toUInt()}"),
                        )
                    }
                }
                RelaySignupButton(url)
                if (modes != null && onMode != null) RelayModeMenu(url, modes[url] ?: RelayMode.BOTH, onMode)
                // UX P2-9: the outline trash of the line icons, named with its relay, and an undo after the removal
                IconButton(onClick = { remove(url, i) }, modifier = Modifier.testTag("$tagPrefix-remove-${url.hashCode().toUInt()}")) {
                    Icon(SettingsIcons.Trash, contentDescription = stringResource(R.string.relay_remove_named, relayShownUrl(url)), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (relays.isNotEmpty()) SettingsCardDivider()
        Row(Modifier.settingAnchor("$tagPrefix-input").padding(start = Spacing.l, end = Spacing.l, top = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newRelay, onValueChange = { newRelay = it }, modifier = Modifier.weight(1f).testTag("$tagPrefix-input"), singleLine = true,
                label = { Text(stringResource(R.string.settings_relay_placeholder)) }, isError = error != null, supportingText = { error?.let { Text(it.asString()) } },
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.width(Spacing.s))
            OutlinedButton(onClick = { if (onAdd(newRelay)) newRelay = "" }, enabled = newRelay.isNotBlank(), modifier = Modifier.testTag("$tagPrefix-add"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.action_add)) }
        }
    }
}

/** "Unlock" for a paid Monero relay: opens its page, where a one-time payment adds the npub to its write list. */
@Composable
private fun RelaySignupButton(url: String) {
    val page = RelaySuggestions.signupUrl(url) ?: return
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = { runCatching { uriHandler.openUri(page) } }, modifier = Modifier.testTag("relay-signup-${url.hashCode().toUInt()}")) {
        Text(stringResource(R.string.settings_relay_signup))
    }
}

/** Optional relays with a one-tap add, as a card under its label; hidden once every suggestion is in the list. */
@Composable
private fun SuggestedRelays(suggestions: List<RelaySuggestion>, onAdd: (String) -> Unit) {
    if (suggestions.isEmpty()) return
    SettingsGroup(stringResource(R.string.settings_suggested_relays)) {
        Text(
            stringResource(R.string.settings_suggested_relays_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.m, bottom = Spacing.xs),
        )
        suggestions.forEach { s ->
            SettingsCardDivider()
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = Spacing.l, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = Spacing.s)) {
                    Text(s.url, style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.settings_suggested_relay_fee, s.feeXmr), style = MaterialTheme.typography.labelSmall)
                }
                RelaySignupButton(s.url)
                IconButton(onClick = { onAdd(s.url) }, modifier = Modifier.testTag("settings-suggested-add-${s.url.hashCode().toUInt()}")) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add)) }
            }
        }
    }
}

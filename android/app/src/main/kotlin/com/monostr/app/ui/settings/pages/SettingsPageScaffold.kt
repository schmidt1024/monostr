package com.monostr.app.ui.settings.pages

import com.monostr.app.ui.settings.settingAnchor
import com.monostr.app.ui.settings.LocalSettingFocus
import com.monostr.app.ui.settings.LocalSettingFocusDone
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monostr.app.ui.settings.SettingsGroupLabel
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monostr.app.R
import com.monostr.app.ui.common.asString
import com.monostr.app.ui.settings.SettingsPage
import com.monostr.app.ui.settings.SettingsViewModel
import com.monostr.app.ui.theme.Spacing

/**
 * One settings subpage (spec 2026-11-11): the page title with back, a scrolling column with the paddings of the
 * former single page, and the snackbar for the shared view model's notices and Monero messages.
 * [focus] is the setting a search result opens the page at; controls marked with [settingAnchor] scroll to it and flash.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPageScaffold(page: SettingsPage, focus: String?, onBack: () -> Unit, vm: SettingsViewModel, content: @Composable ColumnScope.() -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    SettingsSnackbars(vm, snackbar)
    // once per page entry: rotation, Back from a child screen or process death re-enter composition but must not count again
    var drawnMarked by rememberSaveable { mutableStateOf(false) }
    // a deep link (Tor return, translate consent) may pass the overview before its first frame: the syncs start here then (idempotent)
    LaunchedEffect(page) {
        withFrameNanos { }
        if (!drawnMarked) { drawnMarked = true; com.monostr.nostr.PerfTrace.mark("settings_page_${page.id}_drawn") }
        vm.startSyncs()
    }
    // the search result's scroll and flash happen once per entry, not again on every return to the page
    var focusConsumed by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(page.title)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        CompositionLocalProvider(LocalSettingFocus provides focus.takeUnless { focusConsumed }, LocalSettingFocusDone provides { focusConsumed = true }, LocalSettingsSnackbar provides snackbar) {
            Column(Modifier.padding(padding).padding(Spacing.l).fillMaxSize().verticalScroll(rememberScrollState()).testTag("settings-page-${page.id}"), content = content)
        }
    }
}

/** The page's snackbar, for a page part with its own message (the relay editors' «Relay removed · Undo»). */
internal val LocalSettingsSnackbar = androidx.compose.runtime.staticCompositionLocalOf<SnackbarHostState?> { null }

/** The view model's notice (Amber renewal, relay list publish) and the Monero controller's message, each shown once. */
@Composable
internal fun SettingsSnackbars(vm: SettingsViewModel, snackbar: SnackbarHostState) {
    val message by vm.moneroMessage.collectAsStateWithLifecycle()
    val moneroMessage = message?.asString()
    LaunchedEffect(moneroMessage) { moneroMessage?.let { snackbar.showSnackbar(it); vm.monero.clearMessage() } }
    val notice by vm.notice.collectAsStateWithLifecycle()
    val noticeText = notice?.asString()
    LaunchedEffect(noticeText) { noticeText?.let { snackbar.showSnackbar(it); vm.clearNotice() } }
}

/** The grey label above a group's card; see [SettingsGroupLabel]. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier, first: Boolean = false) = SettingsGroupLabel(text, modifier, first)

/**
 * A row inside a card that opens something: title, an optional grey [subtitle], an optional [value] on the right (e.g. a
 * count) and a chevron. [titleColor] is for the danger zone; [anchor] is the search index key the row stands for.
 */
@Composable
internal fun SettingsLinkRow(
    title: String,
    value: String?,
    tag: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    titleColor: Color = Color.Unspecified,
    anchor: String? = null,
    chevron: Boolean = true,
    /** A 24 dp line icon before the title (e.g. the key rows), in the row's text colour. */
    leading: (@Composable () -> Unit)? = null,
    /** Null for a row that only shows a value (no ripple, no chevron). */
    onClick: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().then(if (anchor != null) Modifier.settingAnchor(anchor) else Modifier)
            .heightIn(min = 56.dp).then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.l, vertical = Spacing.m).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val alpha = if (enabled) 1f else 0.38f
        leading?.let {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { it() }
            Spacer(Modifier.width(Spacing.l))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = (if (titleColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else titleColor).copy(alpha = alpha))
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        value?.let {
            Spacer(Modifier.width(Spacing.s))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (chevron && onClick != null) {
            Spacer(Modifier.width(Spacing.s))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A titled switch row inside a card; the row itself (not just the thumb) toggles the value. */
@Composable
internal fun SettingSwitch(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().settingAnchor(tag).heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = Spacing.l, vertical = Spacing.m).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(Spacing.l))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A setting with a title, a grey hint and a control below (segmented buttons), inside a card. */
@Composable
internal fun SettingBlock(title: String, hint: String?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(Spacing.l)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(Spacing.s))
        content()
    }
}

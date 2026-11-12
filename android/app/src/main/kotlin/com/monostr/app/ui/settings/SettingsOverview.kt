package com.monostr.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import com.monostr.nostr.Npub
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monostr.app.R
import com.monostr.app.ui.common.Avatar
import com.monostr.app.ui.common.rememberTorChip
import com.monostr.app.ui.settings.pages.SettingsSnackbars
import com.monostr.app.ui.theme.SettingsIcons
import com.monostr.app.ui.theme.Spacing
import com.monostr.app.ui.theme.StatusColors
import com.monostr.app.ui.theme.TabIcons
import com.monostr.app.ui.theme.TipCoinOutline
import com.monostr.nostr.model.Profile

/**
 * The start of Settings (spec 2026-11-11): search field, the account card, one row per category with its status chip,
 * log out. Opened at a [section] (a [SettingsSection], e.g. the Tor return's `privacy`), it opens that page once on top
 * of itself, so back comes here. The visit's syncs start after the first frame, never holding it back. It collects only
 * the profile, the relay chip, the Monero chip and the logout state; heavy state belongs to the pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsOverview(
    section: String?,
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onOpen: (SettingsPage, String?) -> Unit,
    onLogout: () -> Unit,
    onRedirect: (SettingsPage) -> Unit = { onOpen(it, null) },
    /** Sign out's «Show key» (key login): opens the key screen; the caller grants [ShowKeyGate] only when it navigates. */
    onShowKey: () -> Unit = {},
) {
    var redirected by rememberSaveable { mutableStateOf(false) }
    // the Monero chip names the public address: another client may have changed it while the app was away
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.monero.resumed() }
    LaunchedEffect(section) {
        if (redirected) return@LaunchedEffect
        redirected = true
        SettingsPage.of(section)?.let(onRedirect)
    }
    LaunchedEffect(Unit) { withFrameNanos { }; com.monostr.nostr.PerfTrace.mark("settings_overview_drawn"); vm.startSyncs() }
    val snackbar = remember { SnackbarHostState() }
    SettingsSnackbars(vm, snackbar)
    var query by rememberSaveable { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.settings_title)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SettingsSearchField(query, { query = it }, Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s))
            val resolved = rememberResolvedIndex()
            val availability by vm.searchAvailability.collectAsStateWithLifecycle()
            val results = remember(query, resolved, availability) { searchSettings(query, availableEntries(resolved, availability)) }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.l)) {
                if (query.isNotBlank()) {
                    SearchResults(results, onOpen)
                } else {
                    AccountCard(vm) { onOpen(SettingsPage.ACCOUNT, null) }
                    // round 2 (owner decision 1): the categories in three cards like Android 16 settings, log out outside them
                    // wave B review: at font 1.3 a chip beside the text cut «Tor y privaci…» and the subtitle in es/fr
                    val largeFont = LocalDensity.current.fontScale > LARGE_FONT_SCALE
                    OVERVIEW_GROUPS.forEach { group ->
                        Spacer(Modifier.height(Spacing.m))
                        SettingsCard {
                            group.forEachIndexed { i, page ->
                                if (i > 0) SettingsCardDivider(SETTINGS_ICON_INSET)
                                val chip = pageChip(page, vm)
                                val title = stringResource(page.title)
                                val subtitle = page.subtitle?.let { stringResource(it) }
                                SettingsRow(
                                    icon = { PageIcon(page) },
                                    title = title,
                                    subtitle = subtitle,
                                    // a large font: the chip goes under the text, so title and subtitle keep the row's width
                                    trailing = { if (chip != null && !largeFont) StatusChip(chip.text, chip.tone) else RowChevron() },
                                    below = if (chip != null && largeFont) { { StatusChip(chip.text, chip.tone) } } else null,
                                    onClick = { onOpen(page, null) },
                                    // UX P1-4: one node read as words («Relays, 3 of 4 connected»), not «3/4» on its own
                                    description = chip?.let { listOfNotNull(title, it.spoken, subtitle).joinToString(", ") },
                                    modifier = Modifier.testTag("settings-cat-${page.id}"),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.m))
                    LogoutRow(vm, onLogout, onShowKey)
                    Spacer(Modifier.size(Spacing.xl))
                }
            }
        }
    }
}

/** The index in the app's current language; rebuilt when the locale changes, not on every keystroke. */
@Suppress("LocalContextGetResourceValueCall") // remember is keyed on the locales, so the strings are rebuilt on a language change
@Composable
private fun rememberResolvedIndex(): List<ResolvedEntry> {
    val context = LocalContext.current
    val locales = LocalConfiguration.current.locales
    return remember(locales) {
        SETTINGS_INDEX.map { e ->
            ResolvedEntry(e, context.getString(e.title), e.hint?.let { context.getString(it) }.orEmpty(), context.getString(e.page.title))
        }
    }
}

/** One row per hit: the setting's name, the page it lives on underneath; a tap opens that page at the setting. */
@Composable
private fun SearchResults(results: List<ResolvedEntry>, onOpen: (SettingsPage, String?) -> Unit) {
    if (results.isEmpty()) {
        Text(
            stringResource(R.string.settings_search_none), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = Spacing.xl).testTag("settings-search-none"),
        )
        return
    }
    Spacer(Modifier.height(Spacing.xs))
    SettingsCard {
      results.forEachIndexed { i, r ->
        if (i > 0) SettingsCardDivider(SETTINGS_ICON_INSET)
        val isPage = r.entry.key.startsWith("page-")
        // UX P2-6: a page result says what is on it (its overview subtitle), a second-level page names its parent
        val parentTitle = r.entry.page.parent?.let { stringResource(it.title) }
        SettingsRow(
            icon = { ResultIcon(r.entry.page) },
            title = r.title,
            subtitle = if (isPage) r.hint.ifEmpty { parentTitle } else r.pageTitle,
            trailing = null,
            onClick = { onOpen(r.entry.page, if (isPage) null else r.entry.key) },
            modifier = Modifier.testTag("settings-result-${r.entry.key}"),
        )
      }
    }
}

@Composable
private fun SettingsSearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    TextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.settings_search_hint)) },
        leadingIcon = { Icon(TabIcons.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            { IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear)) } }
        } else null,
        // round 2 (UX P2-7): a full pill in its own tone, so it does not read as one more settings card
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.outlineVariant, unfocusedContainerColor = MaterialTheme.colorScheme.outlineVariant,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("settings-search"),
    )
}

@Composable
private fun AccountCard(vm: SettingsViewModel, onClick: () -> Unit) {
    SettingsCard {
        AccountSummaryRow(vm, Modifier.clickable(role = Role.Button, onClick = onClick).testTag("settings-account")) {
            Icon(SettingsIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The logged-in account as one row: avatar (or a person glyph), name and short npub. Without a name the npub is the title,
 * or [noName] is when given (Account page, UX P2-12) with the npub under it. [trailing] is the chevron or a copy button.
 */
@Composable
internal fun AccountSummaryRow(vm: SettingsViewModel, modifier: Modifier = Modifier, noName: String? = null, trailing: @Composable () -> Unit) {
    val profile by vm.ownProfile.collectAsStateWithLifecycle()
    Row(modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
        if (profile.picture.isNullOrBlank()) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Avatar(profile.picture, size = 48.dp)
        }
        Spacer(Modifier.width(Spacing.m))
        val npub = remember(vm.pubkey) { runCatching { Npub.short(vm.pubkey) }.getOrDefault(Profile.shortPubkey(vm.pubkey)) }
        val named = !profile.displayName.isNullOrBlank() || !profile.name.isNullOrBlank()
        Column(Modifier.weight(1f)) {
            val title = if (named) profile.shownName else noName
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(npub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            } else {
                Text(npub, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(Spacing.s))
        trailing()
    }
}

@Composable
private fun ResultIcon(page: SettingsPage) {
    val top = page.parent ?: page
    if (top == SettingsPage.ACCOUNT) Icon(Icons.Filled.Person, null, tint = MaterialTheme.colorScheme.onSurface) else PageIcon(top)
}

@Composable
private fun PageIcon(page: SettingsPage) {
    val tint = MaterialTheme.colorScheme.onSurface
    when (page) {
        SettingsPage.PRIVACY -> Icon(painterResource(R.drawable.ic_tor_onion_outline), null, tint = tint)
        SettingsPage.RELAYS -> Icon(SettingsIcons.Relays, null, tint = tint)
        SettingsPage.MONERO -> Icon(TipCoinOutline, null, tint = tint)
        SettingsPage.NOTIFICATIONS -> Icon(TabIcons.Bell, null, tint = tint)
        SettingsPage.CONTENT -> Icon(SettingsIcons.Image, null, tint = tint)
        SettingsPage.TRANSLATION -> Icon(SettingsIcons.Globe, null, tint = tint)
        SettingsPage.APPEARANCE -> Icon(SettingsIcons.Sun, null, tint = tint)
        else -> Icon(SettingsIcons.Help, null, tint = tint)
    }
}

/** A category's status chip: what it shows, what a screen reader says, its tone. */
private class PageChip(val text: String, val spoken: String, val tone: ChipTone)

/** The chip of a category with a live status, null for a plain chevron. Each status is read only by its own row. */
@Composable
private fun pageChip(page: SettingsPage, vm: SettingsViewModel): PageChip? = when (page) {
    SettingsPage.PRIVACY -> {
        val chip = rememberTorChip()
        val text = stringResource(chip.text)
        PageChip(text, text, chip.tone)
    }
    SettingsPage.RELAYS -> {
        val chip by vm.relayStatus.collectAsStateWithLifecycle()
        chip?.takeIf { it.total > 0 }?.let { c ->
            PageChip(stringResource(R.string.settings_chip_relays, c.connected, c.total), stringResource(R.string.settings_chip_relays_spoken, c.connected, c.total), c.tone)
        }
    }
    SettingsPage.MONERO -> {
        val chip by vm.moneroChip.collectAsStateWithLifecycle()
        when (chip) {
            MoneroChip.READY -> stringResource(R.string.settings_chip_monero_ready).let { PageChip(it, it, ChipTone.OK) }
            MoneroChip.ACTIVE -> stringResource(R.string.settings_chip_monero_active).let { PageChip(it, it, ChipTone.OK) }
            MoneroChip.NONE -> null
        }
    }
    else -> null
}

@Composable
private fun RowChevron() = Icon(SettingsIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)

/**
 * Sign out, outside the cards (UX P1-5): now one tap from the top level, so it asks first. For a key login the question
 * warns that the key may exist only here and offers «Show key» (straight to the key screen) instead of Cancel.
 */
@Composable
private fun LogoutRow(vm: SettingsViewModel, onLogout: () -> Unit, onShowKey: () -> Unit) {
    val busy by vm.logoutBusy.collectAsStateWithLifecycle()
    val red = MaterialTheme.colorScheme.error
    var asking by rememberSaveable { mutableStateOf(false) }
    if (asking) {
        SettingsConfirmDialog(
            title = stringResource(R.string.settings_logout_q),
            text = if (vm.isAmber) null else stringResource(R.string.settings_logout_key_warning),
            confirm = stringResource(R.string.settings_logout), confirmTag = "settings-logout-confirm", destructive = true,
            onConfirm = { asking = false; vm.logout(onLogout) },
            onDismiss = { asking = false },
            dismissButton = if (vm.isAmber) null else {
                {
                    TextButton(onClick = { asking = false; onShowKey() }, modifier = Modifier.testTag("settings-logout-show-key")) {
                        Text(stringResource(R.string.settings_logout_show_key))
                    }
                }
            },
        )
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !busy, role = Role.Button) { asking = true }.padding(horizontal = Spacing.l, vertical = Spacing.m).testTag("settings-logout"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = red) else Icon(SettingsIcons.LogOut, null, tint = red)
        }
        Spacer(Modifier.width(Spacing.l))
        Text(stringResource(R.string.settings_logout), style = MaterialTheme.typography.bodyLarge, color = red)
    }
}

/** Above this font scale the overview's status chips sit under the row text (1.15 lets the default and 1.1 keep them beside). */
private const val LARGE_FONT_SCALE = 1.15f

/** One settings row: a line [icon], [title], an optional grey one-line [subtitle], and [trailing] (a chip or chevron). */
@Composable
fun SettingsRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    trailing: @Composable (() -> Unit)?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** What a screen reader says for the whole row instead of its texts (rows with a status chip); the texts are not read again. */
    description: String? = null,
    /** Shown under the subtitle (a status chip when the font is too large for it beside the text). */
    below: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.clearAndSetSemantics { contentDescription = description; role = Role.Button } else Modifier)
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(Spacing.l))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            below?.let {
                Spacer(Modifier.height(Spacing.xs))
                it()
            }
        }
        trailing?.let {
            Spacer(Modifier.width(Spacing.s))
            it()
        }
    }
}

/** A small pill: green when [ChipTone.OK], amber for [ChipTone.WARN], grey otherwise; readable in light and dark. */
@Composable
fun StatusChip(text: String, tone: ChipTone) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val fg = when (tone) {
        ChipTone.OK -> Color(if (dark) StatusColors.DarkOk else StatusColors.LightOk)
        ChipTone.WARN -> Color(if (dark) StatusColors.DarkWarn else StatusColors.LightWarn)
        ChipTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // the neutral chip sits on a card (surfaceContainer): one step darker/lighter than it
    val bg = if (tone == ChipTone.NEUTRAL) MaterialTheme.colorScheme.outlineVariant else fg.copy(alpha = 0.15f)
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        maxLines = 1,
        modifier = Modifier.background(bg, MaterialTheme.shapes.small).padding(horizontal = Spacing.s, vertical = Spacing.xxs),
    )
}

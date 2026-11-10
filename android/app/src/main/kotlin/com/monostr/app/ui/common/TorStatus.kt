package com.monostr.app.ui.common

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.monostr.app.R
import com.monostr.app.net.TorState
import com.monostr.app.net.TorSwitchError
import com.monostr.app.net.TorTurn
import com.monostr.app.net.TorUi
import com.monostr.app.ui.theme.Spacing
import com.monostr.nostr.model.RelayState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** No Tor at all: for a composable shown outside [ProvideTorUi], as in tests. */
private object NoTorUi : TorUi {
    override val active: StateFlow<Boolean> = MutableStateFlow(false)
    override val state: StateFlow<TorState?> = MutableStateFlow(null)
    override val turning: StateFlow<TorTurn?> = MutableStateFlow(null)
    override val error: StateFlow<TorSwitchError?> = MutableStateFlow(null)
    override fun turnOn() {}
    override fun turnOff() {}
    override fun retry() = false
    override fun clearError() {}
    override suspend fun tipsToDrop() = 0
}

/** Never changes while the app runs. */
private val LocalTorUi = staticCompositionLocalOf<TorUi> { NoTorUi }

/** The route is Tor. With the switch off this boolean is all the feed reads about Tor: no flow, no controller. */
val LocalTorActive = compositionLocalOf { false }

/** The explanation, Details and the question before off: one each, above every screen. */
@Stable
class TorSheets(explain: MutableState<Boolean>, details: MutableState<Boolean>, confirmOff: MutableState<Boolean>, returnTo: MutableState<String?>, private val forRestart: (String?) -> Unit = {}) {
    var explain by explain
    var details by details
    var confirmOff by confirmOff
    /** Set when a switch is asked for, taken by the next nav host, see [TorReturnEffect]. */
    var returnTo by returnTo
    /** What [returnTo] would be now; the nav host keeps it current. */
    var returnRoute: String? = null

    fun armReturn() {
        returnTo = returnRoute
        forRestart(returnRoute)
    }

    /** Some retries are a whole switch, and one may end the process before it returns. */
    fun retry(ui: TorUi) {
        val route = returnRoute
        forRestart(route)
        if (ui.retry()) returnTo = route
    }
}

val LocalTorSheets = staticCompositionLocalOf { TorSheets(mutableStateOf(false), mutableStateOf(false), mutableStateOf(false), mutableStateOf(null)) }

/**
 * A switch rebuilds the session and so the nav host: the new host goes back where the switch was asked for, once. A switch that ends with
 * the host still standing rebuilt nothing, so what it armed must not catch a later, unrelated start of the session. Watches only while armed.
 */
@Composable
fun TorReturnEffect(navigate: (String) -> Unit) {
    val ui = LocalTorUi.current
    val sheets = LocalTorSheets.current
    val latest by rememberUpdatedState(navigate)
    LaunchedEffect(Unit) {
        sheets.returnTo?.let {
            sheets.returnTo = null
            latest(it)
        }
    }
    if (sheets.returnTo != null) {
        LaunchedEffect(Unit) {
            launch { ui.error.collect { if (it != null) sheets.returnTo = null } }
            ui.turning.collect { if (it == null) sheets.returnTo = null }
        }
    }
}

/** In the logged-out branch: a route of the old account is no place for the next login to go. */
@Composable
fun TorReturnForget() {
    val sheets = LocalTorSheets.current
    LaunchedEffect(Unit) { sheets.returnTo = null }
}

/** At the root of the app: what the Tor composables below read, and the sheets they open. */
@Composable
fun ProvideTorUi(ui: TorUi, content: @Composable () -> Unit) {
    val active by ui.active.collectAsState()
    val explain = rememberSaveable { mutableStateOf(false) }
    val details = rememberSaveable { mutableStateOf(false) }
    val confirmOff = rememberSaveable { mutableStateOf(false) }
    val returnTo = rememberSaveable { mutableStateOf<String?>(null) }
    val sheets = remember {
        // a route from the process before a restart is taken like one armed in this process: the first nav host goes there
        ui.takeRestartReturn()?.let { returnTo.value = it }
        TorSheets(explain, details, confirmOff, returnTo, ui::armRestartReturn)
    }
    CompositionLocalProvider(LocalTorUi provides ui, LocalTorSheets provides sheets, LocalTorActive provides active) {
        content()
        TorSheetsHost(ui, sheets, active)
    }
}

@Composable
private fun TorSheetsHost(ui: TorUi, sheets: TorSheets, active: Boolean) {
    // the gate is the truth: a switch that went through closes what asked for it, one that failed leaves it open with the error
    LaunchedEffect(active) {
        if (!active) { sheets.details = false; sheets.confirmOff = false }
    }
    if (sheets.explain) {
        TorExplainDialog(
            restarts = ui.restartsOnSwitch,
            onConfirm = { sheets.explain = false; sheets.armReturn(); ui.turnOn() },
            onCancel = { sheets.explain = false },
        )
    }
    if (active && sheets.details) {
        val state by ui.state.collectAsState()
        val turning by ui.turning.collectAsState()
        val error by ui.error.collectAsState()
        TorDetailsSheet(
            state = state, turning = turning, error = error, onRetry = { sheets.retry(ui) }, onTurnOff = { sheets.confirmOff = true },
            onDismiss = { sheets.details = false; ui.clearError() },
        )
    }
    if (active && sheets.confirmOff) {
        val count by produceState<Int?>(null, ui) {
            value = try {
                ui.tipsToDrop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // not known: no line rather than «no tips»
            }
        }
        TorOffDialog(
            tips = count,
            onConfirm = { sheets.confirmOff = false; sheets.armReturn(); ui.turnOff() },
            onCancel = { sheets.confirmOff = false },
        )
    }
}

/** From the system's settings (developer options and accessibility both end up here). */
fun reducedAnimations(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

private const val PULSE_MS = 900
private const val PULSE_LOW = 0.35f

/**
 * Own drawing from layers, as one tint per drawable: outline with rings, the same filled with rings in [background], or struck through.
 * Colours as the relay dot's, so connected is the same green.
 */
@Composable
private fun TorGlyph(state: TorState, background: Color, reduced: Boolean, modifier: Modifier = Modifier) {
    when (state) {
        TorState.Connected -> {
            val color = RelayState.CONNECTED.statusColor()
            Box(modifier.size(24.dp)) {
                Layer(R.drawable.ic_tor_onion_fill, color)
                Layer(R.drawable.ic_tor_onion_rings, background)
            }
        }
        is TorState.Connecting -> {
            val color = RelayState.CONNECTING.statusColor()
            if (reduced) {
                Outline(color, modifier)
            } else {
                val alpha by rememberInfiniteTransition(label = "tor").animateFloat(
                    1f, PULSE_LOW, infiniteRepeatable(tween(PULSE_MS, easing = EaseInOut), RepeatMode.Reverse), label = "tor-alpha",
                )
                // in the draw phase: a pulse recomposes nothing
                Outline(color, modifier.graphicsLayer { this.alpha = alpha })
            }
        }
        else -> {
            val color = RelayState.DISCONNECTED.statusColor()
            Box(modifier.size(24.dp)) {
                Outline(color)
                Layer(R.drawable.ic_tor_onion_strike_gap, background)
                Layer(R.drawable.ic_tor_onion_strike, color)
            }
        }
    }
}

@Composable
private fun Outline(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(24.dp)) {
        Layer(R.drawable.ic_tor_onion_outline, color)
        Layer(R.drawable.ic_tor_onion_rings, color)
    }
}

@Composable
private fun Layer(res: Int, color: Color) {
    Icon(painterResource(res), contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
}

/** 24 dp in a 48 dp target (spec 2026-11-06 5.5), for the bar: the rings cut out in the bar's colour. */
@Composable
fun TorIcon(state: TorState, onClick: () -> Unit, reduced: Boolean = rememberReducedAnimations()) {
    val description = stringResource(
        when (state) {
            is TorState.Connecting -> R.string.tor_desc_connecting
            TorState.Connected -> R.string.tor_desc_connected
            else -> R.string.tor_desc_failed
        },
    )
    IconButton(onClick = onClick, modifier = Modifier.testTag("tor-icon").semantics { contentDescription = description }) {
        TorGlyph(state, MaterialTheme.colorScheme.surface, reduced)
    }
}

@Composable
private fun rememberReducedAnimations(): Boolean {
    val context = LocalContext.current
    return remember { reducedAnimations(context) }
}

/** The bar's slot, called with the switch on only. */
@Composable
fun TorBarIcon() {
    val ui = LocalTorUi.current
    val sheets = LocalTorSheets.current
    val state by ui.state.collectAsState()
    state?.let { TorIcon(it, onClick = { sheets.details = true }) }
}

/** Under the bar of a top-level screen (spec 5.5). With the switch off a boolean read and nothing else. */
@Composable
fun TorBannerSlot() {
    if (LocalTorActive.current) TorBannerLive()
}

@Composable
private fun TorBannerLive() {
    val ui = LocalTorUi.current
    val sheets = LocalTorSheets.current
    val state by ui.state.collectAsState()
    val turning by ui.turning.collectAsState()
    val error by ui.error.collectAsState()
    val reduced = rememberReducedAnimations()
    // the list below must not jump when it comes and goes
    Box(if (reduced) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().animateContentSize()) {
        val now = state
        // connected and fine: nothing to say
        if (now != null && (now != TorState.Connected || error != null)) {
            TorBanner(now, error, turning, onRetry = { sheets.retry(ui) }, onDetails = { sheets.details = true })
        }
    }
}

/** The whole banner opens Details. */
@Composable
fun TorBanner(state: TorState, error: TorSwitchError?, turning: TorTurn?, onRetry: () -> Unit, onDetails: () -> Unit, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.surfaceContainerHigh
    val detailsLabel = stringResource(R.string.tor_details)
    Column(
        modifier
            .fillMaxWidth()
            .background(background)
            .clickable(role = Role.Button, onClickLabel = detailsLabel, onClick = onDetails)
            .padding(horizontal = Spacing.l, vertical = if (state == TorState.Failed) Spacing.xs else Spacing.s)
            .testTag("tor-banner"),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (state != TorState.Connected) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TorGlyph(state, background, rememberReducedAnimations())
                Spacer(Modifier.width(Spacing.m))
                Text(
                    stringResource(stateLine(state)), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).testTag("tor-banner-state").semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (state == TorState.Failed) {
                    TextButton(
                        onClick = onRetry, enabled = turning == null, contentPadding = PaddingValues(horizontal = Spacing.s),
                        modifier = Modifier.testTag("tor-retry"),
                    ) { Text(stringResource(R.string.tor_try_again_short)) }
                }
                BannerChevron()
            }
            if (error != null) TorErrorLine(error)
        } else if (error != null) {
            // Tor is fine and a switch was not: the way to act on it is in Details, like the state above
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { TorErrorLine(error) }
                BannerChevron()
            }
        }
    }
}

/** On some screens the banner is the only way to Details, and a text does not look like one. */
@Composable
private fun BannerChevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp).testTag("tor-banner-chevron"),
    )
}

private fun stateLine(state: TorState): Int = when (state) {
    is TorState.Connecting -> R.string.tor_banner_connecting
    TorState.Connected -> R.string.tor_connected
    else -> R.string.tor_banner_failed
}

@Composable
private fun TorErrorLine(error: TorSwitchError) {
    Text(
        stringResource(if (error == TorSwitchError.ON_FAILED) R.string.tor_error_on else R.string.tor_error_off),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("tor-error").semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** What Tor is doing and the deliberate way out, which always asks first (spec 5.4). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorDetailsSheet(state: TorState?, turning: TorTurn?, error: TorSwitchError?, onRetry: () -> Unit, onTurnOff: () -> Unit, onDismiss: () -> Unit) {
    GuardedModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("tor-sheet")) {
        val uriHandler = LocalUriHandler.current
        TorDetailsContent(state, turning, error, onRetry, onTurnOff, onCreditLink = { uriHandler.openUri(TOR_PROJECT_URL) })
    }
}

private const val TOR_PROJECT_HOST = "torproject.org"
private const val TOR_PROJECT_URL = "https://$TOR_PROJECT_HOST"

/** The body of [TorDetailsSheet]. It scrolls, and the credit comes last, so it cannot take a button's place on a small screen. */
@Composable
fun TorDetailsContent(state: TorState?, turning: TorTurn?, error: TorSwitchError?, onRetry: () -> Unit, onTurnOff: () -> Unit, onCreditLink: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        if (state != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TorGlyph(state, MaterialTheme.colorScheme.surfaceContainerLow, rememberReducedAnimations())
                Spacer(Modifier.width(Spacing.m))
                Text(stringResource(stateLine(state)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
        }
        val progress = (state as? TorState.Connecting)?.progress
        if (progress != null) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().testTag("tor-progress"))
            Text(stringResource(R.string.tor_details_progress, progress), style = MaterialTheme.typography.bodySmall)
        }
        if (turning != null) Text(stringResource(turningLine(turning)), style = MaterialTheme.typography.bodySmall)
        if (error != null) TorErrorLine(error)
        Text(stringResource(R.string.tor_explanation), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state == TorState.Failed) {
            // what helps comes first, the way to direct stays the quiet one
            Button(onClick = onRetry, enabled = turning == null, modifier = Modifier.fillMaxWidth().testTag("tor-retry-sheet"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.tor_try_again)) }
            TextButton(onClick = onTurnOff, enabled = turning == null, modifier = Modifier.fillMaxWidth().testTag("tor-off")) { Text(stringResource(R.string.tor_off_action)) }
        } else {
            OutlinedButton(onClick = onTurnOff, enabled = turning == null, modifier = Modifier.fillMaxWidth().testTag("tor-off"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.tor_off_action)) }
        }
        Column {
            Text(stringResource(R.string.tor_credit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("tor-credit"))
            Text(
                TOR_PROJECT_HOST, style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline), color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.heightIn(min = 48.dp).clickable(onClick = onCreditLink).wrapContentHeight().testTag("tor-credit-link"),
            )
        }
    }
}

private fun turningLine(turning: TorTurn): Int = if (turning == TorTurn.ON) R.string.tor_turning_on else R.string.tor_turning_off

@Composable
private fun TorExplainDialog(restarts: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    GuardedAlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.tor_connect)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(stringResource(R.string.tor_explanation), modifier = Modifier.testTag("tor-explain"))
                // only when it will: the login row switches in place and the typed key stays
                if (restarts) Text(stringResource(R.string.tor_restart_once), modifier = Modifier.testTag("tor-explain-restart"))
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("tor-explain-confirm")) { Text(stringResource(R.string.tor_turn_on)) } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.testTag("tor-explain-cancel")) { Text(stringResource(R.string.tor_cancel)) } },
    )
}

@Composable
private fun TorOffDialog(tips: Int?, onConfirm: () -> Unit, onCancel: () -> Unit) {
    GuardedAlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.tor_off_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(stringResource(R.string.tor_off_text))
                if (tips != null && tips > 0) Text(pluralStringResource(R.plurals.tor_off_tips, tips, tips), modifier = Modifier.testTag("tor-off-count"))
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("tor-off-confirm")) { Text(stringResource(R.string.tor_off_button)) } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.testTag("tor-off-cancel")) { Text(stringResource(R.string.tor_cancel)) } },
    )
}

/**
 * The row in the Privacy section. Off: the explanation first. On: the question first. While a switch runs it is disabled and says which way;
 * connecting, connected and failed as in the banner, failed with «Try again» and Details. Orbot's line stands there always, nothing is detected.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TorSettingsRow() {
    val ui = LocalTorUi.current
    val sheets = LocalTorSheets.current
    val active = LocalTorActive.current
    val turning by ui.turning.collectAsState()
    val error by ui.error.collectAsState()
    val state by ui.state.collectAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = active, enabled = turning == null, role = Role.Switch, onValueChange = { if (it) sheets.explain = true else sheets.confirmOff = true })
            .padding(vertical = Spacing.s)
            .testTag("settings-tor"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.tor_connect), style = MaterialTheme.typography.bodyLarge)
            val now = state
            val hint = when {
                turning != null -> turningLine(turning!!)
                // under the title «Connect through Tor» one word is enough
                active && now == TorState.Connected -> R.string.tor_settings_connected
                active && now != null -> stateLine(now)
                else -> null
            }
            if (hint != null) Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(Spacing.l))
        Switch(checked = active, onCheckedChange = null, enabled = turning == null)
    }
    error?.let { TorErrorLine(it) }
    if (active && state == TorState.Failed && turning == null) {
        FlowRow {
            TextButton(onClick = { sheets.retry(ui) }, modifier = Modifier.testTag("tor-retry")) { Text(stringResource(R.string.tor_try_again)) }
            TextButton(onClick = { sheets.details = true }, modifier = Modifier.testTag("tor-details")) { Text(stringResource(R.string.tor_details)) }
        }
    }
    Text(stringResource(R.string.tor_orbot), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("settings-tor-orbot"))
}

/**
 * The quiet row on the login and create-account screens (spec 5.2): needs no session, the gate works without one. It says what Tor does in
 * one short line and keeps its height in every state, so the centred screen does not jump; the tap opens the explanation or Details.
 */
@Composable
fun TorLoginRow() {
    val ui = LocalTorUi.current
    val sheets = LocalTorSheets.current
    val active = LocalTorActive.current
    val turning by ui.turning.collectAsState()
    val error by ui.error.collectAsState()
    val state by ui.state.collectAsState()
    val now = state.takeIf { active }
    Column(Modifier.fillMaxWidth().testTag("login-tor")) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(enabled = turning == null, role = Role.Button) { if (active) sheets.details = true else sheets.explain = true }
                .padding(horizontal = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (now != null) {
                TorGlyph(now, MaterialTheme.colorScheme.surface, rememberReducedAnimations())
                Spacer(Modifier.width(Spacing.m))
            }
            val line = when {
                turning != null -> turningLine(turning!!)
                now == null -> R.string.tor_connect
                now == TorState.Connected -> R.string.tor_connected
                now == TorState.Failed -> R.string.tor_login_failed
                else -> R.string.tor_login_connecting
            }
            Text(
                stringResource(line), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (now == TorState.Failed) {
                Spacer(Modifier.width(Spacing.xs))
                TextButton(onClick = { sheets.retry(ui) }, enabled = turning == null, modifier = Modifier.testTag("tor-retry")) { Text(stringResource(R.string.tor_try_again_short)) }
            }
        }
        error?.let { TorErrorLine(it) }
    }
}

/** What an empty list may say: «no notes» is not known while Tor is down. With the switch off a boolean read and the usual text. */
@Composable
fun torAwareEmptyText(res: Int): String {
    if (!LocalTorActive.current) return stringResource(res)
    val state by LocalTorUi.current.state.collectAsState()
    return stringResource(if (state == TorState.Connected) res else R.string.feed_empty_tor)
}

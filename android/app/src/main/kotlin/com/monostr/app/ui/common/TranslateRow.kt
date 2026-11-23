package com.monostr.app.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.monostr.app.R
import com.monostr.app.translate.DeviceAppState
import com.monostr.app.translate.DeviceBackend
import com.monostr.app.translate.DeviceTranslator
import com.monostr.app.translate.TranslateServer
import com.monostr.app.translate.TranslationMode
import com.monostr.app.translate.translateBackend
import com.monostr.app.translate.TranslationSettingsStore
import com.monostr.app.translate.TranslationState
import com.monostr.app.translate.Translations
import com.monostr.app.ui.theme.Spacing
import com.monostr.nostr.StartupGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** What a note card needs to offer a translation (spec 2026-11-03 «UI»); provided once by the logged-in nav host. */
interface NoteTranslator {
    fun state(noteId: String): StateFlow<TranslationState>
    fun prepare(noteId: String, content: String, label: String?)
    /** The tap on «Translate» / «Retry»: translates, or first asks for consent (nothing is sent before it). */
    fun translate(noteId: String)
    fun showOriginal(noteId: String)
    fun showTranslation(noteId: String)
    /** Runs [action] (an article's «Translate») at once when the consent was given, else after «Translate» in the same sheet. */
    fun withConsent(action: () -> Unit)
}

/** Null outside the logged-in nav host (isolated UI tests) and so in every card there: no button. */
val LocalNoteTranslator = staticCompositionLocalOf<NoteTranslator?> { null }

/** The app-wide [Translations] for the note cards, plus the one-time consent sheet the nav host shows. */
@HiltViewModel
class NoteTranslationViewModel @Inject constructor(
    private val translations: Translations,
    private val settings: TranslationSettingsStore,
    private val device: DeviceTranslator,
) : ViewModel(), NoteTranslator {
    private val _consentFor = MutableStateFlow<String?>(null)
    /** The note (or [ARTICLE]) whose «Translate» waits for the consent sheet; null while no sheet shows. */
    val consentFor: StateFlow<String?> = _consentFor.asStateFlow()
    /** What «Translate» in the sheet runs. */
    private var pending: (() -> Unit)? = null
    private val _deviceState = MutableStateFlow(DeviceAppState.MISSING)
    /** Whether the sheet offers «Use Offline Translator instead» (READY) or «Update» (TOO_OLD): checked each time the sheet is about to show, never on the feed path. */
    val deviceState: StateFlow<DeviceAppState> = _deviceState.asStateFlow()

    /** The host the consent sheet names: the one a translation would go to. */
    val host: StateFlow<String> = combine(settings.mode, settings.customUrl) { mode, url ->
        translateBackend(mode, url)?.label ?: TranslateServer.MONOSTR.host
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TranslateServer.MONOSTR.host)

    /** The logged-in session's startup gate: no language detection before the feed's first page (final review I2). */
    fun useStartupGate(gate: StartupGate) = translations.useStartupGate(gate)

    override fun state(noteId: String) = translations.state(noteId)
    override fun prepare(noteId: String, content: String, label: String?) = translations.prepare(noteId, content, label)
    override fun showOriginal(noteId: String) = translations.showOriginal(noteId)
    override fun showTranslation(noteId: String) = translations.showTranslation(noteId)

    override fun translate(noteId: String) = ask(noteId) { viewModelScope.launch { translations.translate(noteId) } }

    override fun withConsent(action: () -> Unit) = ask(ARTICLE, action)

    private fun ask(key: String, action: () -> Unit) {
        viewModelScope.launch {
            // read at the tap, never from a seeded default: a late-loading «true» must not be needed, a «false» must hold
            if (settings.consentGiven.first()) action() else {
                val state = withContext(Dispatchers.Default) { device.appState() }
                _deviceState.value = state
                settings.deviceAppChecked(state == DeviceAppState.READY) // an install after startup also moves the default mode
                pending = action
                _consentFor.value = key
            }
        }
    }

    /** «Translate» in the sheet: the consent is stored, then the waiting note or article is sent. */
    fun consent() {
        val action = pending ?: return
        pending = null
        _consentFor.value = null
        viewModelScope.launch {
            settings.setConsentGiven()
            action()
        }
    }

    /**
     * «Use Offline Translator instead»: the mode becomes DEVICE, then the waiting note or article is translated on the
     * device. It waits until the notes use the device (the stored mode reaches them a moment later), so nothing goes to
     * the server the sheet named; no server consent is stored.
     */
    fun useDevice() {
        val action = pending ?: return
        pending = null
        _consentFor.value = null
        viewModelScope.launch {
            settings.setMode(TranslationMode.DEVICE)
            withTimeoutOrNull(DEVICE_SWITCH_TIMEOUT_MS) { translations.awaitBackend(DeviceBackend) } ?: return@launch
            action()
        }
    }

    fun dismissConsent() { pending = null; _consentFor.value = null }

    companion object {
        /** [consentFor] while an article's «Translate» waits. */
        const val ARTICLE = "article"
        private const val DEVICE_SWITCH_TIMEOUT_MS = 5_000L
    }
}

/**
 * The card's translation state for [noteId] with [content] (the text the card shows). The flow is fetched per id each
 * time the card enters composition, and [NoteTranslator.prepare] runs right there with it (no coroutine of its own: a
 * no-op for a known note, a map entry while translation is off; detection happens off the main thread).
 */
@Composable
fun rememberTranslation(noteId: String, content: String, label: String?): TranslationState {
    val translator = LocalNoteTranslator.current ?: return TranslationState.None
    // keyed by the locales too: a switched app language re-reads the target, so a translation for the old one goes
    val locales = LocalConfiguration.current.locales.toLanguageTags()
    val flow = remember(translator, noteId, locales) {
        translator.prepare(noteId, content, label)
        translator.state(noteId)
    }
    val state by flow.collectAsStateWithLifecycle()
    return state
}

/**
 * Spec 2026-11-03 «UI», like X: under the header line (`belowText = false`) the accent «Translate» label, a small
 * progress indicator while it runs, or «Translation failed · Retry»; under the text (`belowText = true`) «Translated by
 * host · Show original» or «Show translation». Nothing at all (no height) where the state has nothing for that place.
 */
@Composable
fun TranslateRow(
    state: TranslationState,
    id8: String,
    onTranslate: () -> Unit,
    onOriginal: () -> Unit,
    onTranslation: () -> Unit,
    modifier: Modifier = Modifier,
    belowText: Boolean = false,
) {
    val style = MaterialTheme.typography.labelMedium
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (!belowText) when (state) {
        is TranslationState.Offer -> Text(
            stringResource(R.string.translate_action), style = style, color = accent,
            modifier = modifier.clickable(role = Role.Button, onClick = onTranslate).padding(vertical = Spacing.xs).testTag("translate-$id8"),
        )
        TranslationState.Loading -> CircularProgressIndicator(
            strokeWidth = 2.dp, modifier = modifier.padding(vertical = Spacing.xs).size(14.dp).testTag("translate-loading-$id8"),
        )
        TranslationState.PackMissing -> DeviceStateRow(R.string.translate_pack_missing, R.string.translate_open_device_app, "translate-pack-missing-$id8", "translate-retry-$id8", modifier, ::openDeviceTranslatorApp, onTranslate)
        TranslationState.AppMissing -> DeviceStateRow(R.string.translate_app_missing, R.string.translate_get_app, "translate-app-missing-$id8", "translate-retry-$id8", modifier, ::openDeviceTranslatorStore, onTranslate)
        TranslationState.Failed -> Row(modifier.padding(vertical = Spacing.xs).testTag("translate-failed-$id8"), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.translate_failed) + SEPARATOR, style = style, color = muted)
            Text(stringResource(R.string.translate_retry), style = style, color = accent, modifier = Modifier.clickable(role = Role.Button, onClick = onTranslate).testTag("translate-retry-$id8"))
        }
        else -> Unit
    } else when (state) {
        is TranslationState.Shown -> Row(modifier.padding(top = Spacing.xs).testTag("translated-by-$id8"), verticalAlignment = Alignment.CenterVertically) {
            // a gap, not « · »: the host wraps to a second line on a phone, and the dot then hung alone at its end (owner 2026-11-12)
            Text(stringResource(R.string.translate_by, state.host), style = style, color = muted, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(Spacing.m))
            Text(stringResource(R.string.translate_original), style = style, color = accent, maxLines = 1, modifier = Modifier.clickable(role = Role.Button, onClick = onOriginal).testTag("translate-original-$id8"))
        }
        is TranslationState.Original -> Row(modifier.padding(top = Spacing.xs).testTag("translated-by-$id8")) {
            Text(stringResource(R.string.translate_show_translation), style = style, color = accent, modifier = Modifier.clickable(role = Role.Button, onClick = onTranslation).testTag("translate-show-$id8"))
        }
        else -> Unit
    }
}

private const val SEPARATOR = " · "

/**
 * «Language pack missing  Open Offline Translator  Retry» / «Offline Translator not installed  Get it  Retry»: the first
 * action opens the app or its store page, «Retry» ([onRetry]) tries again once the pack or the app is there. Gaps, not
 * « · »: the row wraps on a phone, and a dot would hang alone at a line's end (owner 2026-11-12).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceStateRow(
    @StringRes text: Int,
    @StringRes action: Int,
    tag: String,
    retryTag: String,
    modifier: Modifier,
    open: (Context) -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val style = MaterialTheme.typography.labelMedium
    val accent = MaterialTheme.colorScheme.primary
    FlowRow(modifier.padding(vertical = Spacing.xs).testTag(tag), horizontalArrangement = Arrangement.spacedBy(Spacing.m), itemVerticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(text), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(action), style = style, color = accent, maxLines = 1, modifier = Modifier.clickable(role = Role.Button) { open(context) })
        Text(stringResource(R.string.translate_retry), style = style, color = accent, maxLines = 1, modifier = Modifier.clickable(role = Role.Button, onClick = onRetry).testTag(retryTag))
    }
}

/** Offline Translator's page in the store app (F-Droid), else on f-droid.org. */
fun openDeviceTranslatorStore(context: Context) {
    val market = Intent(Intent.ACTION_VIEW, "market://details?id=${DeviceTranslator.PKG}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(market)
    } catch (_: ActivityNotFoundException) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, "https://f-droid.org/packages/${DeviceTranslator.PKG}/".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Opens Offline Translator (to download a language pack); its store page when it is gone. */
fun openDeviceTranslatorApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(DeviceTranslator.PKG)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (launch == null || runCatching { context.startActivity(launch) }.isFailure) openDeviceTranslatorStore(context)
}

/** The note card's [TranslateRow] wired to [LocalNoteTranslator] for [noteId]. */
@Composable
fun NoteTranslateRow(state: TranslationState, noteId: String, modifier: Modifier = Modifier, belowText: Boolean = false) {
    val translator = LocalNoteTranslator.current ?: return
    TranslateRow(
        state, noteId.take(8),
        onTranslate = { translator.translate(noteId) },
        onOriginal = { translator.showOriginal(noteId) },
        onTranslation = { translator.showTranslation(noteId) },
        modifier = modifier, belowText = belowText,
    )
}

/** Spec 2026-11-03: asked once, on the first «Translate»; names the server and what it sees ([article]: an article's tap). «Settings» opens the section. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateConsentSheet(host: String, onTranslate: () -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit, article: Boolean = false, onUseDevice: (() -> Unit)? = null, onUpdateDevice: (() -> Unit)? = null) {
    GuardedModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("translate-consent")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.xl)) {
            Text(stringResource(R.string.translate_consent_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(Spacing.m))
            Text(stringResource(if (article) R.string.translate_consent_body_article else R.string.translate_consent_body, host), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(Spacing.xl))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.m, Alignment.End)) {
                OutlinedButton(onClick = onSettings, modifier = Modifier.testTag("translate-consent-settings"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.translate_consent_settings)) }
                Button(onClick = onTranslate, modifier = Modifier.testTag("translate-consent-translate"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.translate_consent_translate)) }
            }
            if (onUseDevice != null) {
                Spacer(Modifier.height(Spacing.s))
                TextButton(onClick = onUseDevice, modifier = Modifier.align(Alignment.End).testTag("translate-consent-device"), shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(R.string.translate_consent_device))
                }
            }
            if (onUpdateDevice != null) {
                Spacer(Modifier.height(Spacing.s))
                TextButton(onClick = onUpdateDevice, modifier = Modifier.align(Alignment.End).testTag("translate-consent-device-update"), shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(R.string.translate_consent_device_update))
                }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

/**
 * The nav host's consent sheet for [translation]'s waiting «Translate»; with Offline Translator installed (checked as the
 * sheet is about to show) it offers the device instead. [onSettings] opens the translation settings.
 */
@Composable
fun TranslateConsentHost(translation: NoteTranslationViewModel, onSettings: () -> Unit) {
    val consentFor by translation.consentFor.collectAsStateWithLifecycle()
    if (consentFor == null) return
    val host by translation.host.collectAsStateWithLifecycle()
    val deviceState by translation.deviceState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    TranslateConsentSheet(
        host = host, onTranslate = translation::consent, onDismiss = translation::dismissConsent,
        article = consentFor == NoteTranslationViewModel.ARTICLE,
        onSettings = { translation.dismissConsent(); onSettings() },
        onUseDevice = if (deviceState == DeviceAppState.READY) translation::useDevice else null,
        // too old: the store, no consent stored, the sheet closes
        onUpdateDevice = if (deviceState == DeviceAppState.TOO_OLD) ({ translation.dismissConsent(); openDeviceTranslatorStore(context) }) else null,
    )
}

package com.monostr.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.monostr.app.R
import com.monostr.app.translate.TranslateServer
import com.monostr.app.translate.TranslationMode
import com.monostr.app.translate.TranslationSettingsStore
import com.monostr.app.translate.TranslationState
import com.monostr.app.translate.Translations
import com.monostr.nostr.StartupGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
) : ViewModel(), NoteTranslator {
    private val _consentFor = MutableStateFlow<String?>(null)
    /** The note (or [ARTICLE]) whose «Translate» waits for the consent sheet; null while no sheet shows. */
    val consentFor: StateFlow<String?> = _consentFor.asStateFlow()
    /** What «Translate» in the sheet runs. */
    private var pending: (() -> Unit)? = null

    /** The host the consent sheet names: the one a translation would go to. */
    val host: StateFlow<String> = combine(settings.mode, settings.customUrl) { mode, url ->
        if (mode == TranslationMode.CUSTOM && url.isNotBlank()) TranslateServer(url).host else TranslateServer.MONOSTR.host
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
            if (settings.consentGiven.first()) action() else { pending = action; _consentFor.value = key }
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

    fun dismissConsent() { pending = null; _consentFor.value = null }

    companion object {
        /** [consentFor] while an article's «Translate» waits. */
        const val ARTICLE = "article"
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
            modifier = modifier.clickable(role = Role.Button, onClick = onTranslate).padding(vertical = 4.dp).testTag("translate-$id8"),
        )
        TranslationState.Loading -> CircularProgressIndicator(
            strokeWidth = 2.dp, modifier = modifier.padding(vertical = 4.dp).size(14.dp).testTag("translate-loading-$id8"),
        )
        TranslationState.Failed -> Row(modifier.padding(vertical = 4.dp).testTag("translate-failed-$id8"), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.translate_failed) + SEPARATOR, style = style, color = muted)
            Text(stringResource(R.string.translate_retry), style = style, color = accent, modifier = Modifier.clickable(role = Role.Button, onClick = onTranslate).testTag("translate-retry-$id8"))
        }
        else -> Unit
    } else when (state) {
        is TranslationState.Shown -> Row(modifier.padding(top = 4.dp).testTag("translated-by-$id8"), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.translate_by, state.host) + SEPARATOR, style = style, color = muted, modifier = Modifier.weight(1f, fill = false))
            Text(stringResource(R.string.translate_original), style = style, color = accent, maxLines = 1, modifier = Modifier.clickable(role = Role.Button, onClick = onOriginal).testTag("translate-original-$id8"))
        }
        is TranslationState.Original -> Row(modifier.padding(top = 4.dp).testTag("translated-by-$id8")) {
            Text(stringResource(R.string.translate_show_translation), style = style, color = accent, modifier = Modifier.clickable(role = Role.Button, onClick = onTranslation).testTag("translate-show-$id8"))
        }
        else -> Unit
    }
}

private const val SEPARATOR = " · "

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
fun TranslateConsentSheet(host: String, onTranslate: () -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit, article: Boolean = false) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("translate-consent")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text(stringResource(R.string.translate_consent_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(if (article) R.string.translate_consent_body_article else R.string.translate_consent_body, host), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                OutlinedButton(onClick = onSettings, modifier = Modifier.testTag("translate-consent-settings")) { Text(stringResource(R.string.translate_consent_settings)) }
                Button(onClick = onTranslate, modifier = Modifier.testTag("translate-consent-translate")) { Text(stringResource(R.string.translate_consent_translate)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

package com.monostr.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.monostr.app.R
import com.monostr.app.ui.theme.Spacing
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Sends web links to [ask] unless [LinkPolicy] lets them through; a newer link replaces the one being asked. */
class GuardedUriHandler(
    private val delegate: UriHandler,
    private val alwaysOpen: () -> Boolean,
    private val torOn: () -> Boolean,
    private val ask: (String) -> Unit,
) : UriHandler {
    override fun openUri(uri: String) {
        when (LinkPolicy.decide(uri, alwaysOpen(), torOn())) {
            LinkAction.OPEN -> openUriConfirmed(uri)
            LinkAction.ASK -> ask(uri)
        }
    }

    /** No browser installed (or any refusal) must not crash the app. */
    fun openUriConfirmed(uri: String) { runCatching { delegate.openUri(uri) } }
}

/** Wraps [content] so every link tap below goes through the prompt; [alwaysOpen] is state the caller already collects. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LinkPromptHost(alwaysOpen: Boolean, onAlwaysOpen: () -> Unit, torOn: () -> Boolean = { false }, torBrowser: TorBrowserFinder? = null, content: @Composable () -> Unit) {
    val base = LocalUriHandler.current
    val context = LocalContext.current
    val finder = remember(context, torBrowser) { torBrowser ?: TorBrowser.finder(context) }
    var asked by rememberSaveable(saver = AskedSaver) { mutableStateOf<String?>(null) }
    val alwaysNow by rememberUpdatedState(alwaysOpen)
    val torNow by rememberUpdatedState(torOn)
    val handler = remember(base) { GuardedUriHandler(base, { alwaysNow }, { torNow() }, { asked = it }) }
    // the second key reaches into windows, which provide the platform LocalUriHandler again (GuardedWindows.kt)
    CompositionLocalProvider(LocalUriHandler provides handler, LocalGuardedUriHandler provides handler) { content() }
    val url = asked ?: return
    // every answer reads the live state and clears it first, so a second tap before recomposition does nothing
    fun answer(open: Boolean, always: Boolean = false) {
        val current = asked ?: return
        asked = null
        if (always) onAlwaysOpen()
        if (open) handler.openUriConfirmed(current)
    }
    val web = LinkPromptRules.isWeb(url)
    // read when asked: while the sheet covers the app the route does not change
    val viaTor = torOn()
    // the two private ways out exist only beside the Tor line; the package check runs once the prompt is open, off the main thread
    val torChoices = web && viaTor
    val torBrowserPkg by produceState<String?>(null, url, torChoices) {
        value = if (torChoices) withContext(Dispatchers.IO) { runCatching { finder.find(url) }.getOrNull() } else null
    }
    val failedText = stringResource(R.string.link_prompt_torbrowser_failed)
    val copiedText = stringResource(R.string.link_prompt_copied)
    fun openInTorBrowser(pkg: String) {
        val current = asked ?: return
        asked = null
        // never the default browser: a failed start only says so
        val started = runCatching { context.startActivity(ViewLaunch(android.content.Intent.ACTION_VIEW, current, pkg).toIntent()) }.isSuccess
        if (!started) Toast.makeText(context, failedText, Toast.LENGTH_SHORT).show()
    }
    fun copyLink() {
        val current = asked ?: return
        asked = null
        copyToClipboard(context, current, copiedText, label = "Monostr link")
    }
    // its buttons answer through openUriConfirmed, the platform's handler read above, so Open never asks again
    GuardedModalBottomSheet(
        onDismissRequest = { asked = null }, modifier = Modifier.testTag("link-prompt"),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.l, vertical = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            // the text scrolls, the buttons stay below it on every screen size
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(stringResource(R.string.link_prompt_title), style = MaterialTheme.typography.titleMedium)
                // the whole host, wrapped: the registrable domain sits at its right end
                if (web) Text(LinkHost.of(url) ?: stringResource(R.string.link_prompt_unknown_host), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("link-host"))
                Text(LinkPromptRules.display(url), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("link-url"))
                if (web) Text(stringResource(R.string.link_prompt_reason), style = MaterialTheme.typography.bodyMedium)
                if (web && viaTor) Text(stringResource(R.string.link_prompt_tor), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("link-tor-note"))
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedButton({ answer(false) }, Modifier.testTag("link-cancel"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.link_prompt_cancel)) }
                if (torChoices) OutlinedButton({ copyLink() }, Modifier.testTag("link-copy"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.link_prompt_copy)) }
                val pkg = torBrowserPkg.takeIf { torChoices }
                if (pkg != null) {
                    Button({ openInTorBrowser(pkg) }, Modifier.testTag("link-torbrowser"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.link_prompt_torbrowser)) }
                    // beside Tor Browser the default browser is the less private choice, so it steps back
                    OutlinedButton({ answer(true) }, Modifier.testTag("link-open"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.link_prompt_open)) }
                } else {
                    Button({ answer(true) }, Modifier.testTag("link-open"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.link_prompt_open)) }
                }
                // under Tor it asks every time whatever is stored (spec 6), so the offer would promise what it cannot keep
                if (web && !viaTor) TextButton({ answer(true, always = true) }, Modifier.testTag("link-always")) { Text(stringResource(R.string.link_prompt_always)) }
            }
        }
    }
}

/** Limits for the prompt: an untrusted link never goes into saved state or onto the screen unbounded. */
object LinkPromptRules {
    const val MAX_CHARS = 8000

    fun persistable(url: String) = url.length <= MAX_CHARS

    /** The text shown: whole up to [MAX_CHARS], beyond that cut with an explicit ellipsis. */
    fun display(url: String) = if (url.length <= MAX_CHARS) url else url.take(MAX_CHARS) + "…"

    /** Only web links reach a site that learns the IP; the reason line and "Always open" belong to them alone. */
    fun isWeb(url: String) = url.substringBefore(':', "").lowercase().let { it == "http" || it == "https" }
}

private val AskedSaver = Saver<MutableState<String?>, String>(
    save = { it.value?.takeIf(LinkPromptRules::persistable) },
    restore = { mutableStateOf(it) },
)

/** The host a browser would contact, read the way browsers parse (OkHttp HttpUrl); null when none can be read. Never guessed from the text. */
object LinkHost {
    fun of(url: String): String? =
        runCatching { url.toHttpUrlOrNull()?.host }.getOrNull()?.takeIf { it.isNotEmpty() }
}

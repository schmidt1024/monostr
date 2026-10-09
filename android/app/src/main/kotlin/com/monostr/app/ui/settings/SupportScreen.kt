package com.monostr.app.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monostr.app.data.HintStore
import com.monostr.app.session.NostrSession
import kotlinx.coroutines.launch
import com.monostr.app.BuildConfig
import com.monostr.app.R
import com.monostr.app.ui.common.QrCode
import com.monostr.app.ui.theme.MonostrFonts
import com.monostr.app.ui.tips.WalletOpener
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Spec 4/5: links built from the BuildConfig values; an empty value gives an empty link (its section is hidden). */
object SupportLinks {
    fun moneroUri(address: String): String = address.trim().let { if (it.isEmpty()) "" else "monero:$it" }
    fun issuesUrl(repoUrl: String): String = repoUrl.trim().trimEnd('/').let { if (it.isEmpty()) "" else "$it/issues" }
}

/** Spec 4: `assets/contributors.txt`, one name per line, written by the Gradle task `updateContributors`. */
object Contributors {
    const val ASSET = "contributors.txt"
    fun parse(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

@HiltViewModel
class SupportViewModel @Inject constructor(@ApplicationContext context: Context, session: NostrSession, hints: HintStore) : ViewModel() {
    /** Read once; a missing asset shows no contributor section. */
    val contributors: List<String> = runCatching {
        context.assets.open(Contributors.ASSET).bufferedReader().use { Contributors.parse(it.readText()) }
    }.getOrDefault(emptyList())

    init {
        // spec 4: visiting this screen ends the feed hint for good
        viewModelScope.launch { runCatching { hints.setSupportHintDone(session.requireReady().pubkey) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(onBack: () -> Unit, vm: SupportViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var fallback by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.support_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
        )
    }) { padding ->
        SupportContent(
            address = BuildConfig.SUPPORT_ADDRESS,
            repoUrl = BuildConfig.REPO_URL,
            contributors = vm.contributors,
            // the existing wallet start (spec 5.9 of Plan 5); no wallet app → address and QR code
            onDonate = { if (!WalletOpener.open(context, SupportLinks.moneroUri(BuildConfig.SUPPORT_ADDRESS))) fallback = true },
            onOpenRepo = { uriHandler.openUri(BuildConfig.REPO_URL) },
            modifier = Modifier.padding(padding),
        )
    }
    if (fallback) SupportFallbackDialog(BuildConfig.SUPPORT_ADDRESS, onDismiss = { fallback = false })
}

@Composable
fun SupportContent(address: String, repoUrl: String, contributors: List<String>, onDonate: () -> Unit, onOpenRepo: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.support_text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("support-text"))
        if (address.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onDonate, modifier = Modifier.fillMaxWidth().testTag("support-donate")) { Text(stringResource(R.string.support_button)) }
        }
        if (contributors.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.support_contributors), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("support-contributors"))
            Spacer(Modifier.height(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                contributors.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        if (repoUrl.isNotBlank()) {
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onOpenRepo, modifier = Modifier.testTag("support-repo")) { Text(stringResource(R.string.support_repo)) }
        }
    }
}

/** No wallet app answered the `monero:` URI: the address to copy and its QR code. */
@Composable
fun SupportFallbackDialog(address: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tip_no_wallet_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                QrCode(SupportLinks.moneroUri(address), Modifier.size(200.dp))
                Spacer(Modifier.height(8.dp))
                Text(address, style = MaterialTheme.typography.bodySmall, fontFamily = MonostrFonts.mono, modifier = Modifier.testTag("support-fallback-address"))
            }
        },
        confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(address)) }) { Text(stringResource(R.string.action_copy_address)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

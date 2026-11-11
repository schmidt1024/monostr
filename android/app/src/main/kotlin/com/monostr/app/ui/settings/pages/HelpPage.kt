package com.monostr.app.ui.settings.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.monostr.app.BuildConfig
import com.monostr.app.R
import com.monostr.app.data.MediaServer
import com.monostr.app.ui.settings.SettingsCardDivider
import com.monostr.app.ui.settings.SettingsGroup
import com.monostr.app.ui.settings.SettingsPage
import com.monostr.app.ui.settings.SettingsViewModel
import com.monostr.app.ui.settings.SupportFallbackDialog
import com.monostr.app.ui.settings.SupportLinks
import com.monostr.app.ui.settings.SupportViewModel
import com.monostr.app.ui.settings.settingAnchor
import com.monostr.app.ui.theme.Spacing
import com.monostr.app.ui.tips.WalletOpener
import com.monostr.nostr.Npub
import com.monostr.nostr.blossom.BlossomAuth

/**
 * Help & about (round 2, owner decision 6): no further subpages. About as a compact card (logo, version, feedback, issues,
 * licence), Support as one card below it. The About and Support screens stay for their other callers.
 */
@Composable
fun HelpPage(vm: SettingsViewModel, focus: String?, onBack: () -> Unit, onOpenProfile: (String) -> Unit, support: SupportViewModel = hiltViewModel()) {
    SettingsPageScaffold(SettingsPage.HELP, focus, onBack, vm) {
        val uriHandler = LocalUriHandler.current
        SettingsGroup(stringResource(R.string.about_title), first = true, modifier = Modifier.settingAnchor("settings-about").testTag("settings-about")) {
            AboutHeader()
            val feedback = Npub.decodeOrNull(BuildConfig.FEEDBACK_NPUB)
            if (feedback != null) {
                SettingsCardDivider()
                SettingsLinkRow(stringResource(R.string.about_feedback), null, "about-feedback") { onOpenProfile(feedback) }
            }
            val issues = SupportLinks.issuesUrl(BuildConfig.REPO_URL)
            if (issues.isNotBlank()) {
                SettingsCardDivider()
                SettingsLinkRow(stringResource(R.string.about_issues), null, "about-issues") { runCatching { uriHandler.openUri(issues) } }
            }
            if (BuildConfig.LICENSE.isNotBlank()) {
                SettingsCardDivider()
                SettingsLinkRow(stringResource(R.string.about_license_row), BuildConfig.LICENSE, "about-license", onClick = null)
            }
            val mediaHost = BlossomAuth.host(MediaServer.DEFAULT).orEmpty()
            if (mediaHost.isNotBlank()) {
                SettingsCardDivider()
                Text(
                    stringResource(R.string.about_media, mediaHost), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.l).testTag("about-media"),
                )
            }
        }
        SupportCard(support.contributors)
    }
}

/** Logo, wordmark, version and the Beta badge: the head of the About card. */
@Composable
private fun AboutHeader() {
    val ink = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
    Column(Modifier.fillMaxWidth().padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_logo_mark), contentDescription = null, colorFilter = ink, modifier = Modifier.height(32.dp))
            Spacer(Modifier.width(Spacing.m))
            Image(painterResource(R.drawable.ic_wordmark), contentDescription = stringResource(R.string.app_name), colorFilter = ink, modifier = Modifier.height(20.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("about-version"))
            if (BuildConfig.BETA) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.testTag("about-beta")) {
                    Text(stringResource(R.string.about_beta), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xxs))
                }
            }
        }
    }
}

/** The support text, the Monero button, the contributors and the source code link, as one card. */
@Composable
private fun SupportCard(contributors: List<String>) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var fallback by rememberSaveable { mutableStateOf(false) }
    val address = BuildConfig.SUPPORT_ADDRESS
    SettingsGroup(stringResource(R.string.support_title), modifier = Modifier.settingAnchor("settings-support").testTag("settings-support")) {
        Column(Modifier.fillMaxWidth().padding(Spacing.l)) {
            Text(stringResource(R.string.support_text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("support-text"))
            if (address.isNotBlank()) {
                Spacer(Modifier.height(Spacing.l))
                // the existing wallet start (spec 5.9 of Plan 5); no wallet app → address and QR code
                Button(
                    onClick = { if (!WalletOpener.open(context, SupportLinks.moneroUri(address))) fallback = true },
                    modifier = Modifier.fillMaxWidth().testTag("support-donate"), shape = MaterialTheme.shapes.medium,
                ) { Text(stringResource(R.string.support_button)) }
            }
            if (contributors.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.l))
                Text(stringResource(R.string.support_contributors), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("support-contributors"))
                Spacer(Modifier.height(Spacing.xs))
                Text(contributors.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (BuildConfig.REPO_URL.isNotBlank()) {
            SettingsCardDivider()
            SettingsLinkRow(stringResource(R.string.support_repo), null, "support-repo") { runCatching { uriHandler.openUri(BuildConfig.REPO_URL) } }
        }
    }
    if (fallback) SupportFallbackDialog(address, onDismiss = { fallback = false })
}

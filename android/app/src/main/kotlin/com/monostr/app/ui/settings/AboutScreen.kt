package com.monostr.app.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.monostr.app.BuildConfig
import com.monostr.app.R
import com.monostr.app.data.MediaServer
import com.monostr.nostr.Npub
import com.monostr.nostr.blossom.BlossomAuth

/** Spec 5: app name with logo, version and build, a Beta badge until 1.0, feedback profile, issues, licence. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenProfile: (String) -> Unit) {
    val uriHandler = LocalUriHandler.current
    val issues = SupportLinks.issuesUrl(BuildConfig.REPO_URL)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.about_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
        )
    }) { padding ->
        AboutContent(
            versionName = BuildConfig.VERSION_NAME, versionCode = BuildConfig.VERSION_CODE, beta = BuildConfig.BETA,
            feedbackPubkey = Npub.decodeOrNull(BuildConfig.FEEDBACK_NPUB), issuesUrl = issues, license = BuildConfig.LICENSE,
            onFeedback = onOpenProfile, onIssues = { uriHandler.openUri(issues) },
            mediaHost = BlossomAuth.host(MediaServer.DEFAULT).orEmpty(), modifier = Modifier.padding(padding),
        )
    }
}

@Composable
fun AboutContent(
    versionName: String,
    versionCode: Int,
    beta: Boolean,
    feedbackPubkey: String?,
    issuesUrl: String,
    license: String,
    onFeedback: (String) -> Unit,
    onIssues: () -> Unit,
    /** Plan 10e: the host of the project's media server; empty hides the line. */
    mediaHost: String = "",
    modifier: Modifier = Modifier,
) {
    val ink = ColorFilter.tint(MaterialTheme.colorScheme.onBackground)
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Image(painterResource(R.drawable.ic_logo_mark), contentDescription = null, colorFilter = ink, modifier = Modifier.height(32.dp))
        Spacer(Modifier.height(12.dp))
        Image(painterResource(R.drawable.ic_wordmark), contentDescription = stringResource(R.string.app_name), colorFilter = ink, modifier = Modifier.height(28.dp))
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.about_version, versionName, versionCode), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("about-version"))
            if (beta) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.testTag("about-beta")) {
                    Text(stringResource(R.string.about_beta), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (feedbackPubkey != null) {
            TextButton(onClick = { onFeedback(feedbackPubkey) }, modifier = Modifier.testTag("about-feedback")) { Text(stringResource(R.string.about_feedback)) }
        }
        if (issuesUrl.isNotBlank()) {
            TextButton(onClick = onIssues, modifier = Modifier.testTag("about-issues")) { Text(stringResource(R.string.about_issues)) }
        }
        if (mediaHost.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.about_media, mediaHost), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("about-media"))
        }
        if (license.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.about_license, license), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("about-license"))
        }
    }
}

package com.monostr.app.ui.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.monostr.app.ui.common.SecureWindow
import com.monostr.app.ui.common.TorLoginRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.monostr.app.R
import com.monostr.app.session.NostrSession
import com.monostr.app.session.SessionState
import com.monostr.app.ui.accounts.AccountNotice
import com.monostr.app.ui.accounts.AccountNotices
import com.monostr.app.ui.common.asString
import com.monostr.app.ui.theme.Spacing
import com.monostr.nostr.LocalSigner
import com.monostr.nostr.Nip49
import com.monostr.nostr.nip55.AmberSigner
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    @ApplicationContext context: android.content.Context,
    private val session: NostrSession,
) : ViewModel() {
    val controller = LoginController(
        actions = object : LoginActions {
            override suspend fun loginLocal(signer: LocalSigner) = session.loginLocal(signer)
            override suspend fun loginAmber(pubkey: String, packageName: String) = session.loginAmber(pubkey, packageName)
        },
        bridge = session.bridge,
        amberAvailable = AmberSigner.isInstalled(context),
        restoreMessage = (session.state.value as? SessionState.LoggedOut)?.message,
        onAlreadyActive = { session.activePubkey()?.let { AccountNotices.post(AccountNotice.AlreadyUsing(it)) } },
        scryptMemory = {
            val info = android.app.ActivityManager.MemoryInfo()
            context.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(info)
            if (info.availMem > 0) info.availMem / 2 else Long.MAX_VALUE
        },
    )

    fun loginWithSecret(input: String, password: String = "") = viewModelScope.launch { controller.loginWithSecret(input, password) }
    fun loginWithAmber() = viewModelScope.launch { controller.loginWithAmber() }
}

@Composable
fun LoginScreen(
    onCreate: () -> Unit = {}, vm: LoginViewModel = hiltViewModel(), adding: Boolean = false, onBack: () -> Unit = {},
    /** Final review I2: the restore failed and other accounts are kept; opens the switcher (switch to one, or remove the stale one). */
    onAccounts: (() -> Unit)? = null,
) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    // the key field is masked, but a paste preview or the recents thumbnail must not show it either
    SecureWindow()
    // spec «Adding an account»: the add sheet's «Log in with key» is this screen with the key form only (Amber, create and Tor live elsewhere then)
    if (adding) BackHandler(enabled = !state.busy, onBack = onBack)
    // final review M8: the key of the account that runs: «Already using …» tells it, the screen closes
    LaunchedEffect(state.alreadyActive) {
        if (state.alreadyActive) {
            vm.controller.alreadyActiveShown()
            onBack()
        }
    }
    var secret by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") } // plain remember: never saved state
    val needsPassword = Nip49.isNcryptsec(secret)
    LaunchedEffect(state.passwordError) { if (state.passwordError != null) password = "" }
    // scrolls once it no longer fits, as with a large font: the Tor row must stay reachable
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
      Column(Modifier.verticalScroll(rememberScrollState()).fillMaxWidth().heightIn(min = maxHeight).padding(Spacing.xl), verticalArrangement = Arrangement.Center) {
        // owner 2026-11-12 (variant B): coin as the hero, close over the wordmark, claim centred; the same stacked lockup as monostr.com
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.ic_logo_mark),
                contentDescription = null, // decorative; the wordmark below carries the name
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                modifier = Modifier.size(96.dp),
            )
            Spacer(Modifier.height(Spacing.l))
            Image(
                painter = painterResource(R.drawable.ic_wordmark),
                contentDescription = stringResource(R.string.app_name),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                modifier = Modifier.height(34.dp),
            )
            Spacer(Modifier.height(Spacing.m))
            Text(
                stringResource(R.string.login_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        state.notice?.let {
            Spacer(Modifier.height(Spacing.s))
            Text(it.asString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        if (onAccounts != null && !adding) {
            Spacer(Modifier.height(Spacing.s))
            OutlinedButton(onClick = onAccounts, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("login-accounts"), shape = MaterialTheme.shapes.medium) {
                Text(stringResource(R.string.accounts_switch_label))
            }
        }
        Spacer(Modifier.height(Spacing.xxxl)) // the lockup stands apart from the form
        if (adding) {
            // the key form only: Amber is the add sheet's first row
        } else if (state.amberAvailable) {
            Button(onClick = vm::loginWithAmber, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("login-amber"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.login_amber)) }
            Spacer(Modifier.height(Spacing.xl))
            Text(stringResource(R.string.login_or_key), style = MaterialTheme.typography.labelLarge)
        } else {
            Text(stringResource(R.string.login_no_signer), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(Spacing.s))
        OutlinedTextField(
            value = secret, onValueChange = { secret = it }, singleLine = true,
            label = { Text(stringResource(R.string.login_secret_label)) }, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            isError = state.error != null, supportingText = { state.error?.let { Text(it.asString()) } },
            modifier = Modifier.fillMaxWidth().testTag("login-secret"),
            shape = MaterialTheme.shapes.medium,
        )
        if (needsPassword) {
            Spacer(Modifier.height(Spacing.s))
            OutlinedTextField(
                value = password, onValueChange = { password = it }, singleLine = true,
                label = { Text(stringResource(R.string.login_password_label)) }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                isError = state.passwordError != null, supportingText = { state.passwordError?.let { Text(it.asString()) } },
                modifier = Modifier.fillMaxWidth().testTag("login-password"),
                shape = MaterialTheme.shapes.medium,
            )
        }
        Spacer(Modifier.height(Spacing.s))
        OutlinedButton(onClick = { vm.loginWithSecret(secret, password) }, enabled = !state.busy && secret.isNotBlank() && (!needsPassword || password.isNotEmpty()), modifier = Modifier.fillMaxWidth().testTag("login-secret-button"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.login_button)) }
        if (!adding) {
            Spacer(Modifier.height(Spacing.l))
            // spec 2026-10-17 §2: the way in for someone without a key
            TextButton(onClick = onCreate, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("login-create")) { Text(stringResource(R.string.login_create)) }
            TorLoginRow()
        }
      }
      if (adding) {
          IconButton(onClick = onBack, enabled = !state.busy, modifier = Modifier.align(Alignment.TopStart).testTag("login-back")) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
          }
      }
    }
}

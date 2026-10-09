package com.monostr.app.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
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
import com.monostr.app.ui.common.asString
import com.monostr.nostr.LocalSigner
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
    )

    fun loginWithSecret(input: String) = viewModelScope.launch { controller.loginWithSecret(input) }
    fun loginWithAmber() = viewModelScope.launch { controller.loginWithAmber() }
}

@Composable
fun LoginScreen(vm: LoginViewModel = hiltViewModel()) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    var secret by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Image(
            painter = painterResource(R.drawable.ic_logo_mark),
            contentDescription = null, // decorative; the wordmark below carries the name
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
            modifier = Modifier.height(32.dp),
        )
        Spacer(Modifier.height(20.dp)) // gap = mark height / φ
        Image(
            painter = painterResource(R.drawable.ic_wordmark),
            contentDescription = stringResource(R.string.app_name),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
            modifier = Modifier.height(40.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.login_tagline), style = MaterialTheme.typography.bodyMedium)
        state.notice?.let {
            Spacer(Modifier.height(8.dp))
            Text(it.asString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(32.dp))
        if (state.amberAvailable) {
            Button(onClick = vm::loginWithAmber, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("login-amber")) { Text(stringResource(R.string.login_amber)) }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.login_or_key), style = MaterialTheme.typography.labelLarge)
        } else {
            Text(stringResource(R.string.login_no_signer), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = secret, onValueChange = { secret = it }, singleLine = true,
            label = { Text(stringResource(R.string.login_secret_label)) }, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            isError = state.error != null, supportingText = { state.error?.let { Text(it.asString()) } },
            modifier = Modifier.fillMaxWidth().testTag("login-secret"),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.loginWithSecret(secret) }, enabled = !state.busy && secret.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("login-secret-button")) { Text(stringResource(R.string.login_button)) }
    }
}

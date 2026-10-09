package com.monostr.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.monostr.app.data.Accent
import com.monostr.app.data.ThemeMode
import com.monostr.app.data.UiSettingsStore
import com.monostr.app.nav.MonostrNavHost
import com.monostr.app.session.NostrSession
import com.monostr.app.ui.theme.AccentSeed
import com.monostr.app.ui.theme.MonoColors
import com.monostr.app.ui.theme.MonostrTheme
import com.monostr.nostr.nip55.AmberSigner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var session: NostrSession
    @Inject lateinit var uiSettings: UiSettingsStore

    companion object {
        /** True while an instance is started (between onStart and onStop); the background worker must not release a shared engine then. */
        val inForeground = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Intent extra of a DM notification: the peer's hex pubkey whose chat to open. */
        const val EXTRA_OPEN_DM = "open_dm"

        /** Intent extra of the "waiting for the signer" summary: `true` opens the Messages tab. */
        const val EXTRA_OPEN_MESSAGES = "open_messages"

        /** A chat a notification asked to open; the logged-in nav host navigates there once and resets it. */
        val pendingDm = MutableStateFlow<String?>(null)

        /** The Messages tab a notification asked to open; handled like [pendingDm]. */
        val pendingMessages = MutableStateFlow(false)

        private val HEX_PUBKEY = Regex("[0-9a-f]{64}")
    }

    /**
     * Takes the target of a DM notification's intent: a peer (anything but a 64-char lowercase hex
     * pubkey is ignored) or the Messages tab (only a boolean `true` counts).
     */
    private fun takeOpenDm(intent: Intent?) {
        if (intent == null) return
        if (intent.hasExtra(EXTRA_OPEN_MESSAGES)) {
            // getBooleanExtra yields false for a value of any other type
            val open = intent.getBooleanExtra(EXTRA_OPEN_MESSAGES, false)
            intent.removeExtra(EXTRA_OPEN_MESSAGES)
            if (open) pendingMessages.value = true
        }
        val peer = intent.getStringExtra(EXTRA_OPEN_DM) ?: return
        intent.removeExtra(EXTRA_OPEN_DM)
        if (HEX_PUBKEY.matches(peer)) pendingDm.value = peer
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeOpenDm(intent)
    }

    override fun onStart() {
        super.onStart()
        inForeground.set(true)
        // back from the background the relay sockets are usually gone: reconnect now instead of
        // waiting for the pool's next retry (a publish in that window found no relay, v0.8.1)
        lifecycleScope.launch { session.reconnectIfLost() }
    }

    override fun onStop() {
        inForeground.set(false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // spec 3: before super.onCreate, so the launch theme hands over to Theme.Monostr
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // a recreated activity (rotation) still carries the launch intent; the chat was opened already
        if (savedInstanceState == null) takeOpenDm(intent)
        enableEdgeToEdge()
        setContent {
            // Null until DataStore delivered: avoids a first frame in the wrong mode under an override (no runBlocking on main).
            val mode by uiSettings.themeMode.collectAsState<ThemeMode, ThemeMode?>(initial = null)
            val accent by uiSettings.accent.collectAsState<Accent, Accent?>(initial = null)
            val customHue by uiSettings.customHue.collectAsState<Float, Float?>(initial = null)
            if (mode == null || accent == null || customHue == null) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(if (isSystemInDarkTheme()) MonoColors.DarkSurface else MonoColors.LightSurface)) {}
                return@setContent
            }
            MonostrTheme(mode!!, accent!!, customHue!!) {
                // NIP-55: whenever the bridge has a new pending request, launch the signer once and
                // hand the result back correlated by request id (a request can be superseded before
                // the launcher's callback fires, e.g. after a config change).
                var launchedId by rememberSaveable { mutableStateOf<String?>(null) }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    val id = launchedId ?: return@rememberLauncherForActivityResult
                    session.bridge.complete(id, result.resultCode == Activity.RESULT_OK, AmberSigner.fromIntent(result.data))
                }
                val pending by session.bridge.pending.collectAsState()
                LaunchedEffect(pending?.id) {
                    val p = pending
                    if (p != null && p.id != launchedId) {
                        launchedId = p.id
                        try {
                            launcher.launch(AmberSigner.toIntent(p.request))
                        } catch (e: Exception) {
                            session.bridge.complete(p.id, false, null)
                        }
                    }
                }
                Surface { MonostrNavHost(session) }
            }
        }
    }
}

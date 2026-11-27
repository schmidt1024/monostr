package com.monostr.app

import com.monostr.app.data.AmountUnit
import com.monostr.app.data.TipSettingsStore
import com.monostr.app.ui.tips.LocalAmountUnit
import androidx.compose.runtime.CompositionLocalProvider
import com.monostr.app.ui.common.LocalQuickReactions
import com.monostr.app.ui.common.Reactions
import com.monostr.app.ui.common.LinkPromptHost
import com.monostr.app.ui.common.ProvideTorUi
import android.app.Activity
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import com.monostr.app.ui.accounts.SWITCH_NOTICE_BOTTOM
import com.monostr.app.ui.accounts.SWITCH_NOTICE_END
import com.monostr.app.ui.accounts.SWITCH_NOTICE_START
import com.monostr.app.ui.accounts.SwitchNotice
import com.monostr.nostr.Npub
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import com.monostr.app.session.NetworkTransitions
import com.monostr.app.session.NostrSession
import com.monostr.app.ui.theme.AccentSeed
import com.monostr.app.ui.theme.MonoColors
import com.monostr.app.ui.theme.MonostrTheme
import com.monostr.nostr.nip55.AmberSigner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import com.monostr.app.work.OpenAction
import com.monostr.app.work.PendingOpen
import com.monostr.app.work.resolveOpen
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var session: NostrSession
    @Inject lateinit var uiSettings: UiSettingsStore
    @Inject lateinit var readerTheme: com.monostr.app.ui.article.ReaderThemeStore
    @Inject lateinit var tipSettings: TipSettingsStore
    @Inject lateinit var torSwitch: com.monostr.app.net.TorSwitch
    @Inject lateinit var sendGate: com.monostr.app.net.SendGate
    @Inject lateinit var torUi: com.monostr.app.net.TorUi
    @Inject lateinit var tor: dagger.Lazy<com.monostr.app.net.TorController>

    companion object {
        /** True while an instance is started (between onStart and onStop); the background worker must not release a shared engine then. */
        val inForeground = java.util.concurrent.atomic.AtomicBoolean(false)
        private val started = StartedCount()

        /** Intent extra of a DM notification: the peer's hex pubkey whose chat to open. */
        const val EXTRA_OPEN_DM = "open_dm"

        /** Intent extra of the "waiting for the signer" summary: `true` opens the Messages tab. */
        const val EXTRA_OPEN_MESSAGES = "open_messages"
        /** The [com.monostr.app.work.OpenToken] of a DM notification's intent: without it the two extras above are ignored. */
        const val EXTRA_OPEN_TOKEN = "open_token"

        /** Intent extra of every notification: the hex pubkey of the account it was posted for. */
        const val EXTRA_ACCOUNT = "account"

        /**
         * The target of a tapped notification, bound to its account in one value (process-wide, so it survives the activity's
         * recreation after a switch); the nav host of that account takes it, nothing else clears it but removal or a failed switch.
         */
        val pendingOpen = MutableStateFlow<PendingOpen?>(null)

        /** Drops [account]'s pending target (removal, failed switch); another account's stays. */
        fun clearPending(account: String) {
            pendingOpen.update { if (it?.account == account) null else it }
        }

        private val HEX_PUBKEY = Regex("[0-9a-f]{64}")

        /** The account just switched to, until its «Now using …» notice is done (process-wide: it crosses the recreate). */
        val switchNotice = MutableStateFlow<String?>(null)
    }

    /**
     * Spec «Switcher UI» / «Tor wait»: the notice of the last switch, left of the compose button above the tab bar. The name comes
     * from the registry (its short npub without one); under Tor it follows the fresh route of the new account.
     */
    @androidx.compose.runtime.Composable
    private fun SwitchNoticeOverlay(modifier: Modifier) {
        val pubkey by switchNotice.collectAsState()
        val target = pubkey ?: return
        val name by androidx.compose.runtime.produceState<String?>(null, target) { value = shownName(target) }
        val shown = name ?: return
        // the Tor controller exists only under Tor; direct, nothing waits for a route
        val route = remember(target) { if (sendGate.viaTor) tor.get().freshRoute else null }
        SwitchNotice(
            shown, route, onDone = { switchNotice.compareAndSet(target, null) },
            modifier = modifier.navigationBarsPadding().padding(start = SWITCH_NOTICE_START, end = SWITCH_NOTICE_END, bottom = SWITCH_NOTICE_BOTTOM),
        )
    }

    /** An account's name from the registry, its short npub without one. */
    private suspend fun shownName(pubkey: String): String {
        val info = runCatching { session.accounts.first() }.getOrNull()?.firstOrNull { it.pubkey == pubkey }
        return info?.name?.takeIf { it.isNotBlank() } ?: runCatching { Npub.short(pubkey) }.getOrDefault(pubkey.take(8))
    }

    /**
     * Takes the target of a DM notification's intent: a peer (anything but a 64-char lowercase hex
     * pubkey is ignored) or the Messages tab (only a boolean `true` counts).
     */
    private fun takeOpenDm(intent: Intent?) {
        if (intent == null) return
        val token = intent.getStringExtra(EXTRA_OPEN_TOKEN)
        intent.removeExtra(EXTRA_OPEN_TOKEN)
        if (!com.monostr.app.work.OpenToken.matches(this, token)) {
            intent.removeExtra(EXTRA_OPEN_MESSAGES)
            intent.removeExtra(EXTRA_OPEN_DM)
            return
        }
        // a notification without its account (posted before accounts existed) opens the app only
        val account = intent.getStringExtra(EXTRA_ACCOUNT)
        intent.removeExtra(EXTRA_ACCOUNT)
        if (account == null || !HEX_PUBKEY.matches(account)) {
            intent.removeExtra(EXTRA_OPEN_MESSAGES)
            intent.removeExtra(EXTRA_OPEN_DM)
            return
        }
        var messages = false
        if (intent.hasExtra(EXTRA_OPEN_MESSAGES)) {
            // getBooleanExtra yields false for a value of any other type
            messages = intent.getBooleanExtra(EXTRA_OPEN_MESSAGES, false)
            intent.removeExtra(EXTRA_OPEN_MESSAGES)
        }
        val peer = intent.getStringExtra(EXTRA_OPEN_DM)?.also { intent.removeExtra(EXTRA_OPEN_DM) }?.takeIf { HEX_PUBKEY.matches(it) }
        pendingOpen.value = PendingOpen(account, peer, messages)
    }

    /**
     * A notification of another account: switch to it first; the target stays pending (bound to its account) until that account's
     * nav host takes it. Busy or failed switch, or an account removed meanwhile: the app opens, the target goes.
     */
    private suspend fun resolvePending(target: PendingOpen) {
        val active = (session.state.first { it !is com.monostr.app.session.SessionState.Loading } as? com.monostr.app.session.SessionState.Active)?.ready?.pubkey
        if (active == null) {
            clearPending(target.account)
            return
        }
        val known = session.accounts.first().map { it.pubkey }.toSet()
        when (resolveOpen(active, known, target.account, target.peer)) {
            is OpenAction.Open -> Unit
            is OpenAction.Ignore -> clearPending(target.account)
            is OpenAction.SwitchThenOpen -> when (session.switchTo(target.account)) {
                com.monostr.app.session.SwitchResult.Done -> Unit
                com.monostr.app.session.SwitchResult.Failed -> {
                    clearPending(target.account)
                    com.monostr.app.ui.accounts.AccountNotices.post(com.monostr.app.ui.accounts.AccountNotice.SwitchFailed)
                }
                else -> clearPending(target.account)
            }
        }
    }

    /**
     * Replaces this activity by a new one in a new task, without a transition: its ViewModel store (the activity's view models
     * and every nav back stack entry's) is cleared with it. What must cross ([pendingOpen], [switchNotice]) is process-wide.
     */
    private fun restartFresh() {
        val next = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(next, androidx.core.app.ActivityOptionsCompat.makeCustomAnimation(this, 0, 0).toBundle())
        finish()
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeOpenDm(intent)
    }

    override fun onStart() {
        super.onStart()
        // only the first started instance: with two tasks the newer one starts before the older one stops
        if (started.start()) {
            inForeground.set(true)
            torSwitch.onForeground()
        }
        // back from the background the relay sockets are usually gone: reconnect now instead of
        // waiting for the pool's next retry (a publish in that window found no relay, v0.8.1)
        lifecycleScope.launch { session.reconnectIfLost() }
        registerNetworkWatch()
    }

    override fun onStop() {
        unregisterNetworkWatch()
        if (started.stop()) {
            inForeground.set(false)
            torSwitch.onBackground()
        }
        super.onStop()
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** One per activity: it remembers the last default network across stop and start (only touched on Main). */
    private val transitions = NetworkTransitions(nowMs = { SystemClock.elapsedRealtime() })

    /**
     * Spec 2026-10-23 §7: only while in the foreground, the default network's changes reconnect the relays. The first
     * callback after registering counts only for another network than before the stop; the callbacks come on a binder
     * thread and hop to the lifecycle scope (Main), which also drops one that arrives after the activity is gone.
     */
    private fun registerNetworkWatch() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        transitions.onRegistered()
        val callback = object : ConnectivityManager.NetworkCallback() {
            // a callback still queued from before the last unregister must not touch the state of the new one
            override fun onAvailable(network: Network) {
                val cb = this
                lifecycleScope.launch {
                    if (networkCallback !== cb) return@launch
                    val change = transitions.onAvailable(network.networkHandle) ?: return@launch
                    session.onNetworkChange(change)
                }
            }

            override fun onLost(network: Network) {
                val cb = this
                lifecycleScope.launch { if (networkCallback === cb) transitions.onLost(network.networkHandle) }
            }
        }
        // a refused registration (too many callbacks, missing permission) leaves only the onStart reconnect, as before
        if (runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess) networkCallback = callback
    }

    private fun unregisterNetworkWatch() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // spec 3: before super.onCreate, so the launch theme hands over to Theme.Monostr
        // Pixel 2026-11-12 (start variant C, «a big coin, then a small one»): the system's own starting window stays while the
        // session restores, at most START_SPINNER_DELAY_MS, and fades straight into the app; no in-app copy of the splash, which
        // drew the coin at another size. Only a slower restore shows the start screen, with the same coin and a spinner.
        val splashSince = android.os.SystemClock.uptimeMillis()
        installSplashScreen().setKeepOnScreenCondition {
            session.state.value is com.monostr.app.session.SessionState.Loading &&
                android.os.SystemClock.uptimeMillis() - splashSince < com.monostr.app.ui.common.START_SPINNER_DELAY_MS
        }
        super.onCreate(savedInstanceState)
        // a recreated activity (rotation) still carries the launch intent; the chat was opened already
        if (savedInstanceState == null) takeOpenDm(intent)
        // spec «Switch flow» step 5: after a switch nothing of the old session stays: a fresh activity (not recreate(), which keeps
        // the ViewModels and the nav back stack's stores) opens on Home with the new look and shows «Now using …». Collected before
        // anything here can switch (an emission without a collector is lost).
        lifecycleScope.launch {
            session.switched.collect { pubkey ->
                switchNotice.value = pubkey
                restartFresh()
            }
        }
        lifecycleScope.launch { pendingOpen.collectLatest { if (it != null) resolvePending(it) } }
        enableEdgeToEdge()
        setContent {
            // Null until DataStore delivered: avoids a first frame in the wrong mode under an override (no runBlocking on main).
            val mode by uiSettings.themeMode.collectAsState<ThemeMode, ThemeMode?>(initial = null)
            val accent by uiSettings.accent.collectAsState<Accent, Accent?>(initial = null)
            val customHue by uiSettings.customHue.collectAsState<Float, Float?>(initial = null)
            // the reader's own choice is read with the app theme: the article is on screen from the first frame (null inside the box = not chosen)
            val readerDark by remember { combine(readerTheme.dark, readerTheme.sepia, readerTheme.textStep) { dark, sepia, step -> ReaderChoice(dark, sepia, step) } }.collectAsState<ReaderChoice, ReaderChoice?>(initial = null)
            if (mode == null || accent == null || customHue == null || readerDark == null) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(if (isSystemInDarkTheme()) MonoColors.DarkSurface else MonoColors.LightSurface)) {}
                return@setContent
            }
            MonostrTheme(mode!!, accent!!, customHue!!) {
                val quick by uiSettings.quickReactions.collectAsState(initial = Reactions.QUICK)
                val unit by tipSettings.amountUnit.collectAsState(initial = AmountUnit.XMR)
                val canSend by sendGate.canSend.collectAsState()
                CompositionLocalProvider(LocalQuickReactions provides quick, LocalAmountUnit provides unit, com.monostr.app.ui.common.LocalCanSend provides canSend,
                    com.monostr.app.ui.theme.LocalThemeChoice provides com.monostr.app.ui.theme.ThemeChoice(mode!!, accent!!, customHue!!, readerDark!!.dark, readerDark!!.sepia, readerDark!!.textStep)) {
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
                val linkAlways by uiSettings.linkAlwaysOpen.collectAsState(initial = false)
                // not yet known counts as on, read at each tap: a stored «Always open» must not slip through while the mode is being read
                LinkPromptHost(linkAlways, { lifecycleScope.launch { uiSettings.setLinkAlwaysOpen(true) } }, torOn = { !sendGate.direct }) {
                    ProvideTorUi(torUi) {
                        Box(Modifier.fillMaxSize()) {
                            Surface { MonostrNavHost(session) }
                            SwitchNoticeOverlay(Modifier.align(Alignment.BottomStart))
                            // a failed switch, a refused removal, «Already using …»: shown over whatever screen the step ended on
                            com.monostr.app.ui.accounts.AccountNoticeHost(
                                nameOf = ::shownName,
                                modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding()
                                    .padding(start = SWITCH_NOTICE_START, end = SWITCH_NOTICE_END, bottom = SWITCH_NOTICE_BOTTOM),
                            )
                        }
                    }
                }
                }
            }
        }
    }
}

/** Distinguishes «not loaded yet» (null box) from «not chosen» (null inside). */
private data class ReaderChoice(val dark: Boolean?, val sepia: Boolean, val textStep: Int)

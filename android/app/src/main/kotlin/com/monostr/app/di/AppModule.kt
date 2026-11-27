package com.monostr.app.di

import com.monostr.app.data.linkpreview.PreviewImages
import com.monostr.app.net.dropAllConnections
import com.monostr.app.BuildConfig
import com.monostr.app.data.linkpreview.LinkPreviewFetcher
import com.monostr.app.data.linkpreview.LinkPreviews
import java.io.File
import android.content.Context
import com.monostr.app.data.*
import com.monostr.nostr.nip55.SignerBridge
import com.monostr.nostr.repo.FeedTabsCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun prefsAccountsStore(@ApplicationContext ctx: Context): PrefsAccountsStore = PrefsAccountsStore(ctx.monostrPrefs)
    @Provides @Singleton fun accountsStore(accounts: PrefsAccountsStore): AccountsStore = accounts
    @Provides @Singleton fun sessionStore(accounts: AccountsStore): SessionStore = accounts
    /** Every settings store reads through this; only [accountsStore] stays on the global file. Inactive (= global file) until an account is activated. */
    @Provides @Singleton fun accountPrefs(@ApplicationContext ctx: Context): AccountPrefs = AccountPrefs.create(ctx, ctx.monostrPrefs)
    @Provides @Singleton fun accountMigration(@ApplicationContext ctx: Context, accounts: PrefsAccountsStore, prefs: AccountPrefs, secrets: SecretStore): AccountMigration =
        AccountMigration(accounts, ctx.monostrPrefs, prefs, secrets)
    @Provides @Singleton fun relayStore(prefs: AccountPrefs): RelayStore = PrefsRelayStore(prefs)
    @Provides @Singleton fun relayModeStore(prefs: AccountPrefs): RelayModeStore = PrefsRelayStore(prefs)
    @Provides @Singleton fun searchRelayStore(prefs: AccountPrefs): SearchRelayStore = PrefsSearchRelayStore(prefs)
    @Provides @Singleton fun recentSearchesStore(prefs: AccountPrefs): RecentSearchesStore = PrefsRecentSearchesStore(prefs)
    @Provides @Singleton fun secretStore(@ApplicationContext ctx: Context): SecretStore = KeystoreSecretStore(ctx)
    @Provides @Singleton fun signerBridge(): SignerBridge = SignerBridge()
    @Provides @Singleton fun tipSettings(prefs: AccountPrefs): TipSettingsStore = PrefsTipSettingsStore(prefs)
    @Provides @Singleton fun pendingTips(prefs: AccountPrefs): PendingTipStore = PrefsPendingTipStore(prefs)
    @Provides @Singleton fun networkGate(): com.monostr.nostr.net.NetworkGate = com.monostr.nostr.net.NetworkGate()
    @Provides @Singleton fun torSwitch(@ApplicationContext ctx: Context): com.monostr.app.net.TorSwitchStore =
        com.monostr.app.net.TorSwitchStore(java.io.File(ctx.noBackupFilesDir, "tor-enabled"))
    /** Ask for it through a dagger.Lazy or Provider, and only with the switch on: with Tor off nothing of it may be built. */
    @Provides @Singleton fun torController(@ApplicationContext ctx: Context, gate: com.monostr.nostr.net.NetworkGate): com.monostr.app.net.TorController =
        com.monostr.app.net.TorController(com.monostr.app.net.AndroidTorEngine(ctx), CoroutineScope(SupervisorJob() + Dispatchers.Default)) { gate.torMode() }
    @Provides @Singleton fun uiSettings(prefs: AccountPrefs): UiSettingsStore = PrefsUiSettingsStore(prefs)
    /** The installed check behind the default mode (backlog 89): the translator is built lazily, on the check's IO thread. */
    @Provides @Singleton fun translationSettings(
        prefs: AccountPrefs,
        device: dagger.Lazy<com.monostr.app.translate.DeviceTranslator>,
    ): com.monostr.app.translate.TranslationSettingsStore =
        com.monostr.app.translate.PrefsTranslationSettingsStore(prefs, { device.get().isInstalled() })
    @Provides @Singleton fun readerTheme(prefs: AccountPrefs): com.monostr.app.ui.article.ReaderThemeStore =
        com.monostr.app.ui.article.PrefsReaderThemeStore(prefs)
    @Provides @Singleton fun translateClient(http: OkHttpClient): com.monostr.app.translate.TranslateClient = com.monostr.app.translate.TranslateClient(http)
    @Provides @Singleton fun languageDetector(): com.monostr.app.translate.LanguageDetector = com.monostr.app.translate.LanguageDetector()
    @Provides @Singleton fun dmSettings(prefs: AccountPrefs): DmSettingsStore = PrefsDmSettingsStore(prefs)
    @Provides @Singleton fun hints(prefs: AccountPrefs): HintStore = PrefsHintStore(prefs)
    @Provides @Singleton fun drafts(prefs: AccountPrefs): DraftStore = PrefsDraftStore(prefs)
    @Provides @Singleton fun pendingSetup(prefs: AccountPrefs): PendingSetupStore = PrefsPendingSetupStore(prefs)
    @Provides @Singleton fun feedTabs(prefs: AccountPrefs): FeedTabsCache = PrefsFeedTabsStore(prefs)
    /** One client for all HTTP, routed by the gate; no logging interceptor (the watcher register body carries the view key). */
    @Provides @Singleton fun okHttp(gate: com.monostr.nostr.net.NetworkGate, tor: dagger.Lazy<com.monostr.app.net.TorController>): OkHttpClient =
        // asked in Tor mode only, so with the switch off nothing of Tor is built
        com.monostr.app.net.gatedOkHttp(gate, torReady = { tor.get().routeReadyNow })
    @Provides @Singleton fun sendGate(gate: com.monostr.nostr.net.NetworkGate, tor: dagger.Lazy<com.monostr.app.net.TorController>): com.monostr.app.net.SendGate =
        com.monostr.app.net.SendGate(gate, { tor.get().routeReadyNow }, com.monostr.app.net.torConnected(tor))
    @Provides @Singleton fun torRestartReturn(@ApplicationContext ctx: Context): com.monostr.app.net.TorRestartReturn =
        com.monostr.app.net.TorRestartReturn(java.io.File(ctx.noBackupFilesDir, "tor-return"))
    @Provides @Singleton fun torSwitchControl(
        @ApplicationContext ctx: Context,
        store: com.monostr.app.net.TorSwitchStore,
        gate: com.monostr.nostr.net.NetworkGate,
        http: OkHttpClient,
        session: com.monostr.app.session.NostrSession,
        pendingTips: PendingTipStore,
        tor: dagger.Lazy<com.monostr.app.net.TorController>,
        restartReturn: com.monostr.app.net.TorRestartReturn,
    ): com.monostr.app.net.TorSwitch = com.monostr.app.net.TorSwitch(
        store, gate, dropConnections = { http.dropAllConnections() }, session, pendingTips,
        acquire = { tor.get().acquire(it) }, release = { tor.get().release(it) }, releaseNow = { tor.get().releaseNow(it) },
        schedule = { com.monostr.app.work.BackgroundCheckWorker.schedule(ctx, it) },
        // only a debug build run by the instrumentation test suite answers otherwise; a release build has no such thing, see both TorRestartSeam.kt
        directRelayRouteUsed = com.monostr.app.net.directRouteEndsProcess { com.monostr.nostr.net.DirectRelayRoute.wasUsed },
        endProcess = com.monostr.app.net.TorProcessEnd(
            inForeground = { com.monostr.app.MainActivity.inForeground.get() },
            persistReturn = { restartReturn.persist() },
            restartActivity = { ctx.startActivity(android.content.Intent.makeRestartActivityTask(android.content.ComponentName(ctx, com.monostr.app.MainActivity::class.java))) },
            exit = { Runtime.getRuntime().exit(0) },
        )::invoke,
    )
    /** Its scope is the application's, as a switch outlives the screen that asked; [com.monostr.app.net.AppTorUi] touches [tor] in Tor mode only. */
    @Provides @Singleton fun torUi(
        gate: com.monostr.nostr.net.NetworkGate,
        store: com.monostr.app.net.TorSwitchStore,
        switch: com.monostr.app.net.TorSwitch,
        tor: dagger.Lazy<com.monostr.app.net.TorController>,
        restartReturn: com.monostr.app.net.TorRestartReturn,
    ): com.monostr.app.net.TorUi = com.monostr.app.net.AppTorUi(
        gate, store, switch, torState = { tor.get().state }, torRetry = { tor.get().retry() }, portConflict = { tor.get().portConflict }, minTurningMs = 1_500, scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        directRelayRouteUsed = { com.monostr.nostr.net.DirectRelayRoute.wasUsed }, restartReturn = restartReturn,
    )
    /** Spec link previews 3.4: one cache for the app; the fetcher shares the app's OkHttp pool but has its own limits. */
    @Provides @Singleton fun linkPreviews(@ApplicationContext ctx: Context, http: OkHttpClient): LinkPreviews =
        LinkPreviews(LinkPreviewFetcher(http, "monostr/${BuildConfig.VERSION_NAME} (+https://monostr.com)")::fetch, File(ctx.cacheDir, "link-previews"))
    @Provides @Singleton fun previewImages(@ApplicationContext ctx: Context, http: OkHttpClient): PreviewImages = PreviewImages.create(ctx, http)
}

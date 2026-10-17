package com.monostr.app.di

import com.monostr.app.data.linkpreview.PreviewImages
import com.monostr.app.BuildConfig
import com.monostr.app.data.linkpreview.LinkPreviewFetcher
import com.monostr.app.data.linkpreview.LinkPreviews
import java.io.File
import android.content.Context
import com.monostr.app.data.*
import com.monostr.nostr.nip55.SignerBridge
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun sessionStore(@ApplicationContext ctx: Context): SessionStore = PrefsSessionStore(ctx.monostrPrefs)
    @Provides @Singleton fun relayStore(@ApplicationContext ctx: Context): RelayStore = PrefsRelayStore(ctx.monostrPrefs)
    @Provides @Singleton fun searchRelayStore(@ApplicationContext ctx: Context): SearchRelayStore = PrefsSearchRelayStore(ctx.monostrPrefs)
    @Provides @Singleton fun recentSearchesStore(@ApplicationContext ctx: Context): RecentSearchesStore = PrefsRecentSearchesStore(ctx.monostrPrefs)
    @Provides @Singleton fun secretStore(@ApplicationContext ctx: Context): SecretStore = KeystoreSecretStore(ctx)
    @Provides @Singleton fun signerBridge(): SignerBridge = SignerBridge()
    @Provides @Singleton fun tipSettings(@ApplicationContext ctx: Context): TipSettingsStore = PrefsTipSettingsStore(ctx.monostrPrefs)
    @Provides @Singleton fun pendingTips(@ApplicationContext ctx: Context): PendingTipStore = PrefsPendingTipStore(ctx.monostrPrefs)
    @Provides @Singleton fun uiSettings(@ApplicationContext ctx: Context): UiSettingsStore = PrefsUiSettingsStore(ctx.monostrPrefs)
    @Provides @Singleton fun dmSettings(@ApplicationContext ctx: Context): DmSettingsStore = PrefsDmSettingsStore(ctx.monostrPrefs)
    @Provides @Singleton fun hints(@ApplicationContext ctx: Context): HintStore = PrefsHintStore(ctx.monostrPrefs)
    @Provides @Singleton fun drafts(@ApplicationContext ctx: Context): DraftStore = PrefsDraftStore(ctx.monostrPrefs)
    /** One client for all watchers; no logging interceptor (the register body carries the view key). */
    @Provides @Singleton fun okHttp(): OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    /** Spec link previews 3.4: one cache for the app; the fetcher shares the app's OkHttp pool but has its own limits. */
    @Provides @Singleton fun linkPreviews(@ApplicationContext ctx: Context, http: OkHttpClient): LinkPreviews =
        LinkPreviews(LinkPreviewFetcher(http, "monostr/${BuildConfig.VERSION_NAME} (+https://monostr.com)")::fetch, File(ctx.cacheDir, "link-previews"))
    @Provides @Singleton fun previewImages(@ApplicationContext ctx: Context, http: OkHttpClient): PreviewImages = PreviewImages.create(ctx, http)
}

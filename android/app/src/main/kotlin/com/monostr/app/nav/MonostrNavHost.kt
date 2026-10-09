package com.monostr.app.nav

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.monostr.app.MainActivity
import com.monostr.app.data.TipSettingsStore
import com.monostr.app.data.UiSettingsStore
import com.monostr.app.session.NostrSession
import com.monostr.app.session.Ready
import com.monostr.app.session.SessionState
import com.monostr.app.ui.bookmarks.BookmarksScreen
import com.monostr.app.ui.common.LocalQuoteLoader
import com.monostr.app.ui.common.QuoteLoader
import com.monostr.app.ui.common.StartScreen
import com.monostr.app.ui.compose.ComposeScreen
import com.monostr.app.ui.dm.ChatScreen
import com.monostr.app.ui.dm.ConversationsScreen
import com.monostr.app.ui.feed.FeedScreen
import com.monostr.app.ui.login.LoginScreen
import com.monostr.app.ui.media.LocalMediaSettings
import com.monostr.app.ui.media.MediaSettings
import com.monostr.app.ui.media.MediaViewerScreen
import com.monostr.app.ui.media.VideoPlayerScreen
import com.monostr.app.ui.monero.MoneroSetupScreen
import com.monostr.app.ui.notifications.NotificationsScreen
import com.monostr.app.ui.profile.ProfileEditScreen
import com.monostr.app.ui.profile.ProfileScreen
import com.monostr.app.ui.search.SearchScreen
import com.monostr.app.ui.settings.AboutScreen
import com.monostr.app.ui.settings.MutedScreen
import com.monostr.app.ui.settings.SettingsScreen
import com.monostr.app.ui.settings.SupportScreen
import com.monostr.app.ui.thread.ThreadScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

object Routes {
    const val PROFILE_EDIT = "profile-edit"
    const val LOGIN = "login"
    const val FEED = "feed"
    const val SETTINGS = "settings"
    const val MONERO_SETUP = "monero-setup"
    const val NOTIFICATIONS = "notifications"
    const val MESSAGES = "messages"
    const val SEARCH = "search?q={q}"
    const val BOOKMARKS = "bookmarks"
    const val SUPPORT = "support"
    const val ABOUT = "about"
    const val MUTED = "muted"
    fun thread(id: String) = "thread/$id"
    fun profile(pubkey: String) = "profile/$pubkey"
    fun dm(pubkey: String) = "dm/$pubkey"
    fun compose(replyTo: String? = null, quote: String? = null) = when {
        replyTo != null -> "compose?replyTo=$replyTo"
        quote != null -> "compose?quote=$quote"
        else -> "compose"
    }
    fun moneroSetup(intro: Boolean) = "monero-setup?intro=$intro"
    fun search(q: String? = null) = if (q == null) "search" else "search?q=" + android.net.Uri.encode(q)
    fun media(noteId: String, index: Int) = "media/$noteId/$index"
    fun video(url: String) = "video?url=" + android.net.Uri.encode(url)
    /** Pictures that are not in the database (direct messages), paged from [index]. */
    fun mediaUrls(urls: List<String>, index: Int = 0) = "media-urls?u=" + android.net.Uri.encode(urls.joinToString("\n")) + "&i=$index"
}

/** Spec 3.2/3.4: how the note cards treat media, from UiSettingsStore, provided once for the whole nav host. */
@HiltViewModel
class MediaSettingsViewModel @Inject constructor(ui: UiSettingsStore) : ViewModel() {
    val settings: StateFlow<MediaSettings> = combine(ui.blurSensitive, ui.mediaOnTap) { b, t -> MediaSettings(b, t) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MediaSettings())
}

/** Spec 6: the receiving onboarding is offered once after login, skippable. */
@HiltViewModel
class OnboardingGateViewModel @Inject constructor(settings: TipSettingsStore) : ViewModel() {
    val showIntro: StateFlow<Boolean?> = combine(settings.setup, settings.onboardingSeen) { setup, seen -> setup == null && !seen }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@Composable
fun MonostrNavHost(session: NostrSession) {
    val state by session.state.collectAsState()
    // restore on start and again whenever background work released the engine (state back to Loading);
    // restore() is mutex-guarded and a no-op while Active, so a repeat call costs nothing
    LaunchedEffect(state) { if (state is SessionState.Loading) session.restore() }
    when (val s = state) {
        SessionState.Loading -> StartScreen()
        is SessionState.LoggedOut -> LoginScreen()
        is SessionState.Active -> LoggedInNavHost(s.ready)
    }
}

@Composable
private fun LoggedInNavHost(ready: Ready, gate: OnboardingGateViewModel = hiltViewModel(), media: MediaSettingsViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    val showIntro by gate.showIntro.collectAsState()
    val mediaSettings by media.settings.collectAsState()
    // one loader per logged-in account; a new login gets a new Ready and so a new loader
    val quoteLoader = remember(ready) { QuoteLoader(ready.quotes, ready.profiles, ready.muted) }
    var introShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(showIntro) {
        if (showIntro == true && !introShown) {
            introShown = true
            nav.navigate(Routes.moneroSetup(intro = true))
        }
    }
    // a tapped DM notification: open that chat once
    val pendingDm by MainActivity.pendingDm.collectAsState()
    LaunchedEffect(pendingDm) {
        pendingDm?.let {
            MainActivity.pendingDm.value = null
            nav.navigate(Routes.dm(it))
        }
    }
    // the "waiting for the signer" summary: open the Messages tab, where the parked messages unlock
    val pendingMessages by MainActivity.pendingMessages.collectAsState()
    LaunchedEffect(pendingMessages) {
        if (pendingMessages) {
            MainActivity.pendingMessages.value = false
            nav.navigate(Routes.MESSAGES) { popUpTo(Routes.FEED); launchSingleTop = true }
        }
    }
    val onOpenMedia: (String, Int) -> Unit = { id, i -> nav.navigate(Routes.media(id, i)) }
    val onOpenVideo: (String) -> Unit = { nav.navigate(Routes.video(it)) }
    val onMessages: () -> Unit = { nav.navigate(Routes.MESSAGES) { popUpTo(Routes.FEED); launchSingleTop = true } }
    // spec 11.1: bookmarks are a tab, so they navigate like the other tabs
    val onBookmarks: () -> Unit = { nav.navigate(Routes.BOOKMARKS) { popUpTo(Routes.FEED); launchSingleTop = true } }
    CompositionLocalProvider(LocalMediaSettings provides mediaSettings, LocalQuoteLoader provides quoteLoader) {
        NavHost(navController = nav, startDestination = Routes.FEED) {
            composable(Routes.FEED) {
                FeedScreen(
                    onOpenThread = { nav.navigate(Routes.thread(it)) },
                    onOpenProfile = { nav.navigate(Routes.profile(it)) },
                    onCompose = { nav.navigate(Routes.compose()) },
                    onReply = { nav.navigate(Routes.compose(it)) },
                    onQuote = { nav.navigate(Routes.compose(quote = it)) },
                    onSupport = { nav.navigate(Routes.SUPPORT) },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onSearch = { nav.navigate(Routes.search()) { popUpTo(Routes.FEED); launchSingleTop = true } },
                    onMessages = onMessages,
                    onNotifications = { nav.navigate(Routes.NOTIFICATIONS) { popUpTo(Routes.FEED); launchSingleTop = true } },
                    onHashtag = { nav.navigate(Routes.search("#$it")) },
                    onBookmarks = onBookmarks,
                    onOpenMedia = onOpenMedia,
                    onOpenVideo = onOpenVideo,
                )
            }
            composable(Routes.BOOKMARKS) {
                BookmarksScreen(
                    onFeed = { nav.popBackStack(Routes.FEED, inclusive = false) },
                    onSearch = { nav.navigate(Routes.search()) { popUpTo(Routes.FEED); launchSingleTop = true } },
                    onMessages = onMessages,
                    onNotifications = { nav.navigate(Routes.NOTIFICATIONS) { popUpTo(Routes.FEED); launchSingleTop = true } },
                    onOpenThread = { nav.navigate(Routes.thread(it)) },
                    onOpenProfile = { nav.navigate(Routes.profile(it)) },
                    onReply = { nav.navigate(Routes.compose(it)) },
                    onQuote = { nav.navigate(Routes.compose(quote = it)) },
                    onHashtag = { nav.navigate(Routes.search("#$it")) },
                    onOpenMedia = onOpenMedia,
                    onOpenVideo = onOpenVideo,
                )
            }
            composable(Routes.NOTIFICATIONS) {
                NotificationsScreen(
                    onFeed = { nav.popBackStack(Routes.FEED, inclusive = false) },
                    onSearch = { nav.navigate(Routes.search()) { popUpTo(Routes.FEED); launchSingleTop = true } },
                    onBookmarks = onBookmarks,
                    onMessages = onMessages,
                    onOpenThread = { nav.navigate(Routes.thread(it)) },
                    onOpenProfile = { nav.navigate(Routes.profile(it)) },
                )
            }
            composable(Routes.MESSAGES) {
                ConversationsScreen(
                    onFeed = { nav.popBackStack(Routes.FEED, inclusive = false) },
                    onSearch = { nav.navigate(Routes.search()) { popUpTo(Routes.FEED); launchSingleTop = true } },
                    onBookmarks = onBookmarks,
                    onNotifications = { nav.navigate(Routes.NOTIFICATIONS) { popUpTo(Routes.FEED); launchSingleTop = true } },
                    onOpenChat = { nav.navigate(Routes.dm(it)) },
                )
            }
            composable("dm/{pubkey}", arguments = listOf(navArgument("pubkey") { type = NavType.StringType })) {
                ChatScreen(
                    onBack = { nav.popBackStack() },
                    onOpenProfile = { nav.navigate(Routes.profile(it)) },
                    onOpenThread = { nav.navigate(Routes.thread(it)) },
                    onHashtag = { nav.navigate(Routes.search("#$it")) },
                    onOpenMedia = { urls, i -> nav.navigate(Routes.mediaUrls(urls, i)) },
                    onOpenVideo = onOpenVideo,
                )
            }
            composable(Routes.SEARCH, arguments = listOf(navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null })) {
                SearchScreen(
                    onFeed = { nav.popBackStack(Routes.FEED, inclusive = false) },
                    onBookmarks = onBookmarks,
                    onMessages = onMessages,
                    onNotifications = { nav.navigate(Routes.NOTIFICATIONS) { popUpTo(Routes.FEED); launchSingleTop = true } },
                    onOpenThread = { nav.navigate(Routes.thread(it)) },
                    onOpenProfile = { nav.navigate(Routes.profile(it)) },
                    onReply = { nav.navigate(Routes.compose(it)) },
                    onQuote = { nav.navigate(Routes.compose(quote = it)) },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onOpenMedia = onOpenMedia,
                    onOpenVideo = onOpenVideo,
                )
            }
            composable("thread/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                ThreadScreen(
                    onBack = { nav.popBackStack() },
                    onOpenProfile = { nav.navigate(Routes.profile(it)) },
                    onReply = { nav.navigate(Routes.compose(it)) },
                    onQuote = { nav.navigate(Routes.compose(quote = it)) },
                    onOpenThread = { nav.navigate(Routes.thread(it)) },
                    onHashtag = { nav.navigate(Routes.search("#$it")) },
                    onOpenMedia = onOpenMedia,
                    onOpenVideo = onOpenVideo,
                )
            }
            composable("profile/{pubkey}", arguments = listOf(navArgument("pubkey") { type = NavType.StringType })) {
                ProfileScreen(
                    onBack = { nav.popBackStack() },
                    onEditProfile = { nav.navigate(Routes.PROFILE_EDIT) },
                    onOpenThread = { nav.navigate(Routes.thread(it)) },
                    onReply = { nav.navigate(Routes.compose(it)) },
                    onQuote = { nav.navigate(Routes.compose(quote = it)) },
                    onOpenProfile = { nav.navigate(Routes.profile(it)) },
                    onHashtag = { nav.navigate(Routes.search("#$it")) },
                    onOpenMedia = onOpenMedia,
                    onOpenVideo = onOpenVideo,
                    onMessage = { nav.navigate(Routes.dm(it)) },
                )
            }
            composable(
                "compose?replyTo={replyTo}&quote={quote}",
                arguments = listOf(
                    navArgument("replyTo") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("quote") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) {
                ComposeScreen(onDone = { nav.popBackStack() })
            }
            composable(
                "media/{noteId}/{index}",
                arguments = listOf(navArgument("noteId") { type = NavType.StringType }, navArgument("index") { type = NavType.IntType }),
            ) {
                MediaViewerScreen(onBack = { nav.popBackStack() })
            }
            composable(
                "media-urls?u={u}&i={i}",
                arguments = listOf(navArgument("u") { type = NavType.StringType }, navArgument("i") { type = NavType.IntType; defaultValue = 0 }),
            ) {
                MediaViewerScreen(onBack = { nav.popBackStack() })
            }
            composable(
                "video?url={url}",
                arguments = listOf(navArgument("url") { type = NavType.StringType }),
            ) { entry ->
                VideoPlayerScreen(url = checkNotNull(entry.arguments?.getString("url")), onBack = { nav.popBackStack() })
            }
            composable(Routes.PROFILE_EDIT) { ProfileEditScreen(onDone = { nav.popBackStack() }) }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onMoneroSetup = { nav.navigate(Routes.moneroSetup(intro = false)) },
                    onSupport = { nav.navigate(Routes.SUPPORT) },
                    onAbout = { nav.navigate(Routes.ABOUT) },
                    onMuted = { nav.navigate(Routes.MUTED) },
                )
            }
            composable(Routes.SUPPORT) { SupportScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.MUTED) { MutedScreen(onBack = { nav.popBackStack() }, onOpenProfile = { nav.navigate(Routes.profile(it)) }) }
            composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }, onOpenProfile = { nav.navigate(Routes.profile(it)) }) }
            composable(
                "monero-setup?intro={intro}",
                arguments = listOf(navArgument("intro") { type = NavType.BoolType; defaultValue = false }),
            ) {
                MoneroSetupScreen(onDone = { nav.popBackStack() })
            }
        }
    }
}

package com.monostr.app.nav

import com.monostr.app.ui.common.NoteLinks
import com.monostr.app.data.linkpreview.PreviewImages
import com.monostr.app.ui.media.LocalPreviewImageLoader
import com.monostr.app.data.linkpreview.LinkPreviews
import com.monostr.app.ui.media.LinkPreviewLoader
import com.monostr.app.ui.media.LocalLinkPreviewLoader
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavController
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
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.monostr.app.MainActivity
import com.monostr.app.data.TipSettingsStore
import com.monostr.app.data.UiSettingsStore
import com.monostr.app.session.NostrSession
import com.monostr.app.session.Ready
import com.monostr.app.session.SessionState
import com.monostr.app.ui.article.ArticleScreen
import com.monostr.app.ui.bookmarks.BookmarksScreen
import com.monostr.app.ui.common.LocalOpenArticle
import com.monostr.app.ui.common.LocalOpenList
import com.monostr.nostr.model.Hex64
import com.monostr.nostr.model.AddressRef
import com.monostr.app.ui.common.AddressLoader
import com.monostr.app.ui.common.LocalAddressLoader
import com.monostr.app.ui.common.LocalQuoteLoader
import com.monostr.app.ui.common.LocalPollLoader
import com.monostr.app.ui.common.PollLoader
import com.monostr.app.ui.common.PrefsPollSettings
import com.monostr.app.data.AccountPrefs
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monostr.app.ui.common.LocalNoteTranslator
import com.monostr.app.ui.common.NoteTranslationViewModel
import com.monostr.app.ui.common.TranslateConsentHost
import com.monostr.app.ui.settings.SettingsSection
import com.monostr.app.ui.common.QuoteLoader
import com.monostr.app.ui.common.LocalTorSheets
import com.monostr.app.ui.common.StartScreen
import com.monostr.app.ui.common.TorReturnEffect
import com.monostr.app.ui.common.TorReturnForget
import com.monostr.app.ui.compose.ComposeScreen
import com.monostr.app.ui.dm.ChatScreen
import com.monostr.app.ui.dm.ConversationsScreen
import com.monostr.app.ui.feed.FeedScreen
import com.monostr.app.ui.lists.ListEditScreen
import com.monostr.app.ui.lists.ListScreen
import com.monostr.app.ui.accounts.AccountSwitcherHost
import com.monostr.app.ui.accounts.AccountSwitcherSheet
import com.monostr.app.ui.login.CreateAccountScreen
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
import com.monostr.app.ui.follows.FollowsScreen
import com.monostr.app.ui.settings.MutedScreen
import com.monostr.app.ui.settings.SettingsOverview
import com.monostr.app.ui.settings.SettingsPage
import com.monostr.app.ui.settings.SettingsViewModel
import com.monostr.app.ui.settings.pages.AccountPage
import com.monostr.app.ui.settings.pages.AppearancePage
import com.monostr.app.ui.settings.pages.ContentPage
import com.monostr.app.ui.settings.pages.ReactionsPage
import com.monostr.app.ui.settings.pages.DmRelaysPage
import com.monostr.app.ui.settings.pages.HelpPage
import com.monostr.app.ui.settings.pages.MoneroPage
import com.monostr.app.ui.settings.pages.NotificationsPage
import com.monostr.app.ui.settings.pages.PrivacyPage
import com.monostr.app.ui.settings.pages.RelaysPage
import com.monostr.app.ui.settings.pages.SearchRelaysPage
import com.monostr.app.ui.settings.pages.TranslationPage
import com.monostr.app.ui.settings.ShowKeyScreen
import com.monostr.app.ui.settings.SupportScreen
import com.monostr.app.ui.thread.ThreadScreen
import com.monostr.nostr.model.ListAddress
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
    /** Spec 2026-11-11: the nested graph of the overview and its pages, the scope of their shared view model. */
    const val SETTINGS_GRAPH = "settings-graph"
    const val SETTINGS_OVERVIEW = "$SETTINGS?section={section}"
    const val SETTINGS_PAGE = "$SETTINGS/{page}?focus={focus}"
    const val MONERO_SETUP = "monero-setup"
    const val NOTIFICATIONS = "notifications"
    const val MESSAGES = "messages"
    const val SEARCH = "search?q={q}"
    const val BOOKMARKS = "bookmarks"
    const val SUPPORT = "support"
    const val MUTED = "muted"
    const val MUTED_THREADS = "muted-threads"
    const val SHOW_KEY = "show-key"
    const val ADD_ACCOUNT_KEY = "add-account-key"
    const val ADD_ACCOUNT_CREATE = "add-account-create"
    const val LIST = "list/{address}"
    const val LIST_EDIT = "list-edit?address={address}&add={add}"
    /** Spec 2026-10-18 §2.3: the address is URL-encoded (a foreign `d` may hold any character). */
    fun list(address: ListAddress) = "list/" + android.net.Uri.encode(address.toString())
    fun listEdit(address: ListAddress? = null, add: String? = null): String {
        val params = listOfNotNull(address?.let { "address=" + android.net.Uri.encode(it.toString()) }, add?.let { "add=$it" })
        return if (params.isEmpty()) "list-edit" else "list-edit?" + params.joinToString("&")
    }
    /** Ids and pubkeys in a route are hex or name nothing ([Hex64.orNone]): a stranger's tag value with a slash found no destination and closed the app. */
    fun thread(id: String) = "thread/${Hex64.orNone(id)}"
    /** Spec 2026-10-29 article reader: [naddr] is the bech32 `naddr1…` (letters and digits only, so no encoding). */
    fun article(naddr: String, foreign: Boolean = true) = "article/$naddr?foreign=$foreign"
    fun profile(pubkey: String) = "profile/${Hex64.orNone(pubkey)}"
    /** Spec 2026-10-30 follow lists: [followers] opens the second tab. */
    fun follows(pubkey: String, followers: Boolean) = "follows/${Hex64.orNone(pubkey)}?followers=$followers"
    fun dm(pubkey: String) = "dm/${Hex64.orNone(pubkey)}"
    /** Spec 2026-10-29 article reader: [article] is the bech32 `naddr1…` of the article commented on, [parent] the comment answered. */
    fun commentOn(article: String, parent: String? = null) = "compose?article=$article" + (parent?.let { "&parent=$it" } ?: "")
    fun compose(replyTo: String? = null, quote: String? = null, text: String? = null) = when {
        replyTo != null -> "compose?replyTo=$replyTo"
        quote != null -> "compose?quote=$quote"
        text != null -> "compose?text=" + android.net.Uri.encode(text)
        else -> "compose"
    }
    /**
     * Where a Tor switch brings the user back to, as it rebuilds the session and with it this nav host (spec 2026-11-06): null for the feed, where
     * the host starts anyway, and for a screen deep in the stack, whose state went with the old session.
     */
    fun afterTorSwitch(destination: String?): String? = when {
        destination == null -> null
        // the overview and every subpage: back to Tor & privacy, where the Tor row is
        destination.startsWith(SETTINGS) -> settings(SettingsSection.PRIVACY)
        destination == MESSAGES || destination == BOOKMARKS || destination == NOTIFICATIONS -> destination
        destination == SEARCH -> "search"
        else -> null
    }

    /** Settings opened at one section ([SettingsSection]): the overview, with that page opened on top of it. */
    fun settings(section: String) = "settings?section=$section"

    /** One settings subpage, at the setting [focus] (a search result) when given. */
    fun settingsPage(page: SettingsPage, focus: String? = null) = "$SETTINGS/${page.id}" + (focus?.let { "?focus=${android.net.Uri.encode(it)}" } ?: "")
    fun moneroSetup(intro: Boolean, upgrade: Boolean = false) = "monero-setup?intro=$intro&upgrade=$upgrade"
    fun search(q: String? = null) = if (q == null) "search" else "search?q=" + android.net.Uri.encode(q)
    fun media(noteId: String, index: Int) = "media/$noteId/$index"
    fun video(url: String) = "video?url=" + android.net.Uri.encode(url)
    /** Pictures that are not in the database (direct messages), paged from [index]. */
    fun mediaUrls(urls: List<String>, index: Int = 0) = "media-urls?u=" + android.net.Uri.encode(urls.joinToString("\n")) + "&i=$index"
}

/** Spec 3.2/3.4: how the note cards treat media, from UiSettingsStore, provided once for the whole nav host. */
@HiltViewModel
class MediaSettingsViewModel @Inject constructor(ui: UiSettingsStore, val accountPrefs: AccountPrefs, val linkPreviews: LinkPreviews, val previewImages: PreviewImages) : ViewModel() {
    val settings: StateFlow<MediaSettings> = combine(ui.blurSensitive, ui.mediaOnTap, ui.linkPreviews, ui.gifMode) { b, t, l, g -> MediaSettings(b, t, l, g) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MediaSettings())
}

/** Spec 6: the receiving onboarding is offered once after login, skippable. */
@HiltViewModel
class OnboardingGateViewModel @Inject constructor(private val settings: TipSettingsStore) : ViewModel() {
    /** No setup on this device and never answered: the intro is still open, [MoneroIntroGate] decides whether it comes. */
    val showIntro: StateFlow<Boolean?> = combine(settings.setup, settings.onboardingSeen) { setup, seen -> setup == null && !seen }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    suspend fun markSeen() { runCatching { settings.setOnboardingSeen() } }
}

@Composable
fun MonostrNavHost(session: NostrSession) {
    val state by session.state.collectAsState()
    // restore on start and again whenever background work released the engine (state back to Loading);
    // restore() is mutex-guarded and a no-op while Active, so a repeat call costs nothing
    LaunchedEffect(state) { if (state is SessionState.Loading) session.restore() }
    // the app is visible with this session: what only the UI needs starts now (a background check's session leaves it out)
    LaunchedEffect(state) { (state as? SessionState.Active)?.ready?.startForegroundWork?.invoke() }
    val owner = remember { ShownAccount() }
    when (val s = state) {
        SessionState.Loading -> StartScreen()
        is SessionState.LoggedOut -> {
            owner.pubkey = null
            TorReturnForget()
            // spec 2026-10-17 §11: login is not a nav route; a saved flag switches to the create flow
            var creating by rememberSaveable { mutableStateOf(false) }
            // final review I2: a failed restore with other accounts kept offers the switcher (a working one, or removing the stale one)
            val kept by session.accounts.collectAsState(initial = emptyList())
            var switcher by rememberSaveable { mutableStateOf(false) }
            val offerSwitcher = s.message == NostrSession.RESTORE_FAILED && kept.size > 1
            if (creating) {
                CreateAccountScreen(onLeave = { creating = false })
            } else {
                LoginScreen(onCreate = { creating = true }, onAccounts = if (offerSwitcher) ({ switcher = true }) else null)
            }
            // the login screen is the add flow here: «Add account» and «Sign in again» lead back to it
            if (switcher && offerSwitcher && !creating) AccountSwitcherSheet(hiltViewModel(), onAdd = { switcher = false }, onDismiss = { switcher = false })
        }
        // spec «Switch flow» step 5: another account is shown only by a fresh activity (MainActivity replaces this one on
        // `switched`); until then this one shows the start screen, so it builds no screen (or takes no notification target) of it
        is SessionState.Active -> if (owner.claim(s.ready.pubkey)) {
            androidx.compose.runtime.key(s.ready.pubkey) { LoggedInNavHost(s.ready, created = session.wasCreatedHere(s.ready.pubkey)) }
        } else {
            StartScreen()
        }
    }
}

/** The account this activity shows: the first one active in it; the login screen in between frees it (a first login). */
private class ShownAccount {
    var pubkey: String? = null

    fun claim(active: String): Boolean {
        if (pubkey == null) pubkey = active
        return pubkey == active
    }
}

@Composable
private fun LoggedInNavHost(
    ready: Ready,
    /** The account was created in this process: its Monero intro asks no relay first. */
    created: Boolean = false,
    gate: OnboardingGateViewModel = hiltViewModel(),
    media: MediaSettingsViewModel = hiltViewModel(),
    translation: NoteTranslationViewModel = hiltViewModel(),
) {
    val nav = rememberNavController()
    // spec perf2 A2 (3): the first screen other than the home feed lets the session's background work go at once
    val torSheets = LocalTorSheets.current
    DisposableEffect(nav, ready) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            if (destination.route != Routes.FEED) ready.startupGate.open("navigation")
            torSheets.returnRoute = Routes.afterTorSwitch(destination.route)
        }
        nav.addOnDestinationChangedListener(listener)
        onDispose {
            nav.removeOnDestinationChangedListener(listener)
            torSheets.returnRoute = null
        }
    }
    TorReturnEffect { route ->
        // a route stored by an older version may be gone by now: the feed then, where the host starts anyway
        try {
            nav.navigate(route) { popUpTo(Routes.FEED); launchSingleTop = true }
        } catch (_: IllegalArgumentException) {
        }
    }
    val showIntro by gate.showIntro.collectAsState()
    val mediaSettings by media.settings.collectAsState()
    // language detection for the note cards waits for this session's feed (final review I2)
    remember(ready) { ready.startupGate.also { translation.useStartupGate(it) } }
    // one loader per logged-in account; a new login gets a new Ready and so a new loader
    val quoteLoader = remember(ready) { QuoteLoader(ready.quotes, ready.profiles, ready.muted) }
    val addressLoader = remember(ready) { AddressLoader(ready.addresses, ready.profiles, ready.muted) }
    val linkPreviewLoader = remember(ready) { LinkPreviewLoader(media.linkPreviews, ready.muted, ready.mute.state) }
    // polls spec 2026-11-12: votes fetched once per poll while this account is logged in; fetches and votes run in the host's scope
    val pollScope = rememberCoroutineScope()
    val pollLoader = remember(ready) {
        PollLoader(ready.polls, ready.follows.followed(), ready.muted, ready.pubkey, PrefsPollSettings(media.accountPrefs), { System.currentTimeMillis() / 1000 }, pollScope)
    }
    var introShown by rememberSaveable { mutableStateOf(false) }
    // backlog 121: the feed first; the intro only once the relays said for sure that nothing is set up, and only over the feed
    var introDue by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(showIntro) {
        if (showIntro == true && !introShown && !introDue) {
            introDue = com.monostr.app.ui.monero.MoneroIntroGate.due(created, lookup = { ready.tips.ownMonero() }, markSeen = { gate.markSeen() })
        }
    }
    val shownEntry by nav.currentBackStackEntryAsState()
    val onFeed = shownEntry?.destination?.route == Routes.FEED
    LaunchedEffect(introDue, onFeed, showIntro) {
        if (introDue && onFeed && showIntro == true && !introShown) {
            introShown = true
            nav.navigate(Routes.moneroSetup(intro = true))
        }
    }
    // a tapped DM notification: open that chat once
    // a tapped notification: only once its own account runs (never a chat of A inside B); taken atomically
    val pendingOpen by MainActivity.pendingOpen.collectAsState()
    LaunchedEffect(pendingOpen, ready.pubkey) {
        val target = pendingOpen
        if (!com.monostr.app.work.shouldConsume(target, ready.pubkey)) return@LaunchedEffect
        if (!MainActivity.pendingOpen.compareAndSet(target, null)) return@LaunchedEffect
        target!!.peer?.let { nav.navigate(Routes.dm(it)) }
        if (target.messages) nav.navigate(Routes.MESSAGES) { popUpTo(Routes.FEED); launchSingleTop = true }
    }
    val onOpenMedia: (String, Int) -> Unit = { id, i -> nav.navigate(Routes.media(id, i)) }
    val openArticle: (AddressRef, Boolean) -> Unit = { ref, foreign -> ref.toBech32OrNull()?.let { nav.navigate(Routes.article(it, foreign)) } }
    val openList: (ListAddress) -> Unit = { nav.navigate(Routes.list(it)) }
    val onOpenVideo: (String) -> Unit = { nav.navigate(Routes.video(it)) }
    val onMessages: () -> Unit = { nav.navigate(Routes.MESSAGES) { popUpTo(Routes.FEED); launchSingleTop = true } }
    // spec 11.1: bookmarks are a tab, so they navigate like the other tabs
    val onBookmarks: () -> Unit = { nav.navigate(Routes.BOOKMARKS) { popUpTo(Routes.FEED); launchSingleTop = true } }
    // spec 2026-11-03: one consent sheet for every card's first «Translate»
    TranslateConsentHost(translation, onSettings = { nav.navigate(Routes.settings(SettingsSection.TRANSLATION)) })
    CompositionLocalProvider(LocalMediaSettings provides mediaSettings, LocalQuoteLoader provides quoteLoader, LocalPollLoader provides pollLoader, LocalAddressLoader provides addressLoader, LocalOpenArticle provides openArticle, LocalOpenList provides openList, LocalLinkPreviewLoader provides linkPreviewLoader, LocalPreviewImageLoader provides media.previewImages.loader, LocalNoteTranslator provides translation) {
      // spec «Switcher UI»: one switcher for the feed's avatar and Settings; the add sheet's key login and create flow are routes here
      AccountSwitcherHost(
          onLoginWithKey = { nav.navigate(Routes.ADD_ACCOUNT_KEY) { launchSingleTop = true } },
          onCreate = { nav.navigate(Routes.ADD_ACCOUNT_CREATE) { launchSingleTop = true } },
      ) {
        val tabNav = remember(nav) { com.monostr.app.ui.common.TabNav(onSettings = { nav.navigate(Routes.SETTINGS) }, onOwnProfile = { nav.navigate(Routes.profile(ready.pubkey)) }) }
        CompositionLocalProvider(com.monostr.app.ui.common.LocalTabNav provides tabNav) {
        NavHost(navController = nav, startDestination = Routes.FEED) {
            // spec «Adding an account»: the first login's screens; a login that works switches, and the activity recreates
            composable(Routes.ADD_ACCOUNT_KEY) { LoginScreen(adding = true, onBack = { nav.popBackStack(Routes.ADD_ACCOUNT_KEY, inclusive = true) }) }
            composable(Routes.ADD_ACCOUNT_CREATE) { CreateAccountScreen(onLeave = { nav.popBackStack(Routes.ADD_ACCOUNT_CREATE, inclusive = true) }, adding = true) }
            composable(Routes.FEED) {
                FeedScreen(
                    onOpenThread = { nav.navigate(Routes.thread(it)) },
                    onOpenProfile = { nav.navigate(Routes.profile(it)) },
                    onCompose = { nav.navigate(Routes.compose()) },
                    onReply = { nav.navigate(Routes.compose(it)) },
                    onQuote = { nav.navigate(Routes.compose(quote = it)) },
                    onSupport = { nav.navigate(Routes.SUPPORT) },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onManageRelays = { nav.navigate(Routes.settingsPage(SettingsPage.RELAYS)) },
                    onSearch = { nav.navigate(Routes.search()) { popUpTo(Routes.FEED); launchSingleTop = true } },
                    onMessages = onMessages,
                    onNotifications = { nav.navigate(Routes.NOTIFICATIONS) { popUpTo(Routes.FEED); launchSingleTop = true } },
                    onHashtag = { nav.navigate(Routes.search(NoteLinks.searchFor(it))) },
                    onBookmarks = onBookmarks,
                    onOpenMedia = onOpenMedia,
                    onOpenVideo = onOpenVideo,
                    onOpenList = { nav.navigate(Routes.list(it)) },
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
                    onHashtag = { nav.navigate(Routes.search(NoteLinks.searchFor(it))) },
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
                    onBack = { nav.back() },
                    onOpenProfile = { nav.navigate(Routes.profile(it)) },
                    onOpenThread = { nav.navigate(Routes.thread(it)) },
                    onHashtag = { nav.navigate(Routes.search(NoteLinks.searchFor(it))) },
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
                    onOpenList = { nav.navigate(Routes.list(it)) },
                )
            }
            composable("thread/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                ThreadScreen(
                    onBack = { nav.back() },
                    onOpenProfile = { nav.navigate(Routes.profile(it)) },
                    onReply = { nav.navigate(Routes.compose(it)) },
                    onQuote = { nav.navigate(Routes.compose(quote = it)) },
                    onOpenThread = { nav.navigate(Routes.thread(it)) },
                    onHashtag = { nav.navigate(Routes.search(NoteLinks.searchFor(it))) },
                    onOpenMedia = onOpenMedia,
                    onOpenVideo = onOpenVideo,
                )
            }
            composable(
                "article/{naddr}?foreign={foreign}",
                arguments = listOf(navArgument("naddr") { type = NavType.StringType }, navArgument("foreign") { type = NavType.BoolType; defaultValue = true }),
            ) {
                ArticleScreen(
                    onBack = { nav.back() },
                    onOpenProfile = { nav.navigate(Routes.profile(it)) },
                    onOpenThread = { nav.navigate(Routes.thread(it)) },
                    onOpenArticle = openArticle,
                    onQuote = { ref -> ref.toBech32OrNull()?.let { nav.navigate(Routes.compose(text = "\n\nnostr:$it")) } },
                    onReply = { ref, parent -> ref.toBech32OrNull()?.let { nav.navigate(Routes.commentOn(it, parent)) } },
                    onQuoteNote = { nav.navigate(Routes.compose(quote = it)) },
                    onOpenNoteMedia = onOpenMedia,
                    onOpenVideo = onOpenVideo,
                    onHashtag = { nav.navigate(Routes.search(NoteLinks.searchFor(it))) },
                    onOpenMedia = { urls, i -> nav.navigate(Routes.mediaUrls(urls, i)) },
                )
            }
            composable("profile/{pubkey}", arguments = listOf(navArgument("pubkey") { type = NavType.StringType })) {
                ProfileScreen(
                    onBack = { nav.back() },
                    onEditProfile = { nav.navigate(Routes.PROFILE_EDIT) },
                    onOpenThread = { nav.navigate(Routes.thread(it)) },
                    onReply = { nav.navigate(Routes.compose(it)) },
                    onQuote = { nav.navigate(Routes.compose(quote = it)) },
                    onOpenProfile = { nav.navigate(Routes.profile(it)) },
                    onHashtag = { nav.navigate(Routes.search(NoteLinks.searchFor(it))) },
                    onOpenMedia = onOpenMedia,
                    onOpenVideo = onOpenVideo,
                    onMessage = { nav.navigate(Routes.dm(it)) },
                    onOpenList = { nav.navigate(Routes.list(it)) },
                    onCreateList = { nav.navigate(Routes.listEdit()) },
                    onNewListWith = { nav.navigate(Routes.listEdit(add = it)) },
                    onOpenPicture = { nav.navigate(Routes.mediaUrls(listOf(it))) },
                    onOpenFollows = { pubkey, followers -> nav.navigate(Routes.follows(pubkey, followers)) },
                )
            }
            composable(
                "follows/{pubkey}?followers={followers}",
                arguments = listOf(navArgument("pubkey") { type = NavType.StringType }, navArgument("followers") { type = NavType.BoolType; defaultValue = false }),
            ) {
                FollowsScreen(onBack = { nav.back() }, onOpenProfile = { nav.navigate(Routes.profile(it)) })
            }
            composable(Routes.LIST, arguments = listOf(navArgument("address") { type = NavType.StringType })) {
                ListScreen(
                    onBack = { nav.back() },
                    onOpenProfile = { nav.navigate(Routes.profile(it)) },
                    onEdit = { nav.navigate(Routes.listEdit(it)) },
                    onOpenThread = { nav.navigate(Routes.thread(it)) },
                    onReply = { nav.navigate(Routes.compose(it)) },
                    onQuote = { nav.navigate(Routes.compose(quote = it)) },
                    onHashtag = { nav.navigate(Routes.search(NoteLinks.searchFor(it))) },
                    onOpenMedia = onOpenMedia,
                    onOpenVideo = onOpenVideo,
                )
            }
            composable(
                Routes.LIST_EDIT,
                arguments = listOf(
                    navArgument("address") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("add") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) {
                // after a delete the list screen goes too (the edit screen is only opened from it for existing lists)
                ListEditScreen(onDone = { deleted -> if (deleted) nav.popBackStack(Routes.LIST, inclusive = true) else nav.back() })
            }
            composable(
                "compose?replyTo={replyTo}&quote={quote}&text={text}&article={article}&parent={parent}",
                arguments = listOf(
                    navArgument("replyTo") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("quote") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("text") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("article") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("parent") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) {
                ComposeScreen(onDone = { nav.back() })
            }
            composable(
                "media/{noteId}/{index}",
                arguments = listOf(navArgument("noteId") { type = NavType.StringType }, navArgument("index") { type = NavType.IntType }),
            ) {
                MediaViewerScreen(onBack = { nav.back() })
            }
            composable(
                "media-urls?u={u}&i={i}",
                arguments = listOf(navArgument("u") { type = NavType.StringType }, navArgument("i") { type = NavType.IntType; defaultValue = 0 }),
            ) {
                MediaViewerScreen(onBack = { nav.back() })
            }
            composable(
                "video?url={url}",
                arguments = listOf(navArgument("url") { type = NavType.StringType }),
            ) { entry ->
                VideoPlayerScreen(url = checkNotNull(entry.arguments?.getString("url")), onBack = { nav.back() })
            }
            composable(Routes.PROFILE_EDIT) { ProfileEditScreen(onDone = { nav.back() }) }
            // spec 2026-11-11: the overview and one page per category share one view model, scoped to this graph
            navigation(startDestination = Routes.SETTINGS_OVERVIEW, route = Routes.SETTINGS_GRAPH) {
                composable(
                    Routes.SETTINGS_OVERVIEW,
                    arguments = listOf(navArgument("section") { type = NavType.StringType; nullable = true; defaultValue = null }),
                ) { entry ->
                    val parent = remember(entry) { nav.getBackStackEntry(Routes.SETTINGS_GRAPH) }
                    SettingsOverview(
                        section = entry.arguments?.getString("section"),
                        vm = hiltViewModel<SettingsViewModel>(parent),
                        onBack = { nav.back() },
                        // a second tap during the push transition must not push the page twice
                        onOpen = { page, focus -> if (entry.isResumed()) nav.navigate(Routes.settingsPage(page, focus)) },
                        // the deep link opens its page from the first composition, before the entry is resumed
                        onRedirect = { page -> nav.navigate(Routes.settingsPage(page)) },
                    )
                }
                composable(
                    Routes.SETTINGS_PAGE,
                    arguments = listOf(
                        navArgument("page") { type = NavType.StringType },
                        navArgument("focus") { type = NavType.StringType; nullable = true; defaultValue = null },
                    ),
                ) { entry ->
                    val parent = remember(entry) { nav.getBackStackEntry(Routes.SETTINGS_GRAPH) }
                    val vm: SettingsViewModel = hiltViewModel(parent)
                    val focus = entry.arguments?.getString("focus")
                    // backlog 2026-11-11: every way off a page waits for it to be resumed, so a double tap during the
                    // transition neither pushes a screen twice nor pops two pages
                    val back: () -> Unit = { if (entry.isResumed()) nav.back() }
                    when (SettingsPage.of(entry.arguments?.getString("page"))) {
                        SettingsPage.ACCOUNT -> AccountPage(vm, focus, back, onShowKey = { if (entry.isResumed()) nav.navigate(Routes.SHOW_KEY) }, onLogout = { nav.popBackStack(Routes.SETTINGS_GRAPH, inclusive = true) })
                        SettingsPage.PRIVACY -> PrivacyPage(vm, focus, back, onMuted = { if (entry.isResumed()) nav.navigate(Routes.MUTED) }, onMutedThreads = { if (entry.isResumed()) nav.navigate(Routes.MUTED_THREADS) })
                        SettingsPage.RELAYS -> RelaysPage(vm, focus, back, onOpen = { if (entry.isResumed()) nav.navigate(Routes.settingsPage(it)) })
                        SettingsPage.DM_RELAYS -> DmRelaysPage(vm, focus, back)
                        SettingsPage.SEARCH_RELAYS -> SearchRelaysPage(vm, focus, back)
                        SettingsPage.MONERO -> MoneroPage(
                            vm, focus, back,
                            onMoneroSetup = { if (entry.isResumed()) nav.navigate(Routes.moneroSetup(intro = false)) },
                            onMoneroUpgrade = { if (entry.isResumed()) nav.navigate(Routes.moneroSetup(intro = false, upgrade = true)) },
                        )
                        SettingsPage.NOTIFICATIONS -> NotificationsPage(vm, focus, back)
                        SettingsPage.CONTENT -> ContentPage(vm, focus, back, onOpen = { if (entry.isResumed()) nav.navigate(Routes.settingsPage(it)) })
                        SettingsPage.REACTIONS -> ReactionsPage(vm, focus, back)
                        SettingsPage.TRANSLATION -> TranslationPage(vm, focus, back)
                        SettingsPage.APPEARANCE -> AppearancePage(vm, focus, back)
                        SettingsPage.HELP -> HelpPage(vm, focus, back, onOpenProfile = { if (entry.isResumed()) nav.navigate(Routes.profile(it)) })
                        null -> LaunchedEffect(Unit) { nav.back() }
                    }
                }
            }
            // pops only this route: "no key" and ON_STOP may both close it
            composable(Routes.SHOW_KEY) { ShowKeyScreen(onBack = { nav.popBackStack(Routes.SHOW_KEY, inclusive = true) }) }
            composable(Routes.SUPPORT) { SupportScreen(onBack = { nav.back() }) }
            composable(Routes.MUTED) { MutedScreen(threads = false, onBack = { nav.back() }, onOpenProfile = { nav.navigate(Routes.profile(it)) }) }
            composable(Routes.MUTED_THREADS) { MutedScreen(threads = true, onBack = { nav.back() }, onOpenProfile = { nav.navigate(Routes.profile(it)) }) }
            composable(
                "monero-setup?intro={intro}&upgrade={upgrade}",
                arguments = listOf(
                    navArgument("intro") { type = NavType.BoolType; defaultValue = false },
                    navArgument("upgrade") { type = NavType.BoolType; defaultValue = false },
                ),
            ) {
                MoneroSetupScreen(onDone = { nav.back() })
            }
        }
        }
      }
    }
}

/** Whether this entry is the settled top of the back stack; taps during a transition into another page arrive while it is not. */
/**
 * What a back arrow does: one screen back, never the last one, and nothing while a transition still runs.
 *
 * A bare `popBackStack()` also pops the start destination. A second tap on a back arrow during its screen's exit
 * animation (the feed's wordmark sits where the arrow was) took the feed itself off the stack and left the app black
 * until it was closed (owner, Pixel 2026-11-26 14:08).
 */
internal fun androidx.navigation.NavController.back() {
    val top = currentBackStackEntry ?: return
    if (previousBackStackEntry == null) return
    if (top.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED) popBackStack()
}

private fun androidx.navigation.NavBackStackEntry.isResumed(): Boolean = lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED

package com.monostr.app.ui.follows

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import com.monostr.nostr.repo.FollowState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.monostr.app.R
import com.monostr.app.session.NostrSession
import com.monostr.app.ui.common.Avatar
import com.monostr.nostr.Npub
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.FollowListsRepository
import com.monostr.nostr.PerfTrace
import com.monostr.nostr.repo.FollowRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.monostr.nostr.repo.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One of the two lists: the accounts shown so far, whether more are loading, whether it has ended. */
data class FollowColumn(val people: List<Profile> = emptyList(), val loading: Boolean = true, val ended: Boolean = false)

data class FollowsUi(val name: String = "", val following: FollowColumn = FollowColumn(), val followers: FollowColumn = FollowColumn())

/**
 * Spec 2026-10-30 follow lists: both columns load page by page as they scroll, like X. Following is known whole at
 * once (the contact list), only its profiles come in pages; followers come from the relays a page at a time, at most [MAX_FOLLOWERS].
 */
class FollowsController(
    private val pubkey: String,
    private val lists: FollowListsRepository,
    private val profiles: ProfileRepository,
    private val scope: CoroutineScope,
    private val pageSize: Int = PAGE_SIZE,
    /** The rows' follow buttons (like X); null in tests without them. */
    private val follows: FollowRepository? = null,
) {
    /** Whom the user follows (null = not known yet: the buttons wait), and which rows are writing right now. */
    val followed: StateFlow<Set<String>?> = follows?.followed()?.stateIn(scope, SharingStarted.Eagerly, null) ?: MutableStateFlow(null)
    private val _busy = MutableStateFlow(emptySet<String>())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    fun follow(target: String) = write(target) { it.follow(target) }
    fun unfollow(target: String) = write(target) { it.unfollow(target) }

    private fun write(target: String, block: suspend (FollowRepository) -> Unit) {
        val repo = follows ?: return
        if (target in _busy.value) return
        _busy.update { it + target }
        // a failed write leaves the list as it was; the button shows the stored state again
        scope.launch { try { safe { block(repo) } } finally { _busy.update { it - target } } }
    }

    private val _state = MutableStateFlow(FollowsUi())
    val state: StateFlow<FollowsUi> = _state.asStateFlow()

    private var following: List<String>? = null
    private var followingJob: Job? = null
    private var followersJob: Job? = null
    private var nextUntil: Long? = null
    private var followersStarted = false
    private val seen = HashSet<String>()

    fun start() {
        scope.launch { runCatching { profiles.get(pubkey) }.getOrNull()?.let { p -> _state.update { it.copy(name = p.shownName) } } }
        moreFollowing()
        moreFollowers()
    }

    fun moreFollowing() {
        if (followingJob?.isActive == true || _state.value.following.ended) return
        followingJob = scope.launch {
            val t = System.nanoTime()
            _state.update { it.copy(following = it.following.copy(loading = true)) }
            val all = following ?: safe { lists.following(pubkey) }.orEmpty().also { following = it }
            val shown = _state.value.following.people.size
            val next = all.drop(shown).take(pageSize)
            val people = load(next)
            _state.update { it.copy(following = FollowColumn(it.following.people + people, loading = false, ended = shown + next.size >= all.size)) }
            PerfTrace.mark("following page ${(System.nanoTime() - t) / 1_000_000}ms +${people.size} of ${all.size}")
        }
    }

    fun moreFollowers() {
        if (followersJob?.isActive == true || _state.value.followers.ended) return
        followersJob = scope.launch {
            _state.update { it.copy(followers = it.followers.copy(loading = true)) }
            val until = nextUntil
            if (followersStarted && until == null) { _state.update { it.copy(followers = it.followers.copy(loading = false, ended = true)) }; return@launch }
            followersStarted = true
            val t = System.nanoTime()
            val page = safe { lists.followers(pubkey, until, seen) }
            val fresh = page?.pubkeys.orEmpty().take(MAX_FOLLOWERS - seen.size)
            seen += fresh
            nextUntil = page?.nextUntil
            val people = load(fresh)
            val ended = fresh.isEmpty() || nextUntil == null || seen.size >= MAX_FOLLOWERS
            _state.update { it.copy(followers = FollowColumn(it.followers.people + people, loading = false, ended = ended)) }
            PerfTrace.mark("followers page ${(System.nanoTime() - t) / 1_000_000}ms +${people.size} total ${seen.size}")
        }
    }

    /** The profiles of [pubkeys] in that order; one unknown stays as its npub. */
    private suspend fun load(pubkeys: List<String>): List<Profile> {
        if (pubkeys.isEmpty()) return emptyList()
        val known = safe { profiles.getAll(pubkeys) }.orEmpty()
        return pubkeys.map { known[it] ?: Profile.empty(it) }
    }

    private suspend fun <T> safe(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        const val PAGE_SIZE = 30
        /** Spec: a big account's followers end here. */
        const val MAX_FOLLOWERS = 1000
    }
}

@HiltViewModel
class FollowsViewModel @Inject constructor(session: NostrSession, handle: SavedStateHandle) : ViewModel() {
    private val ready = session.requireReady()
    val startOnFollowers: Boolean = handle.get<Boolean>("followers") ?: false
    val self: String = ready.pubkey
    val controller = FollowsController(checkNotNull(handle["pubkey"]), ready.followLists, ready.profiles, viewModelScope, follows = ready.follows).also { it.start() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowsScreen(onBack: () -> Unit, onOpenProfile: (String) -> Unit, vm: FollowsViewModel = hiltViewModel()) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    val followed by vm.controller.followed.collectAsStateWithLifecycle()
    val busy by vm.controller.busy.collectAsStateWithLifecycle()
    var confirm by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Profile?>(null) }
    val row: @Composable (Profile) -> Unit = { p ->
        PersonRow(
            p, follow = if (p.pubkey == vm.self) null else followed?.let { if (p.pubkey in it) FollowState.Following else FollowState.NotFollowing } ?: FollowState.Unknown,
            busy = p.pubkey in busy, onOpen = { onOpenProfile(p.pubkey) }, onFollow = { vm.controller.follow(p.pubkey) }, onUnfollow = { confirm = p },
        )
    }
    var tab by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(if (vm.startOnFollowers) 1 else 0) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(state.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().testTag("follows-screen")) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.profile_following_label)) }, modifier = Modifier.testTag("follows-tab-following"))
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.follows_tab_followers)) }, modifier = Modifier.testTag("follows-tab-followers"))
            }
            if (tab == 0) {
                FollowList(state.following, R.string.follows_empty_following, endHint = null, onMore = vm.controller::moreFollowing, row = row, key = "following")
            } else {
                FollowList(state.followers, R.string.follows_empty_followers, endHint = R.string.follows_end_followers, onMore = vm.controller::moreFollowers, row = row, key = "followers")
            }
        }
    }
    // like the profile's button: unfollowing asks first
    confirm?.let { p ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.profile_unfollow_title, p.shownName)) },
            confirmButton = {
                TextButton(onClick = { vm.controller.unfollow(p.pubkey); confirm = null }, modifier = Modifier.testTag("follows-unfollow-confirm")) { Text(stringResource(R.string.profile_unfollow_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** X's row: picture, name and handle beside the follow button, the bio under them (at most three lines); a tap opens the profile. */
@Composable
private fun PersonRow(p: Profile, follow: FollowState?, busy: Boolean, onOpen: () -> Unit, onFollow: () -> Unit, onUnfollow: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 12.dp).testTag("follows-person-${p.pubkey.take(8)}")) {
        Avatar(p.picture)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.shownName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(p.nip05 ?: Npub.short(p.pubkey), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (follow != null) {
                    Spacer(Modifier.width(8.dp))
                    val tag = Modifier.height(32.dp).testTag("follows-follow-${p.pubkey.take(8)}")
                    val pad = PaddingValues(horizontal = 16.dp)
                    when (follow) {
                        FollowState.Following -> OutlinedButton(onClick = onUnfollow, enabled = !busy, contentPadding = pad, modifier = tag) { Text(stringResource(R.string.profile_following), style = MaterialTheme.typography.labelMedium) }
                        FollowState.NotFollowing -> Button(onClick = onFollow, enabled = !busy, contentPadding = pad, modifier = tag) { Text(stringResource(R.string.profile_follow), style = MaterialTheme.typography.labelMedium) }
                        FollowState.Unknown -> Button(onClick = {}, enabled = false, contentPadding = pad, modifier = tag) { Text(stringResource(R.string.profile_follow), style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
            p.about?.trim()?.takeIf { it.isNotEmpty() }?.let { bio ->
                Text(bio, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun FollowList(column: FollowColumn, empty: Int, endHint: Int?, onMore: () -> Unit, row: @Composable (Profile) -> Unit, key: String) {
    val list = androidx.compose.runtime.key(key) { rememberLazyListState() }
    // the next page while the last ten rows come into view, like X
    val nearEnd by remember(list) { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 10 } ?: false } }
    LaunchedEffect(nearEnd, column.people.size) { if (nearEnd && !column.loading && !column.ended) onMore() }
    if (column.people.isEmpty() && column.ended) {
        Box(Modifier.fillMaxSize().padding(24.dp).testTag("follows-empty"), contentAlignment = Alignment.TopCenter) {
            Text(stringResource(empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("follows-list-$key")) {
        items(column.people, key = { it.pubkey }) { p ->
            row(p)
            HorizontalDivider()
        }
        if (column.loading) item(key = "loading") {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
        }
        if (column.ended && endHint != null && column.people.isNotEmpty()) item(key = "end") {
            Text(
                stringResource(endHint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("follows-end"),
            )
        }
    }
}

package com.monostr.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monostr.app.R
import com.monostr.app.ui.theme.PlainShapes
import com.monostr.app.ui.theme.Spacing
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.Poll
import com.monostr.nostr.model.PollTally
import com.monostr.nostr.model.PollVote
import com.monostr.nostr.model.Tally
import com.monostr.nostr.repo.PollRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import kotlin.coroutines.CoroutineContext

/** What a poll card shows (polls spec 2026-11-12). */
sealed interface PollUi {
    /** The votes are being fetched: the options show, not yet tappable. */
    data object Loading : PollUi

    /** Not voted, not ended, not the own poll: the options as pills. [failed] after a vote no relay took; [sending] while one is out. */
    data class Open(val total: Int, val endsAt: Long?, val failed: Boolean = false, val sending: Boolean = false) : PollUi

    /**
     * Results as bars. [followingTally] counts only followed voters; null while the follow list is unknown (no switch then).
     * [followingOn] the «Following» view is chosen (and possible).
     */
    data class Results(val tally: Tally, val followingTally: Tally?, val ended: Boolean, val endsAt: Long?, val followingOn: Boolean) : PollUi

    /** The votes could not be fetched. */
    data object Error : PollUi
}

/**
 * Polls spec 2026-11-12: the state of each shown poll. The votes of a poll are fetched once per screen life and kept
 * (the last [MAX_KEPT] polls); an own vote is merged in locally as soon as a relay took it, no refetch. Counting runs on
 * [counting] (the default dispatcher), never on the main thread.
 */
class PollLoader(
    private val polls: PollRepository,
    private val follows: Flow<Set<String>?>,
    private val muted: StateFlow<Set<String>>,
    private val me: String,
    private val settings: PollSettings,
    /** Unix seconds. */
    private val now: () -> Long,
    /** Where fetches and votes run: they outlive a card that scrolls away. */
    private val scope: CoroutineScope,
    private val counting: CoroutineContext = Dispatchers.Default,
) {
    private class Entry(val note: Note, val poll: Poll) {
        val ui = MutableStateFlow<PollUi>(PollUi.Loading)
        /** The fetched votes; null until the fetch is done (or after it failed). */
        @Volatile var votes: List<PollVote>? = null
        /** The own vote a relay took in this session, merged into every count. */
        @Volatile var own: PollVote? = null
        @Volatile var followingOn = false
        @Volatile var failed = false
        @Volatile var sending = false
        /** A fetch is out or done; reset after a failed one, so the next showing asks again. */
        var fetching = false
        val lock = Mutex()
    }

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > MAX_KEPT
    }

    @Volatile private var followed: Set<String>? = null
    @Volatile private var watching = false
    @Volatile private var firstVoteDone: Boolean? = null

    /** Whether the «Your vote is public» sheet was already shown; false while not known yet (the sheet then shows: privacy first). */
    val firstVoteShown: Boolean get() = firstVoteDone == true

    /** The follow list, the mute list and the first-vote flag are read from the first shown poll on, not before. */
    private fun watch() {
        if (watching) return
        synchronized(this) {
            if (watching) return
            watching = true
        }
        scope.launch {
            combine(follows, muted) { f, _ -> f }.collect { f ->
                followed = f
                synchronized(entries) { entries.values.toList() }.forEach { recompute(it) }
            }
        }
        scope.launch { settings.firstVoteShown.collect { if (firstVoteDone != true) firstVoteDone = it } }
    }

    fun state(poll: Note): StateFlow<PollUi> {
        val p = poll.poll ?: return MutableStateFlow(PollUi.Error)
        watch()
        val e = synchronized(entries) { entries.getOrPut(poll.id) { Entry(poll, p) } }
        val fetch = synchronized(e) { (!e.fetching).also { e.fetching = true } }
        if (fetch) {
            scope.launch {
                val votes = try {
                    polls.votes(e.note)
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    null
                }
                if (votes == null) {
                    synchronized(e) { e.fetching = false }
                    if (e.own == null) e.ui.value = PollUi.Error
                    return@launch
                }
                e.votes = votes
                recompute(e)
            }
        } else if (e.ui.value is PollUi.Open && p.ended(now())) {
            // the poll ended while it was kept: final results now
            scope.launch { recompute(e) }
        }
        return e.ui
    }

    /** The «All | Following» choice for [pollId], for this screen's life. */
    fun showFollowing(pollId: String, on: Boolean) {
        val e = synchronized(entries) { entries[pollId] } ?: return
        e.followingOn = on
        scope.launch { recompute(e) }
    }

    /**
     * Sends the own vote; true when a relay took it (the card then shows results), false keeps the pills with an error line.
     * A vote is final: false at once, nothing sent, while one is out or after one went through. A poll that ended meanwhile
     * sends nothing either and turns to its final results.
     */
    suspend fun vote(poll: Note, optionIds: List<String>): Boolean {
        val e = claim(poll) ?: return false
        return publish(e, optionIds)
    }

    /** [vote] in the loader's scope, so a card scrolled away does not cancel the publish. Claimed at once: a double tap sends one vote. */
    fun send(poll: Note, optionIds: List<String>) {
        val e = claim(poll) ?: return
        scope.launch { publish(e, optionIds) }
    }

    /** The first vote after the sheet: the sheet never shows again, then the vote goes out. */
    fun sendFirst(poll: Note, optionIds: List<String>) {
        val e = claim(poll) ?: return
        firstVoteDone = true
        scope.launch {
            try {
                settings.setFirstVoteShown()
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                // the flag stays in memory for this session; the vote goes out anyway
            }
            publish(e, optionIds)
        }
    }

    /**
     * The entry of [poll], marked sending, or null when no vote may go out: the poll ended (its final results are shown
     * then), a vote is out already or one went through. Synchronous, so two taps in a row cannot both claim it.
     */
    private fun claim(poll: Note): Entry? {
        val p = poll.poll ?: return null
        val e = synchronized(entries) { entries.getOrPut(poll.id) { Entry(poll, p) } }
        if (p.ended(now())) {
            scope.launch { recompute(e) }
            return null
        }
        synchronized(e) {
            if (e.sending || e.own != null) return null
            e.sending = true
            e.failed = false
            (e.ui.value as? PollUi.Open)?.let { e.ui.value = it.copy(sending = true, failed = false) }
        }
        return e
    }

    private suspend fun publish(e: Entry, optionIds: List<String>): Boolean {
        val sent = try {
            polls.vote(e.note, optionIds).takeIf { it.sentToAny }?.eventId
        } catch (c: CancellationException) {
            synchronized(e) { e.sending = false }
            throw c
        } catch (_: Exception) {
            null
        }
        synchronized(e) {
            if (sent != null) e.own = PollVote(sent, me, now(), optionIds) else e.failed = true
            e.sending = false
        }
        recompute(e)
        return sent != null
    }

    private suspend fun recompute(e: Entry) = e.lock.withLock {
        val fetched = e.votes
        if (fetched == null && e.own == null && !e.sending && !e.failed) return@withLock
        val votes = fetched.orEmpty() + listOfNotNull(e.own)
        val mutedNow = muted.value
        val follows = followed
        val ui = withContext(counting) {
            val tally = PollTally.count(e.poll, votes, me, mutedNow)
            val ended = e.poll.ended(now())
            if (tally.mine == null && !ended && e.note.author != me) {
                PollUi.Open(tally.total, e.poll.endsAt, e.failed, e.sending)
            } else {
                val following = follows?.let { PollTally.count(e.poll, votes, me, mutedNow, it) }
                PollUi.Results(tally, following, ended, e.poll.endsAt, e.followingOn && following != null)
            }
        }
        e.ui.value = ui
    }

    private companion object {
        const val MAX_KEPT = 200
    }
}

/** Provided once by the logged-in nav host; null outside of it (isolated UI tests), where a card shows its options only. */
val LocalPollLoader = staticCompositionLocalOf<PollLoader?> { null }

/** «2 d», «5 h», «12 min» until [endsAt]; at least one minute while the poll runs. */
fun pollTimeLeft(endsAt: Long, now: Long): UiText {
    val d = (endsAt - now).coerceAtLeast(0)
    return when {
        d < 3600 -> uiText(R.string.time_minutes, (d / 60).coerceAtLeast(1))
        d < 86400 -> uiText(R.string.time_hours, d / 3600)
        else -> uiText(R.string.time_days, d / 86400)
    }
}

/**
 * Polls spec 2026-11-12, mockup `poll-feed.html`: a poll under its note's text. Before the own vote the options are
 * outlined accent pills (tap = vote) or, for a multiple-choice poll, check boxes and «Vote»; after the own vote, after
 * the end or on the own poll the results as bars. Before the very first vote of the install the [FirstVoteSheet].
 */
@Composable
fun PollCard(note: Note, modifier: Modifier = Modifier) {
    val poll = note.poll ?: return
    val loader = LocalPollLoader.current
    val id8 = note.id.take(8)
    val ui by remember(note.id, loader) { loader?.state(note) ?: MutableStateFlow(PollUi.Loading) }.collectAsState()
    var pending by remember(note.id) { mutableStateOf<List<String>?>(null) }
    val onVote: (List<String>) -> Unit = { ids ->
        if (loader != null) {
            if (loader.firstVoteShown) loader.send(note, ids) else pending = ids
        }
    }
    Column(modifier.fillMaxWidth().testTag("poll-$id8")) {
        when (val u = ui) {
            is PollUi.Results -> {
                val shown = if (u.followingOn && u.followingTally != null) u.followingTally else u.tally
                poll.options.forEach { o -> PollBar(o.label, shown.percent(o.id), o.id in shown.leading, u.tally.mine?.contains(o.id) == true, "poll-bar-$id8-${o.id}") }
            }
            else -> {
                val enabled = u is PollUi.Open && !u.sending
                if (poll.multiple) PollChecks(note.id, poll, enabled, onVote) else poll.options.forEach { o ->
                    PollPill(o.label, enabled, "poll-option-$id8-${o.id}") { onVote(listOf(o.id)) }
                }
            }
        }
        if ((ui as? PollUi.Open)?.failed == true) {
            Text(
                stringResource(R.string.poll_vote_failed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = Spacing.xs).semantics { liveRegion = LiveRegionMode.Polite }.testTag("poll-error-$id8"),
            )
        }
        PollFooter(poll, ui, id8) { on -> loader?.showFollowing(note.id, on) }
    }
    pending?.let { ids ->
        val label = poll.options.filter { it.id in ids }.joinToString(", ") { it.label }
        FirstVoteSheet(
            label,
            onVote = { pending = null; loader?.sendFirst(note, ids) },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun PollPill(label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val voteLabel = stringResource(R.string.poll_vote)
    Box(
        Modifier.padding(top = Spacing.s).fillMaxWidth()
            .clip(CircleShape)
            .border(1.5.dp, accent, CircleShape)
            .clickable(enabled = enabled, onClickLabel = voteLabel, role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = accent, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Multiple choice (other clients only): a check box per option, then «Vote» once one is ticked. */
@Composable
private fun PollChecks(noteId: String, poll: Poll, enabled: Boolean, onVote: (List<String>) -> Unit) {
    val id8 = noteId.take(8)
    var checked by remember(noteId) { mutableStateOf(emptySet<String>()) }
    poll.options.forEach { o ->
        val on = o.id in checked
        Row(
            Modifier.padding(top = Spacing.s).fillMaxWidth()
                .clip(PlainShapes.medium)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                .toggleable(value = on, enabled = enabled, role = Role.Checkbox) { checked = if (it) checked + o.id else checked - o.id }
                .testTag("poll-option-$id8-${o.id}")
                .padding(end = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = on, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(Spacing.s))
            Text(o.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    Button(
        onClick = { onVote(poll.options.map { it.id }.filter { it in checked }) },
        enabled = enabled && checked.isNotEmpty(),
        shape = CircleShape,
        modifier = Modifier.padding(top = Spacing.s).testTag("poll-vote-$id8"),
    ) { Text(stringResource(R.string.poll_vote)) }
}

/** A result bar: the share drawn behind the label (no extra layout), the leading option bold on an accent tint. */
@Composable
private fun PollBar(label: String, percent: Int, leading: Boolean, mine: Boolean, tag: String) {
    // one formatter per bar, kept across recompositions: «42 %» in German, «%42» in Turkish
    val format = remember { NumberFormat.getPercentInstance() }
    val fill = if (leading) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant
    val weight = if (leading) FontWeight.Bold else FontWeight.Normal
    Row(
        Modifier.padding(top = Spacing.s).fillMaxWidth()
            .clip(PlainShapes.small)
            .drawBehind { drawRect(fill, size = Size(size.width * percent / 100f, size.height)) }
            .semantics(mergeDescendants = true) { progressBarRangeInfo = ProgressBarRangeInfo(percent / 100f, 0f..1f) }
            .testTag(tag)
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = weight, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (mine) {
                Spacer(Modifier.width(Spacing.xs))
                Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.poll_your_vote), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp).testTag("$tag-mine"))
            }
        }
        Spacer(Modifier.width(Spacing.s))
        Text(format.format(percent / 100.0), style = MaterialTheme.typography.bodyMedium, fontWeight = weight)
    }
}

/** «N votes · 22 h left», «M of N votes · Final results», «… · Multiple choice»; the «All | Following» switch where results show. */
@Composable
private fun PollFooter(poll: Poll, ui: PollUi, id8: String, onFollowing: (Boolean) -> Unit) {
    val now = System.currentTimeMillis() / 1000
    val parts = ArrayList<String>(3)
    when (ui) {
        is PollUi.Results -> parts += if (ui.followingOn && ui.followingTally != null) {
            stringResource(R.string.poll_of_votes, ui.followingTally.total, ui.tally.total)
        } else {
            pluralStringResource(R.plurals.poll_votes, ui.tally.total, ui.tally.total)
        }
        is PollUi.Open -> parts += pluralStringResource(R.plurals.poll_votes, ui.total, ui.total)
        else -> Unit
    }
    val ended = (ui as? PollUi.Results)?.ended ?: poll.ended(now)
    val endsAt = poll.endsAt
    when {
        ended -> parts += stringResource(R.string.poll_final)
        endsAt != null -> parts += stringResource(R.string.poll_left, pollTimeLeft(endsAt, now).asString())
    }
    if (poll.multiple && ui !is PollUi.Results) parts += stringResource(R.string.poll_multiple)
    Row(Modifier.padding(top = Spacing.s).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            parts.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false).testTag("poll-footer-$id8"),
        )
        if (ui is PollUi.Results && ui.followingTally != null) {
            Spacer(Modifier.width(Spacing.s))
            PollSegments(ui.followingOn, id8, onFollowing)
        }
    }
}

@Composable
private fun PollSegments(following: Boolean, id8: String, onFollowing: (Boolean) -> Unit) {
    Row(Modifier.clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape).selectableGroup()) {
        PollSegment(stringResource(R.string.poll_all), !following, "poll-all-$id8") { onFollowing(false) }
        PollSegment(stringResource(R.string.poll_following), following, "poll-following-$id8") { onFollowing(true) }
    }
}

@Composable
private fun PollSegment(label: String, on: Boolean, tag: String, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(CircleShape)
            .then(if (on) Modifier.background(accent.copy(alpha = 0.15f)) else Modifier)
            .selectable(selected = on, role = Role.Tab, onClick = { if (!on) onClick() })
            .testTag(tag)
            .padding(horizontal = Spacing.s, vertical = Spacing.xxs),
    )
}

/** Polls spec 2026-11-12: once per install, before the very first vote: the vote is public and final. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirstVoteSheet(option: String, onVote: () -> Unit, onDismiss: () -> Unit) {
    GuardedModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("poll-first-vote")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.xl)) {
            Text(stringResource(R.string.poll_public_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(Spacing.m))
            Text(stringResource(R.string.poll_public_text), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(Spacing.xl))
            Button(onClick = onVote, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("poll-first-vote-confirm")) {
                Text(stringResource(R.string.poll_public_vote, option), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(Spacing.s))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().testTag("poll-first-vote-cancel")) {
                Text(stringResource(R.string.action_cancel))
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

package com.monostr.app.ui.article

import com.monostr.app.ui.common.BookmarkActions
import com.monostr.app.ui.common.Message
import com.monostr.app.ui.common.MuteActions
import com.monostr.app.ui.common.NoteDeletions
import com.monostr.app.ui.common.NoteVisibility
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.thread.ThreadRow
import com.monostr.app.ui.feed.withProfiles
import com.monostr.nostr.model.Strands
import com.monostr.nostr.repo.ArticleRepliesRepository
import com.monostr.nostr.repo.MuteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.monostr.app.ui.common.MentionResolver
import com.monostr.app.ui.common.NoteActions
import com.monostr.app.ui.common.asMessage
import com.monostr.app.ui.feed.NoteUi
import com.monostr.app.ui.feed.target
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.nostr.PerfTrace
import com.monostr.nostr.model.Article
import com.monostr.nostr.model.AddressRef
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.AddressResult
import com.monostr.nostr.repo.AddressableRepository
import com.monostr.nostr.repo.CountsRepository
import com.monostr.nostr.repo.MemoryOwnReactions
import com.monostr.nostr.repo.OwnReactions
import com.monostr.nostr.repo.ProfileRepository
import com.monostr.nostr.repo.PublishRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Spec 2026-10-29 article reader: what the reader screen shows. */
sealed interface ArticleUi {
    data object Loading : ArticleUi

    /** [pictures]: the cover then the body pictures (deduplicated), the list the full-screen viewer pages through. */
    data class Shown(val article: Article, val blocks: List<MdBlock>, val minutes: Int, val note: NoteUi, val pictures: List<String>) : ArticleUi
    data object Missing : ArticleUi
    data object Deleted : ArticleUi

    /** The author is muted: the usual placeholder instead of the text. */
    data object Muted : ArticleUi
}

/**
 * The replies under the article, built like the thread screen's strands (spec 2026-10-29 article reader): [rows] hold the
 * direct replies, each with at most one child and a "Show replies" row; [items] the notes the rows name.
 */
data class ArticleReplies(
    val items: List<NoteUi> = emptyList(),
    val rows: List<ThreadRow> = emptyList(),
    val loaded: Boolean = false,
    /** Strand heads only a mute hides whose child is shown: they stay as "Note from a muted account" (like the thread screen), one tap shows them. */
    val muted: Map<String, NoteUi> = emptyMap(),
)

/** The parsed blocks of the last [capacity] articles by event id, so reopening one parses nothing. */
class ArticleBlockCache(private val capacity: Int = 20) {
    class Parsed(val blocks: List<MdBlock>, val minutes: Int)

    private val map = object : LinkedHashMap<String, Parsed>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Parsed>?): Boolean = size > capacity
    }

    @Synchronized fun get(id: String): Parsed? = map[id]
    @Synchronized fun put(id: String, parsed: Parsed) { map[id] = parsed }

    companion object {
        val shared = ArticleBlockCache()
    }
}

internal fun picturesOf(article: Article, blocks: List<MdBlock>): List<String> {
    val out = LinkedHashSet<String>()
    article.image?.let { out += it }
    fun walk(list: List<MdBlock>) {
        for (b in list) when (b) {
            is MdBlock.Picture -> out += b.url
            is MdBlock.Quote -> walk(b.blocks)
            is MdBlock.ListBlock -> b.items.forEach { walk(it) }
            else -> Unit
        }
    }
    walk(blocks)
    return out.toList()
}

/**
 * The reader's state (spec 2026-10-29 article reader): Loading, then Shown / Missing / Deleted. The article text
 * shows as soon as the event is there and parsed ([parseContext], off Main); counts, tips, own reactions and the
 * author's profile arrive after it and only update the [NoteUi] under the text.
 */
class ArticleController(
    private val ref: AddressRef,
    private val addresses: AddressableRepository,
    private val profiles: ProfileRepository,
    publish: PublishRepository,
    private val decorations: TipDecorations,
    private val counts: CountsRepository,
    private val scope: CoroutineScope,
    private val mentions: MentionResolver = MentionResolver(profiles, scope),
    own: OwnReactions = MemoryOwnReactions(),
    private val muted: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
    private val parseContext: CoroutineContext = kotlinx.coroutines.Dispatchers.Default,
    private val cache: ArticleBlockCache = ArticleBlockCache.shared,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    /** False (a link from a direct message): only the user's own relays are asked, no hint and no author relay (spec 8). */
    private val allowForeign: Boolean = true,
    /** The replies under the article; none in tests that do not look at them. */
    private val replies: ArticleRepliesRepository = object : ArticleRepliesRepository {
        override fun observe(ref: AddressRef, allowForeign: Boolean): Flow<List<Note>> = kotlinx.coroutines.flow.emptyFlow()
    },
    deletions: NoteDeletions = NoteDeletions(),
    private val visibility: NoteVisibility = NoteVisibility(deletions, muted),
    afterDelete: suspend (Note) -> UiText? = { null },
    private val bookmarks: BookmarkActions? = null,
    /** Null (tests that do not mute): [mute] and [unmute] do nothing. */
    mute: MuteRepository? = null,
    muteScope: CoroutineScope? = null,
) {
    private val _state = MutableStateFlow<ArticleUi>(ArticleUi.Loading)
    val state: StateFlow<ArticleUi> = _state.asStateFlow()
    private val _message = MutableStateFlow<Message?>(null)
    val message: StateFlow<Message?> = _message.asStateFlow()
    val names: StateFlow<Map<String, String>> get() = mentions.names
    private val actions = NoteActions(publish, scope, counts, deletions, afterDelete, own)
    private val muteActions = mute?.let { MuteActions(it, scope, profiles, background = muteScope) }
    private val _replies = MutableStateFlow(ArticleReplies())
    val repliesState: StateFlow<ArticleReplies> = _replies.asStateFlow()

    /** The raw replies of the last emission, before [visibility]; a muted account unmuted again comes back from here. */
    private var all: List<NoteUi> = emptyList()
    private var strands: List<com.monostr.nostr.model.Strand> = emptyList()
    private var openedAt = 0L
    private var started = false

    /** [openedAtMs]: when the route was opened (the "article shown" mark counts from it); once only, a rotation does not start it again. */
    fun start(openedAtMs: Long = clockMs()) {
        if (started) return
        started = true
        openedAt = openedAtMs
        if (ref.kind != Article.KIND) {
            _state.value = ArticleUi.Missing
            return
        }
        scope.launch {
            val result = try {
                (addresses.cached(ref, allowForeign) as? AddressResult.Found) ?: addresses.get(ref, allowForeign)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AddressResult.Missing
            }
            when (result) {
                AddressResult.Missing -> _state.value = ArticleUi.Missing
                AddressResult.Deleted -> _state.value = ArticleUi.Deleted
                is AddressResult.Found -> show(result)
            }
        }
    }

    private suspend fun show(found: AddressResult.Found) {
        val article = Article.parse(found.event)
        if (article == null) {
            _state.value = ArticleUi.Missing
            return
        }
        if (article.event.pubkey in muted.value) {
            _state.value = ArticleUi.Muted
            return
        }
        val parsed = cache.get(article.event.id) ?: run {
            val t = clockMs()
            val p = withContext(parseContext) { parse(article.event.content) }
            cache.put(article.event.id, p)
            PerfTrace.mark("article parsed ${clockMs() - t}ms ${article.event.content.length}")
            p
        }
        val note = article.asNote()
        val ui = decorations.decorate(NoteUi(note, Profile.empty(note.author), Profile.empty(note.author), counts = counts.counts.value[note.id]))
            .let { u -> bookmarks?.articleFlags(u, article.coordinate) ?: u.copy(bookmarkEnabled = false) }
        _state.value = ArticleUi.Shown(article, parsed.blocks, parsed.minutes, ui, picturesOf(article, parsed.blocks))
        PerfTrace.mark("article shown ${clockMs() - openedAt}ms")
        follow(article, note)
    }

    /** Final review M1: whatever the parser throws, the article still reads, as one plain paragraph. */
    private fun parse(content: String): ArticleBlockCache.Parsed = ArticleBlockCache.Parsed(
        runCatching { ArticleMarkdown.parse(content) }.getOrElse { if (content.isBlank()) emptyList() else listOf(MdBlock.Paragraph(MdText(content.trim(), emptyList()))) },
        runCatching { ArticleMarkdown.readingMinutes(content) }.getOrDefault(1),
    )

    /** Everything that is not the text: started after it is in the state. */
    private fun follow(article: Article, note: Note) {
        counts.requestArticle(note.id, article.coordinate, priority = true)
        actions.check(listOf(note.id))
        mentions.follow(_replies.map { r -> listOf(note) + r.items.map { it.note } })
        decorations.start()
        decorations.add(listOf(note))
        scope.launch { runCatching { profiles.prefetch(listOf(note.author)) } }
        scope.launch { profiles.observe(note.author).collect { p -> refresh { it.copy(author = p, repostedAuthor = p) } } }
        scope.launch { actions.changes.collect { refresh(actions::flags) } }
        scope.launch { decorations.changes.collect { refresh(decorations::decorate) } }
        bookmarks?.let { b -> scope.launch { b.changes.collect { refresh { u -> b.articleFlags(u, article.coordinate) } } } }
        scope.launch { counts.counts.collect { c -> refresh { it.copy(counts = c[note.id]) }; refreshReplies { withCounts(it) } } }
        followReplies(article)
    }

    /** The replies: started after the text is shown, never in its way. A failing fetch leaves the list as it is. */
    private fun followReplies(article: Article) {
        bookmarks?.ensureLoaded()
        scope.launch {
            try {
                replies.observe(article.ref, allowForeign).collect { notes -> build(article.asNote(), notes) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _replies.update { it.copy(loaded = true) }
            }
        }
        scope.launch { profiles.updates.collect { byKey -> all = all.map { it.withProfiles(byKey) }; refreshReplies { it.withProfiles(byKey) } } }
        scope.launch { actions.changes.collect { refreshReplies(actions::flags) } }
        scope.launch { decorations.changes.collect { refreshReplies(decorations::decorate) } }
        bookmarks?.let { b -> scope.launch { b.changes.collect { refreshReplies(b::flags) } } }
        scope.launch { visibility.changes.collect { rows(loaded = _replies.value.loaded) } }
    }

    private suspend fun build(article: Note, notes: List<Note>) {
        strands = Strands.build(article, notes)
        val shown = strands.flatMap { listOfNotNull(it.reply, it.child) }.distinctBy { it.id }
        val byKey = profiles.getAll(shown.map { it.author })
        all = shown.map { n -> NoteUi(n, byKey.getValue(n.author), byKey.getValue(n.author)) }
        decorations.add(shown)
        rows(loaded = true)
    }

    /**
     * Applies [visibility] to the last build: a deleted note leaves, with its strand when it is the head; a head only a mute
     * hides stays as a muted row while its child is shown (the thread screen's rule), else it leaves with its strand.
     */
    private fun rows(loaded: Boolean = true) {
        val current = all.map(::fresh)
        val items = visibility.visible(current)
        val ok = items.mapTo(HashSet()) { it.note.id }
        val mutedOnly = visibility.mutedOnly(current).associateBy { it.note.id }
        val kept = HashMap<String, NoteUi>()
        val rows = buildList {
            for (s in strands) {
                val child = s.child?.takeIf { it.id in ok }
                if (s.reply.id !in ok) {
                    val muted = mutedOnly[s.reply.id]
                    if (muted == null || child == null) continue
                    kept[s.reply.id] = muted
                }
                add(ThreadRow.Reply(s.reply.id, lineBelow = child != null, keepWhenHidden = s.reply.id in kept, orphan = false))
                child?.let { add(ThreadRow.Child(it.id)) }
                if (s.hidden > 0) add(ThreadRow.More("more-${s.reply.id}", open = s.reply.id))
            }
        }
        _replies.value = ArticleReplies(items, rows, loaded, kept)
    }

    private fun refreshReplies(f: (NoteUi) -> NoteUi) = _replies.update { r -> r.copy(items = r.items.map(f), muted = r.muted.mapValues { (_, v) -> f(v) }) }

    private fun fresh(ui: NoteUi): NoteUi = withCounts(actions.flags(bookmarks?.flags(decorations.decorate(ui)) ?: decorations.decorate(ui)))

    private fun withCounts(ui: NoteUi): NoteUi = ui.copy(counts = counts.counts.value[ui.target.id])

    /** Visible reply ids from the list: their counts are asked (like the thread screen). */
    fun visible(noteIds: List<String>) {
        val wanted = noteIds.toSet()
        val targets = _replies.value.items.filter { it.note.id in wanted }.map { it.target.id }
        if (targets.isNotEmpty()) { counts.request(targets); actions.check(targets) }
    }

    fun delete(note: Note) = actions.delete(note)
    /** The article itself: bookmarked privately by its coordinate (the list's own rules, see `BookmarksRepository.toggleArticle`). */
    fun bookmarkArticle() {
        val b = bookmarks ?: return
        val shown = _state.value as? ArticleUi.Shown ?: return
        b.toggleArticle(shown.article.coordinate) { m -> refresh { u -> b.articleFlags(u, shown.article.coordinate) }; m?.let { _message.value = it.asMessage() } }
    }

    fun bookmark(note: Note) {
        bookmarks?.let { b -> b.toggle(note) { m -> _replies.update { r -> r.copy(items = r.items.map(b::flags)) }; m?.let { _message.value = it.asMessage() } } }
    }

    /** Mutes the author at once (the reply list loses the account's notes), the publish runs behind it. */
    fun mute(note: Note) {
        muteActions?.mute(note.author) { m, ok -> _message.value = Message(m, undoMute = note.author.takeIf { ok }) }
    }

    fun unmute(pubkey: String) {
        muteActions?.unmute(pubkey) { m -> if (m != null) _message.value = m.asMessage() }
    }

    private fun refresh(f: (NoteUi) -> NoteUi) {
        _state.update { s -> if (s is ArticleUi.Shown) s.copy(note = f(s.note)) else s }
    }

    fun like(note: Note) = actions.like(note, ::report)
    fun react(note: Note, emoji: String) = actions.react(note, emoji, ::report)
    fun repost(note: Note) = actions.repost(note, ::report)
    fun clearMessage() { _message.value = null }

    private fun report(text: com.monostr.app.ui.common.UiText) {
        refresh(actions::flags)
        _message.value = text.asMessage()
    }
}

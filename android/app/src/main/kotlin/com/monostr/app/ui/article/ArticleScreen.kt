package com.monostr.app.ui.article

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.widthIn
import com.monostr.app.ui.theme.MonostrFonts
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.SubcomposeAsyncImage
import com.monostr.app.R
import com.monostr.app.session.NostrSession
import com.monostr.app.ui.common.AddressCard
import com.monostr.app.ui.common.Avatar
import com.monostr.app.ui.common.LocalOpenArticle
import com.monostr.app.ui.common.LocalAllowForeign
import androidx.compose.runtime.CompositionLocalProvider
import com.monostr.app.ui.common.LoadingScreen
import com.monostr.app.ui.common.MessageSnackbar
import com.monostr.app.ui.common.BookmarkActions
import com.monostr.app.ui.common.NoteActionRow
import com.monostr.app.ui.common.NoteCard
import com.monostr.app.ui.feed.target
import com.monostr.app.ui.common.NoteMenu
import com.monostr.app.ui.common.ShowRepliesRow
import com.monostr.app.ui.common.MutedRow
import com.monostr.app.ui.common.VisibleNotes
import com.monostr.app.ui.common.noteMenu
import com.monostr.app.ui.thread.ThreadRow
import com.monostr.app.ui.common.NoteLinks
import com.monostr.app.ui.common.NoteSpan
import com.monostr.app.ui.common.QuoteCard
import com.monostr.app.ui.media.LocalMediaSettings
import com.monostr.app.ui.media.MediaBoxes
import com.monostr.app.ui.media.MediaCoverBadge
import com.monostr.app.ui.media.MediaRatios
import com.monostr.app.ui.media.MediaReveals
import com.monostr.app.ui.tips.TipSheetFactory
import com.monostr.app.ui.tips.TipSheetHost
import com.monostr.app.ui.tips.TippersDialog
import com.monostr.nostr.model.AddressLinks
import com.monostr.nostr.model.AddressRef
import com.monostr.nostr.model.Article
import com.monostr.nostr.model.NoteAddress
import com.monostr.nostr.model.Note
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ArticleViewModel @Inject constructor(session: NostrSession, handle: SavedStateHandle, factory: TipSheetFactory) : androidx.lifecycle.ViewModel() {
    /** Taken first: the ViewModel is made at the route's first composition, the "article shown" mark counts from here. */
    private val openedAt = System.nanoTime() / 1_000_000
    private val ready = session.requireReady()
    val naddr: String = handle["naddr"] ?: ""
    val allowForeign: Boolean = handle["foreign"] ?: true
    // an undecodable link is kind 0: the controller answers «not found» without asking anyone
    val ref: AddressRef = NoteAddress.decode(naddr) ?: AddressRef(0, "", "")
    val decorations = factory.decorations(viewModelScope)
    val tipSheet = factory.tipSheet(viewModelScope)
    val controller = ArticleController(
        ref, ready.addresses, ready.profiles, ready.publish, decorations, ready.counts, viewModelScope,
        mentions = com.monostr.app.ui.common.MentionResolver(ready.profiles, viewModelScope, kotlinx.coroutines.Dispatchers.Default),
        own = ready.ownReactions, muted = ready.muted, allowForeign = allowForeign,
        replies = ready.articleReplies, deletions = ready.deletions, visibility = ready.visibility, afterDelete = ready.afterNoteDelete,
        bookmarks = BookmarkActions(ready.bookmarks, viewModelScope, background = ready.engine.backgroundScope),
        mute = ready.mute, muteScope = ready.engine.backgroundScope,
    )
    val menu = ready.noteMenu(delete = controller::delete, mute = controller::mute)

    init {
        // once per ViewModel (a rotation keeps it); a cached article is shown before the first frame (Main.immediate)
        controller.start(openedAt)
    }
}

/** What the blocks need besides themselves; one per article. */
private class RenderContext(
    val text: ArticleTextStyle,
    val actions: ArticleLinkActions,
    val articleId: String,
    val sensitive: Boolean,
    val pictures: List<String>,
    val onOpenPicture: (Int) -> Unit,
    val onOpenProfile: (String) -> Unit,
    val onOpenNote: (String) -> Unit,
    val onHashtag: (String) -> Unit,
    val allowForeign: Boolean,
)

/**
 * Spec 2026-10-29 article reader: cover, title, author row, the Markdown body and the action row. Replies are
 * the next task: [onReply] is the hook. The pictures page in the full-screen viewer through [onOpenMedia].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleScreen(
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenArticle: (AddressRef, Boolean) -> Unit,
    onHashtag: (String) -> Unit,
    onOpenMedia: (List<String>, Int) -> Unit,
    /** Comment on the article ([parent] null) or on one of its replies ([parent] = that reply's id). */
    onReply: (AddressRef, String?) -> Unit = { _, _ -> },
    onQuote: (AddressRef) -> Unit = {},
    onQuoteNote: (String) -> Unit = {},
    /** The pictures and videos of a reply, as on the thread screen. */
    onOpenNoteMedia: (String, Int) -> Unit = { _, _ -> },
    onOpenVideo: (String) -> Unit = {},
    vm: ArticleViewModel = hiltViewModel(),
) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    val names by vm.controller.names.collectAsStateWithLifecycle()
    val message by vm.controller.message.collectAsStateWithLifecycle()
    val replies by vm.controller.repliesState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tippersFor by remember { mutableStateOf<Note?>(null) }
    val uriHandler = LocalUriHandler.current
    MessageSnackbar(message, snackbar, onUnmute = vm.controller::unmute, onShown = vm.controller::clearMessage)
    Scaffold(
        modifier = Modifier.testTag("article-screen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.article_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                ArticleUi.Loading -> LoadingScreen()
                ArticleUi.Missing -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(stringResource(R.string.article_missing), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("article-missing"))
                    // a link that does not decode has nothing to open
                    if (vm.ref.kind == Article.KIND) {
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { runCatching { uriHandler.openUri(AddressLinks.web(vm.ref)) } }, modifier = Modifier.testTag("article-open-browser")) {
                            Text(stringResource(R.string.article_open_browser))
                        }
                    }
                }
                ArticleUi.Muted -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.mute_quote_placeholder), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("article-muted"))
                }
                ArticleUi.Deleted -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.article_deleted), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("article-deleted"))
                }
                is ArticleUi.Shown -> Reader(
                    s, names, replies, vm.controller, vm.menu, onOpenProfile, onOpenThread, onOpenArticle, vm.allowForeign, onHashtag, onOpenMedia,
                    onLike = { vm.controller.like(s.note.note) },
                    onReact = { e -> vm.controller.react(s.note.note, e) },
                    onRepost = { vm.controller.repost(s.note.note) },
                    onReply = { onReply(s.article.ref, null) },
                    onReplyTo = { onReply(s.article.ref, it) },
                    onQuoteNote = onQuoteNote,
                    onOpenNoteMedia = onOpenNoteMedia,
                    onOpenVideo = onOpenVideo,
                    onTipNote = { vm.tipSheet.open(it) },
                    onShowTippersFor = { tippersFor = it },
                    onQuote = { onQuote(s.article.ref) },
                    onTip = { vm.tipSheet.open(s.article.asNote()) },
                    onShowTippers = { tippersFor = s.article.asNote() },
                )
            }
        }
    }
    TipSheetHost(vm.tipSheet)
    tippersFor?.let { TippersDialog(it, load = vm.decorations::tippers, onDismiss = { tippersFor = null }) }
}

@Composable
private fun Reader(
    s: ArticleUi.Shown,
    names: Map<String, String>,
    replies: ArticleReplies,
    controller: ArticleController,
    menu: NoteMenu,
    onOpenProfile: (String) -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenArticle: (AddressRef, Boolean) -> Unit,
    allowForeign: Boolean,
    onHashtag: (String) -> Unit,
    onOpenMedia: (List<String>, Int) -> Unit,
    onLike: () -> Unit,
    onReact: (String) -> Unit,
    onRepost: () -> Unit,
    onReply: () -> Unit,
    onReplyTo: (String) -> Unit,
    onQuoteNote: (String) -> Unit,
    onOpenNoteMedia: (String, Int) -> Unit,
    onOpenVideo: (String) -> Unit,
    onTipNote: (Note) -> Unit,
    onShowTippersFor: (Note) -> Unit,
    onQuote: () -> Unit,
    onTip: () -> Unit,
    onShowTippers: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val link = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val noteLabel = stringResource(R.string.note_ref_label)
    val addressLabel = stringResource(R.string.address_ref_label)
    val article = s.article
    val ctx = remember(s.article.event.id, names, link, codeBg, s.pictures, allowForeign) {
        RenderContext(
            text = ArticleTextStyle(link, noteLabel, addressLabel, names, codeBg),
            actions = ArticleLinkActions(
                onUrl = { runCatching { uriHandler.openUri(it) } },
                onHashtag = onHashtag,
                onProfile = onOpenProfile,
                onNote = onOpenThread,
                onAddress = { ref ->
                    val (target, foreign) = embedTarget(ref, allowForeign)
                    if (ref.kind == Article.KIND) onOpenArticle(target, foreign) else runCatching { uriHandler.openUri(AddressLinks.web(target)) }
                },
            ),
            articleId = article.event.id,
            sensitive = article.contentWarning != null,
            pictures = s.pictures,
            onOpenPicture = { i -> onOpenMedia(s.pictures, i) },
            onOpenProfile = onOpenProfile,
            onOpenNote = onOpenThread,
            onHashtag = onHashtag,
            allowForeign = allowForeign,
        )
    }
    // Spec 8: cards and links inside a reader opened from a direct message follow the same rule as the reader itself
    val parentOpen = LocalOpenArticle.current
    val scopedOpen = remember(parentOpen, allowForeign) {
        if (allowForeign || parentOpen == null) parentOpen else { ref: AddressRef, _: Boolean -> val (r, f) = embedTarget(ref, false); parentOpen(r, f) }
    }
    CompositionLocalProvider(LocalOpenArticle provides scopedOpen, LocalAllowForeign provides allowForeign) {
    val listState = rememberLazyListState()
    // backlog 28: muted strand heads the user tapped open, for this screen only (the account stays muted)
    var shownMuted by rememberSaveable { mutableStateOf(emptySet<String>()) }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("article-body")) {
        item(key = "header") { Header(s, ctx, onOpenProfile) }
        items(s.blocks.size, key = { "b$it" }) { i ->
            ReaderColumn(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { BlockView(s.blocks[i], ctx) }
        }
        item(key = "actions") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                HorizontalDivider(Modifier.padding(bottom = 4.dp))
                NoteActionRow(
                    note = s.note, shown = s.note.note,
                    onReply = onReply, onRepost = onRepost, onQuote = onQuote,
                    onLike = onLike, onBookmark = controller::bookmarkArticle, onTip = onTip, onShowTippers = onShowTippers,
                    spread = true, onReact = onReact,
                )
                HorizontalDivider()
            }
        }
        // the replies: the thread screen's rows, after the text; a spinner until they loaded, nothing when there are none
        if (!replies.loaded) item(key = "replies-loading") {
            Box(Modifier.fillMaxWidth().padding(16.dp).testTag("article-replies-loading"), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        // a strand head only a mute hides stays as a muted row while its child shows (thread screen); a tap shows it here
        val byId = replies.items.associateBy { it.note.id } + replies.muted.filterKeys { it in shownMuted }
        itemsIndexed(replies.rows, key = { _, row -> row.id }) { i, row ->
            val next = replies.rows.getOrNull(i + 1)
            if (row is ThreadRow.More) {
                ShowRepliesRow(onClick = { onOpenThread(row.open) })
            } else {
                val n = byId[row.id]
                val lineBelow = row is ThreadRow.Reply && row.lineBelow && next is ThreadRow.Child
                if (n == null && row.id in replies.muted) {
                    MutedRow(lineAbove = false, lineBelow = lineBelow, onShow = { shownMuted = shownMuted + row.id })
                } else if (n != null) {
                    NoteCard(
                        note = n,
                        replyContext = false,
                        onOpen = { onOpenThread(n.note.id) },
                        onOpenProfile = onOpenProfile,
                        onReply = { onReplyTo(n.note.id) },
                        onLike = { controller.like(n.target) },
                        onReact = { e -> controller.react(n.target, e) },
                        onRepost = { controller.repost(n.target) },
                        onQuote = { onQuoteNote(n.target.id) },
                        onTip = { onTipNote(n.target) },
                        onShowTippers = { onShowTippersFor(n.target) },
                        onHashtag = onHashtag,
                        onOpenNote = onOpenThread,
                        onBookmark = { controller.bookmark(n.target) },
                        onOpenMedia = onOpenNoteMedia,
                        onOpenVideo = onOpenVideo,
                        names = names,
                        menu = menu,
                        collapsed = true,
                        lineAbove = row is ThreadRow.Child,
                        lineBelow = lineBelow,
                    )
                }
            }
            if (next == null || next is ThreadRow.Reply) HorizontalDivider()
        }
    }
    VisibleNotes(listState, controller::visible)
    }
}

@Composable
private fun Header(s: ArticleUi.Shown, ctx: RenderContext, onOpenProfile: (String) -> Unit) {
    val article = s.article
    val author = s.note.author
    Column {
        article.image?.let { ArticlePicture(it, ctx, 0, Modifier.fillMaxWidth().testTag("article-cover"), cover = true) }
        ReaderColumn(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
          Column {
            Text(article.title, style = ReaderType.title(), modifier = Modifier.testTag("article-title"))
            Row(Modifier.padding(top = 12.dp).clickable { onOpenProfile(article.event.pubkey) }.testTag("article-author"), verticalAlignment = Alignment.CenterVertically) {
                Avatar(author.picture, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    // the byline in spaced capitals, like a newspaper (2026-10-30)
                    Text(author.shownName.uppercase(), style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.08.em), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val date = DateUtils.formatDateTime(LocalContext.current, article.publishedAt * 1000, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH)
                    Text(
                        date + " · " + pluralStringResource(R.plurals.article_read_time, s.minutes, s.minutes),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
          }
        }
    }
}

/**
 * The reader's book typography (2026-10-30, «Feuilleton»): Newsreader for title, headings and text; code, tables, cards
 * and the replies below keep the app's faces.
 */
private object ReaderType {
    @Composable fun title() = MaterialTheme.typography.headlineMedium.let { it.copy(fontFamily = MonostrFonts.serif, fontWeight = FontWeight.Normal, lineHeight = it.fontSize * 1.25, letterSpacing = (-0.005).em) }
    @Composable fun body() = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonostrFonts.serif, fontSize = 18.sp, lineHeight = (18 * 1.55).sp)
    @Composable fun quote() = body().copy(fontStyle = FontStyle.Italic)
    @Composable fun heading(level: Int) = when (level) {
        1 -> MaterialTheme.typography.headlineSmall
        2 -> MaterialTheme.typography.titleLarge
        else -> MaterialTheme.typography.titleMedium
    }.copy(fontFamily = MonostrFonts.serif, fontWeight = FontWeight.Medium)
}

/** Keeps the text at a readable measure on tablets and in landscape: at most 640 dp wide, centred. */
@Composable
private fun ReaderColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 640.dp).fillMaxWidth().then(modifier)) { content() }
    }
}

@Composable
private fun BlockView(block: MdBlock, ctx: RenderContext, quote: Boolean = false) {
    when (block) {
        is MdBlock.Heading -> Text(richText(block.text, ctx), style = ReaderType.heading(block.level), modifier = Modifier.padding(top = 8.dp))
        is MdBlock.Paragraph -> ParagraphView(block, ctx, quote)
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { block.blocks.forEach { BlockView(it, ctx, quote = true) } }
        }
        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEachIndexed { i, item ->
                Row {
                    Text(if (block.ordered) "${block.start + i}." else "•", style = if (quote) ReaderType.quote() else ReaderType.body(), modifier = Modifier.width(28.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { item.forEach { BlockView(it, ctx, quote) } }
                }
            }
        }
        is MdBlock.Code -> Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).horizontalScroll(rememberScrollState()).padding(12.dp)) {
            Text(block.text, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, softWrap = false)
        }
        is MdBlock.Picture -> {
            val index = ctx.pictures.indexOf(block.url).coerceAtLeast(0)
            ArticlePicture(block.url, ctx, index, Modifier.fillMaxWidth(), alt = block.alt)
        }
        is MdBlock.Table -> TableView(block, ctx)
        MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
    }
}

/** A paragraph that is one `nostr:note`/`naddr` token on its own is the same card a note shows; anything else is text. */
@Composable
private fun ParagraphView(block: MdBlock.Paragraph, ctx: RenderContext, quote: Boolean = false) {
    val own = if (block.text.spans.isEmpty()) NoteLinks.annotate(block.text.text.trim()).singleOrNull() else null
    when {
        own is NoteSpan.AddressRef && own.text.startsWith("nostr:", ignoreCase = true) -> {
            val (ref, foreign) = embedTarget(own.ref, ctx.allowForeign)
            AddressCard(ref, ctx.text.names, onOpenProfile = ctx.onOpenProfile, sensitive = ctx.sensitive, allowForeign = foreign)
        }
        own is NoteSpan.NoteRef && own.text.startsWith("nostr:", ignoreCase = true) ->
            QuoteCard(own.noteId, emptyList(), ctx.text.names, onOpenNote = ctx.onOpenNote, onOpenProfile = ctx.onOpenProfile, onHashtag = ctx.onHashtag)
        else -> Text(richText(block.text, ctx), style = if (quote) ReaderType.quote() else ReaderType.body())
    }
}

@Composable
private fun richText(text: MdText, ctx: RenderContext) = remember(text, ctx) { articleText(text, ctx.text, ctx.actions) }

@Composable
private fun TableView(block: MdBlock.Table, ctx: RenderContext) {
    val border = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.horizontalScroll(rememberScrollState()).border(1.dp, border)) {
        (listOf(block.header) + block.rows).forEachIndexed { r, row ->
            Row(Modifier.height(IntrinsicSize.Min)) {
                row.forEach { cell ->
                    Text(
                        richText(cell, ctx), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal),
                        modifier = Modifier.width(150.dp).fillMaxHeight().border(0.5.dp, border).padding(8.dp),
                    )
                }
            }
        }
    }
}

/**
 * A cover or body picture: same rules as the pictures of a note. A sensitive article ([RenderContext.sensitive]) covers all its
 * pictures until a tap, "media on tap" waits for one; neither is fetched before. A tap opens the viewer at [index].
 */
@Composable
private fun ArticlePicture(url: String, ctx: RenderContext, index: Int, modifier: Modifier, alt: String = "", cover: Boolean = false) {
    val settings = LocalMediaSettings.current
    val revealed by MediaReveals.revealed.collectAsStateWithLifecycle()
    val covered = settings.blurSensitive && ctx.sensitive && ctx.articleId !in revealed
    val waiting = !covered && settings.mediaOnTap && ctx.articleId !in revealed
    var learned by remember(url) { mutableStateOf(MediaRatios[url]) }
    var failed by remember(url) { mutableStateOf(false) }
    BoxWithConstraints(modifier) {
        val box = MediaBoxes.single(learned, widthDp = maxWidth.value)
        val ratio = if (cover) box.ratio.coerceIn(1f, 2f) else box.ratio
        Box(
            Modifier.fillMaxWidth().aspectRatio(ratio).then(if (cover) Modifier else Modifier.clip(RoundedCornerShape(12.dp)))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable {
                    when {
                        covered || waiting -> MediaReveals.reveal(ctx.articleId)
                        else -> ctx.onOpenPicture(index)
                    }
                }
                .testTag("article-picture-$index"),
            contentAlignment = Alignment.Center,
        ) {
            when {
                covered || waiting -> MediaCoverBadge(
                    if (covered) Icons.Filled.VisibilityOff else Icons.Outlined.Image,
                    title = stringResource(if (covered) R.string.media_sensitive else R.string.media_tap_to_load),
                )
                failed -> Icon(Icons.Filled.BrokenImage, contentDescription = stringResource(R.string.media_failed), tint = MaterialTheme.colorScheme.outline)
                else -> SubcomposeAsyncImage(
                    model = url, contentDescription = alt.ifBlank { null },
                    contentScale = if (cover) ContentScale.Crop else ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                    onSuccess = { r -> MediaRatios.put(url, r.result.image.width, r.result.image.height); learned = MediaRatios[url] },
                    onError = { failed = true },
                )
            }
        }
    }
}

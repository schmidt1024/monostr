package com.monostr.app.translate

import com.monostr.app.ui.article.MdBlock
import com.monostr.app.ui.article.MdText
import com.monostr.nostr.PerfTrace
import com.monostr.nostr.model.Article
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One article translated block by block (spec 2026-11-03 «Articles»): the title, the summary and every text of the body
 * (headings, paragraphs, list items, quotes, table cells) as [MdHtml], code blocks and pictures untouched. The title
 * goes alone first, then one block per request, in reading order, strictly one request at a time: the 2-core server
 * works through two requests no faster than one after the other, so a second only delays the first (Pixel check
 * 2026-11-03). Very short neighbours share a request up to [COMBINE_CHARS] characters. Each answer shows at once in
 * [blocks]; each call's timeout follows its length ([timeoutMs]). A refused (429) or failed request stops the rest, which keep their original until
 * [retry]; a block whose tags come back broken, or one longer than the server takes in one request ([MAX_PIECE_CHARS]),
 * keeps its original for good. Either sets [partial].
 */
class ArticleTranslation internal constructor(
    /** server, texts, source, target, the call's timeout in milliseconds */
    private val translate: suspend (TranslateServer, List<String>, String, String, Long) -> TranslateResult,
    val server: TranslateServer,
    private val source: String,
    val target: String,
) {
    constructor(client: TranslateClient, server: TranslateServer, source: String, target: String) :
        this({ s, q, f, t, ms -> client.translate(s, q, f, t, ms) }, server, source, target)

    private enum class Status { PENDING, DONE, KEPT }

    private class Piece(val original: MdText, val enc: MdHtml.Encoded) {
        @Volatile var status = when {
            !enc.prose -> Status.DONE
            enc.html.length > MAX_PIECE_CHARS -> Status.KEPT // the server would refuse it (400) on every retry
            else -> Status.PENDING
        }
        @Volatile var result: MdText = original
    }

    private val lock = Any()
    private var pieces: List<Piece> = emptyList()
    private var titleAt = -1
    private var summaryAt = -1
    private var bodyAt = 0
    private var original: List<MdBlock> = emptyList()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()
    private val _summary = MutableStateFlow<String?>(null)
    val summary: StateFlow<String?> = _summary.asStateFlow()
    private val _blocks = MutableStateFlow<List<MdBlock>>(emptyList())
    val blocks: StateFlow<List<MdBlock>> = _blocks.asStateFlow()
    private val _partial = MutableStateFlow(false)
    /** Some text shows its original: a block came back broken, or a request failed and [retry] is open. */
    val partial: StateFlow<Boolean> = _partial.asStateFlow()
    private val _stopped = MutableStateFlow(false)
    /** A request was refused or failed: texts are left untranslated, [retry] resumes at the first of them. */
    val stopped: StateFlow<Boolean> = _stopped.asStateFlow()
    private val _translated = MutableStateFlow(0)
    /** How many texts show a translation. */
    val translated: StateFlow<Int> = _translated.asStateFlow()

    suspend fun run(title: String, summary: String?, blocks: List<MdBlock>) {
        synchronized(lock) {
            val list = ArrayList<Piece>()
            fun add(t: MdText): Int { list += Piece(t, MdHtml.encode(t)); return list.size - 1 }
            titleAt = if (title.isNotBlank()) add(MdText(title, emptyList())) else -1
            summaryAt = if (!summary.isNullOrBlank()) add(MdText(summary, emptyList())) else -1
            bodyAt = list.size
            mapTexts(blocks) { add(it); it }
            original = blocks
            pieces = list
            _title.value = title
            _summary.value = summary
            _blocks.value = blocks
        }
        send(first = true)
    }

    /** Sends what a refused or failed request left, from the first untranslated text on. */
    suspend fun retry() {
        if (!_stopped.value) return
        _stopped.value = false
        publish()
        send(first = false)
    }

    /** [first]: the run's first request, the title alone. One request at a time, in reading order. */
    private suspend fun send(first: Boolean) {
        val (todo, lead) = synchronized(lock) {
            val todo = pieces.filter { it.status == Status.PENDING }
            todo to if (first && titleAt >= 0 && todo.firstOrNull() === pieces[titleAt]) 1 else 0
        }
        for (request in batches(todo.map { it.enc.html.length }, lead)) {
            if (!sendBatch(request.map { todo[it] })) break // stopped: the rest waits for [retry]
        }
        publish()
    }

    /** False when the server refused or failed: the caller sends nothing more. */
    private suspend fun sendBatch(batch: List<Piece>): Boolean {
        val r = try {
            val q = batch.map { it.enc.html }
            translate(server, q, source, target, timeoutMs(q.sumOf { it.length }))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TranslateResult.Failed
        }
        if (r !is TranslateResult.Ok || r.texts.size != batch.size) {
            _stopped.value = true
            publish()
            return false
        }
        synchronized(lock) {
            batch.forEachIndexed { i, p ->
                val back = MdHtml.decode(r.texts[i], p.enc)
                if (back == null) p.status = Status.KEPT else { p.result = back; p.status = Status.DONE }
            }
        }
        publish()
        return true
    }

    private fun publish() {
        synchronized(lock) {
            val list = pieces
            if (titleAt >= 0) _title.value = list[titleAt].result.text
            if (summaryAt >= 0) _summary.value = list[summaryAt].result.text
            var at = bodyAt
            _blocks.value = mapTexts(original) { list[at++].result }
            _translated.value = list.count { it.status == Status.DONE && it.result !== it.original }
            _partial.value = list.any { it.status == Status.KEPT } || (_stopped.value && list.any { it.status == Status.PENDING })
        }
    }

    companion object {
        /** At most this many very short texts share one request. */
        const val MAX_TEXTS = 8
        /** Neighbouring texts share a request while together at most this long (short list items, table cells); a longer one goes alone. */
        const val COMBINE_CHARS = 300
        /** The server's char limit per request (LibreTranslate `--char-limit`, translate/compose.yml): a longer text is never sent. */
        const val MAX_PIECE_CHARS = 5000
        /** A call's timeout: [TIMEOUT_BASE_MS] plus [TIMEOUT_PER_CHAR_MS] per character, at most [TIMEOUT_MAX_MS]; the 2-core server took ~9.4 ms per character fr→de via English. */
        const val TIMEOUT_BASE_MS = 10_000L
        const val TIMEOUT_PER_CHAR_MS = 12L
        const val TIMEOUT_MAX_MS = 60_000L

        internal fun timeoutMs(chars: Int): Long = (TIMEOUT_BASE_MS + TIMEOUT_PER_CHAR_MS * chars).coerceAtMost(TIMEOUT_MAX_MS)

        /**
         * The requests for texts of [lengths], as indexes, in reading order: the first [lead] texts alone each (the title of a
         * fresh run; 0: none); then one text per request, neighbours together while they fit [COMBINE_CHARS] characters and
         * [MAX_TEXTS] texts.
         */
        internal fun batches(lengths: List<Int>, lead: Int): List<List<Int>> {
            val out = ArrayList<List<Int>>()
            var cur = ArrayList<Int>()
            var chars = 0
            val head = lead.coerceIn(0, lengths.size)
            for (i in 0 until head) out += listOf(i)
            for (i in head until lengths.size) {
                val len = lengths[i]
                if (cur.isNotEmpty() && (cur.size >= MAX_TEXTS || chars + len > COMBINE_CHARS)) {
                    out += cur
                    cur = ArrayList()
                    chars = 0
                }
                cur += i
                chars += len
            }
            if (cur.isNotEmpty()) out += cur
            return out
        }

        /** [blocks] with each text replaced by [f], in reading order; code, pictures and rules as they are. */
        internal fun mapTexts(blocks: List<MdBlock>, f: (MdText) -> MdText): List<MdBlock> = blocks.map { b ->
            when (b) {
                is MdBlock.Heading -> b.copy(text = f(b.text))
                is MdBlock.Paragraph -> b.copy(text = f(b.text))
                is MdBlock.Quote -> b.copy(blocks = mapTexts(b.blocks, f))
                is MdBlock.ListBlock -> b.copy(items = b.items.map { mapTexts(it, f) })
                is MdBlock.Table -> b.copy(header = b.header.map(f), rows = b.rows.map { row -> row.map(f) })
                is MdBlock.Code, is MdBlock.Picture, MdBlock.Rule -> b
            }
        }
    }
}

/** What the reader shows about translating its article; [row] drives the header's [com.monostr.app.ui.common.TranslateRow]. */
data class ArticleTranslationUi(
    val row: TranslationState = TranslationState.None,
    /** The translated title and blocks while they show (progressively while loading); null: the original. */
    val title: String? = null,
    val blocks: List<MdBlock>? = null,
    /** «Some parts could not be translated», once the run is over. */
    val partial: Boolean = false,
    /** A request failed mid-way: the partial note offers «Retry». */
    val canRetry: Boolean = false,
)

/**
 * The reader's translation (spec 2026-11-03 «Articles»): detects the article's language off the main thread (its NIP-32
 * label wins, else title, summary and the first paragraphs), offers «Translate» when that is not the reader's language
 * and a server is set, and runs one [ArticleTranslation] in [scope], the reader's: leaving the reader cancels it.
 * [checkTarget] follows a switched app language: the translation for the old one is dropped.
 */
class ArticleTranslator internal constructor(
    settings: TranslationSettingsStore,
    private val detect: (content: String, label: String?) -> String?,
    /** server, texts, source, target, the call's timeout in milliseconds */
    private val translator: suspend (TranslateServer, List<String>, String, String, Long) -> TranslateResult,
    private val target: () -> String,
    private val scope: CoroutineScope,
    private val work: CoroutineDispatcher = Dispatchers.Default,
    /** Where the language is detected: the detector's own lane in the app (final review I2). */
    private val detectOn: CoroutineDispatcher = work,
) {
    constructor(settings: TranslationSettingsStore, detector: LanguageDetector, client: TranslateClient, scope: CoroutineScope) :
        this(
            settings, detector::detect, { s, q, f, t, ms -> client.translate(s, q, f, t, ms) }, { Languages.target(Translations.systemLocales()) }, scope,
            Dispatchers.Default, LanguageDetector.LANE,
        )

    private val server = MutableStateFlow<TranslateServer?>(null)
    private val raw = MutableStateFlow(ArticleTranslationUi())
    val state: StateFlow<ArticleTranslationUi> = combine(server, raw) { s, r -> if (s == null) ArticleTranslationUi() else r }
        .stateIn(scope, SharingStarted.Eagerly, ArticleTranslationUi())

    /** Guards [detected], [targetNow], [source], [current], [job] and the writes of [raw]. Never held across a suspension. */
    private val lock = Any()
    /** Detection is over: [lang] holds its answer, [targetNow] the target [source] was set for. */
    @Volatile private var detected = false
    @Volatile private var lang: String? = null
    @Volatile private var targetNow: String? = null
    @Volatile private var source: String? = null
    @Volatile private var article: Article? = null
    @Volatile private var blocks: List<MdBlock> = emptyList()
    @Volatile private var current: ArticleTranslation? = null
    @Volatile private var job: Job? = null

    init {
        scope.launch {
            combine(settings.mode, settings.customUrl, ::translateServer).distinctUntilChanged().collect { server.value = it }
        }
    }

    /** Once per article: detects its language in the background; never blocks the caller. */
    fun prepare(article: Article, blocks: List<MdBlock>) {
        if (this.article != null) return
        this.article = article
        this.blocks = blocks
        scope.launch(detectOn) {
            val t0 = System.nanoTime()
            val found = runCatching { detect(sample(article, blocks), article.language) }.getOrNull() // a failing model: no button
            PerfTrace.mark("article language ${(System.nanoTime() - t0) / 1_000_000}ms $found")
            val to = target()
            synchronized(lock) {
                lang = found?.takeIf { it in Languages.SUPPORTED }
                targetNow = to
                source = lang?.takeIf { it != to }
                detected = true
                source?.let { s -> if (raw.value.row == TranslationState.None) raw.value = ArticleTranslationUi(TranslationState.Offer(s)) }
            }
        }
    }

    /**
     * Reads the reader's language again (the reader calls it whenever its locales change). When it changed since the
     * offer, a running translation is cancelled and the one for the old language dropped: «Translate» again, or nothing
     * when the article is in the new language.
     */
    fun checkTarget() {
        val to = target()
        synchronized(lock) {
            if (!detected || to == targetNow) return
            targetNow = to
            job?.cancel()
            job = null
            current = null
            source = lang?.takeIf { it != to }
            raw.value = source?.let { ArticleTranslationUi(TranslationState.Offer(it)) } ?: ArticleTranslationUi()
        }
    }

    /** «Translate» (after the consent) and «Retry»: starts, resumes, or shows the translation again. */
    fun translate() {
        checkTarget()
        if (job?.isActive == true) return
        val s = server.value ?: return
        val from = source ?: return
        val to = targetNow ?: return
        val a = article ?: return
        val t = current
        when {
            t == null -> {
                val fresh = ArticleTranslation(translator, s, from, to)
                current = fresh
                launch(fresh) { fresh.run(a.title, null, blocks) } // the reader shows no summary: not sent
            }
            t.stopped.value -> launch(t) { t.retry() }
            raw.value.row is TranslationState.Original -> showTranslation()
        }
    }

    fun showOriginal() {
        val r = raw.value
        (r.row as? TranslationState.Shown)?.let { raw.value = r.copy(row = TranslationState.Original(it.text, it.host)) }
    }

    fun showTranslation() {
        val t = current ?: return
        if (raw.value.row is TranslationState.Original) publish(t, loading = false)
    }

    private fun launch(t: ArticleTranslation, block: suspend () -> Unit) {
        publish(t, loading = true)
        job = scope.launch(work) {
            val watch = launch { combine(t.title, t.blocks) { _, _ -> }.collect { publish(t, loading = true) } }
            try {
                block()
            } finally {
                // joined, not just cancelled: a «loading» publish still running on another thread must not land after the last one
                withContext(NonCancellable) { watch.cancelAndJoin() }
            }
            publish(t, loading = false)
        }
    }

    private fun publish(t: ArticleTranslation, loading: Boolean) {
        synchronized(lock) {
            if (current !== t) return // dropped by a language switch
            val failed = !loading && t.stopped.value && t.translated.value == 0
            raw.value = when {
                failed -> ArticleTranslationUi(TranslationState.Failed)
                else -> ArticleTranslationUi(
                    row = if (loading) TranslationState.Loading else TranslationState.Shown("", t.server.host),
                    title = t.title.value,
                    blocks = t.blocks.value,
                    partial = !loading && t.partial.value,
                    canRetry = !loading && t.stopped.value,
                )
            }
        }
    }

    companion object {
        /** The text the language is detected on: title, summary and the first paragraphs, about [SAMPLE_CHARS] characters. */
        internal fun sample(article: Article, blocks: List<MdBlock>): String {
            val sb = StringBuilder(article.title)
            article.summary?.let { sb.append('\n').append(it) }
            ArticleTranslation.mapTexts(blocks) { t -> if (sb.length < SAMPLE_CHARS) sb.append('\n').append(t.text); t }
            return if (sb.length > SAMPLE_CHARS) sb.substring(0, SAMPLE_CHARS) else sb.toString()
        }

        const val SAMPLE_CHARS = 1000
    }
}

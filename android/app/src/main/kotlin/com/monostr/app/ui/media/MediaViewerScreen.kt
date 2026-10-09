package com.monostr.app.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.monostr.app.R
import com.monostr.app.session.NostrSession
import com.monostr.nostr.model.NoteMedia
import com.monostr.nostr.repo.ThreadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MediaViewerUiState(val media: List<NoteMedia> = emptyList(), val page: Int = 0, val missing: Boolean = false)

/**
 * Pictures of one note (videos skipped), starting at the picture behind [index] of note.media (spec 3.3),
 * or a plain list of picture URLs (direct messages, whose notes are not in the database).
 */
class MediaViewerController private constructor(private val load: suspend () -> MediaViewerUiState, private val scope: CoroutineScope) {
    constructor(noteId: String, index: Int, threads: ThreadRepository, scope: CoroutineScope) : this({ fromNote(noteId, index, threads) }, scope)

    constructor(urls: List<String>, index: Int = 0, scope: CoroutineScope) : this(
        { MediaViewerUiState(urls.map { NoteMedia(it, NoteMedia.Kind.IMAGE) }, index.coerceIn(0, (urls.size - 1).coerceAtLeast(0)), missing = urls.isEmpty()) },
        scope,
    )

    private val _state = MutableStateFlow(MediaViewerUiState())
    val state: StateFlow<MediaViewerUiState> = _state.asStateFlow()

    fun start() {
        scope.launch { val loaded = load(); _state.update { loaded } }
    }

    private companion object {
        suspend fun fromNote(noteId: String, index: Int, threads: ThreadRepository): MediaViewerUiState {
            val note = runCatching { threads.note(noteId) }.getOrNull() ?: return MediaViewerUiState(missing = true)
            val pictures = note.media.filter { it.kind == NoteMedia.Kind.IMAGE }
            val target = note.media.getOrNull(index)
            val page = pictures.indexOfFirst { it.url == target?.url }.coerceAtLeast(0)
            return MediaViewerUiState(pictures, page, missing = pictures.isEmpty())
        }
    }
}

@HiltViewModel
class MediaViewerViewModel @Inject constructor(session: NostrSession, handle: SavedStateHandle) : ViewModel() {
    private val urls = handle.get<String>("u")?.split("\n")?.filter { it.isNotBlank() }
    val controller = if (urls != null) {
        MediaViewerController(urls, handle.get<Int>("i") ?: 0, viewModelScope)
    } else {
        MediaViewerController(checkNotNull(handle["noteId"]), handle.get<Int>("index") ?: 0, session.requireReady().threads, viewModelScope)
    }
    init { controller.start() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(onBack: () -> Unit, vm: MediaViewerViewModel = hiltViewModel()) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.missing) { if (state.missing) onBack() }
    if (state.media.isEmpty()) return
    val pager = rememberPagerState(initialPage = state.page) { state.media.size }
    var sheetFor by remember { mutableStateOf<NoteMedia?>(null) }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("media-viewer")) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            ZoomableImage(state.media[page], onDismiss = onBack, onLongPress = { sheetFor = state.media[page] })
        }
        // edge-to-edge: keep the header below the status bar
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("media-close")) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.media_viewer_close), tint = Color.White) }
            Text(stringResource(R.string.media_viewer_position, pager.currentPage + 1, state.media.size), color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
    sheetFor?.let { m ->
        val clipboard = LocalClipboardManager.current
        val uriHandler = LocalUriHandler.current
        ModalBottomSheet(onDismissRequest = { sheetFor = null }) {
            ListItem(headlineContent = { Text(stringResource(R.string.media_copy_link)) }, leadingContent = { Icon(Icons.Filled.ContentCopy, null) }, modifier = Modifier.clickable { clipboard.setText(AnnotatedString(m.url)); sheetFor = null })
            ListItem(headlineContent = { Text(stringResource(R.string.media_open_browser)) }, leadingContent = { Icon(Icons.Filled.OpenInBrowser, null) }, modifier = Modifier.clickable { runCatching { uriHandler.openUri(m.url) }; sheetFor = null })
        }
    }
}

/** Pinch 1x-5x, pan while zoomed, double tap 1x <-> 2.5x, vertical drag > 120 dp at 1x dismisses (spec 3.3). */
@Composable
private fun ZoomableImage(m: NoteMedia, onDismiss: () -> Unit, onLongPress: () -> Unit) {
    var scale by remember(m.url) { mutableFloatStateOf(1f) }
    var offset by remember(m.url) { mutableStateOf(Offset.Zero) }
    val dismissPx = with(LocalDensity.current) { 120.dp.toPx() }
    var dragY by remember { mutableFloatStateOf(0f) }
    AsyncImage(
        model = m.url, contentDescription = m.alt, contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(m.url) {
                // Not detectTransformGestures: it consumes one-finger pans at 1x, which blocks the pager swipe and the
                // vertical dismiss. Here events are consumed only while zoomed or while two or more fingers are down.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (scale > 1f || fingers >= 2) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            offset = if (scale > 1f) offset + event.calculatePan() else Offset.Zero
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(m.url) {
                detectTapGestures(
                    onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero },
                    onLongPress = { onLongPress() },
                )
            }
            .then(
                if (scale == 1f) {
                    Modifier.pointerInput(m.url) {
                        detectVerticalDragGestures(
                            onDragEnd = { if (scale == 1f && kotlin.math.abs(dragY) > dismissPx) onDismiss(); dragY = 0f },
                            onVerticalDrag = { _, dy -> if (scale == 1f) dragY += dy },
                        )
                    }
                } else {
                    Modifier
                },
            )
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
    )
}

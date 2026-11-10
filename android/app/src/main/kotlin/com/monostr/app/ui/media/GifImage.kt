package com.monostr.app.ui.media

import android.graphics.drawable.Animatable
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.asDrawable
import coil3.compose.AsyncImage
import coil3.decode.Decoder
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.monostr.app.R
import com.monostr.app.data.GifMode
import com.monostr.app.ui.theme.Spacing
import com.monostr.nostr.PerfTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Asks for the frames of a GIF. No memory cache: a frame-less bitmap of the same URL (a thumbnail, a quote card) must
 * not answer this request, and an animation is never cached there anyway; the file stays in the disk cache.
 */
@Composable
fun rememberGifRequest(url: String): ImageRequest {
    val context = LocalContext.current
    return remember(url) {
        val factory: Decoder.Factory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) AnimatedImageDecoder.Factory() else GifDecoder.Factory()
        ImageRequest.Builder(context).data(url).decoderFactory(factory).memoryCachePolicy(CachePolicy.DISABLED).build()
    }
}

/**
 * A GIF in a note (backlog 66): plays by itself while it is the topmost one in view, for [GifPlayback.playMillis], then
 * stays on its frame; the badge plays and pauses it. Never shown behind a cover: the caller only composes it once the
 * media may load. A tap elsewhere on the cell opens the viewer, where it loops.
 */
@Composable
fun GifImage(
    url: String, key: String, contentDescription: String?, contentScale: ContentScale, alignment: Alignment, placeholder: Painter?,
    onSize: ((Int, Int) -> Unit)?, onError: () -> Unit, modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mode = LocalMediaSettings.current.gifMode
    if (mode == GifMode.OFF) {
        // the first frame, as any picture
        AsyncImage(
            model = url, contentDescription = contentDescription, contentScale = contentScale, alignment = alignment, modifier = modifier.fillMaxSize(),
            placeholder = placeholder, onSuccess = { s -> onSize?.invoke(s.result.image.width, s.result.image.height) }, onError = { onError() },
        )
        return
    }
    val animationsOff = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    var animation by remember(url) { mutableStateOf<Animatable?>(null) }
    var info by remember(url) { mutableStateOf<GifTiming.Info?>(null) }
    var bytes by remember(url) { mutableStateOf<Long?>(null) }
    // the badge: a play that does not wait for its turn, and a pause
    var tapped by remember(url) { mutableStateOf(false) }
    var playing by remember(url) { mutableStateOf(false) }
    val active by Gifs.stage.active.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resumed by lifecycle.currentStateFlow.collectAsStateWithLifecycle()

    DisposableEffect(key) { onDispose { Gifs.stage.remove(key) } }
    val a = animation
    LaunchedEffect(a) {
        if (a == null) return@LaunchedEffect
        // read after the download: the same file, nothing extra over the network
        withContext(Dispatchers.IO) {
            runCatching {
                SingletonImageLoader.get(context).diskCache?.openSnapshot(url)?.use { snapshot ->
                    val fs = SingletonImageLoader.get(context).diskCache!!.fileSystem
                    bytes = fs.metadata(snapshot.data).size
                    fs.read(snapshot.data) { info = GifTiming.read(inputStream()) }
                }
            }
        }
        PerfTrace.mark("gif ready ${info?.frames} frames ${info?.loopMillis}ms")
    }
    val moves = info?.let { it.frames > 1 } ?: true
    val mayStart = GifPlayback.autoplays(mode, animationsOff, bytes) && active == key && !Gifs.hasPlayed(key)
    val wanted = moves && resumed.isAtLeast(Lifecycle.State.RESUMED) && (tapped || mayStart)
    LaunchedEffect(a, wanted, info) {
        if (a == null) return@LaunchedEffect
        if (!wanted) {
            a.stop()
            playing = false
            return@LaunchedEffect
        }
        a.start()
        playing = true
        delay(GifPlayback.playMillis(info?.loopMillis))
        a.stop()
        playing = false
        tapped = false
        Gifs.markPlayed(key)
    }
    Box(
        modifier.fillMaxSize().onGloballyPositioned { c ->
            val height = c.size.height
            if (height > 0) {
                val bounds = c.boundsInWindow()
                Gifs.stage.report(key, bounds.top, bounds.height / height)
            }
        },
    ) {
        AsyncImage(
            model = rememberGifRequest(url), contentDescription = contentDescription, contentScale = contentScale, alignment = alignment,
            modifier = Modifier.fillMaxSize(), placeholder = placeholder,
            onSuccess = { s ->
                onSize?.invoke(s.result.image.width, s.result.image.height)
                animation = s.result.image.asDrawable(context.resources) as? Animatable
            },
            onError = { onError() },
        )
        if (a != null && moves) {
            val label = stringResource(if (playing) R.string.media_gif_pause else R.string.media_gif_play)
            Row(
                Modifier.align(Alignment.BottomStart).padding(Spacing.s).background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small)
                    .clickable(onClickLabel = label) {
                        if (playing) {
                            tapped = false
                            Gifs.markPlayed(key)
                            a.stop()
                            playing = false
                        } else {
                            Gifs.markPlayed(key)
                            tapped = true
                        }
                    }
                    .padding(horizontal = Spacing.s, vertical = Spacing.xs)
                    .testTag(if (playing) "gif-pause-$key" else "gif-play-$key"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = label, tint = Color.White, modifier = Modifier.size(16.dp))
                Text("GIF", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
    }
}
